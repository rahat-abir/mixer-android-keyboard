package dev.banglish.keyboard.glide

import java.io.File
import java.io.InputStream

/**
 * Bangla words for glide typing (assets/bn_glide.txt: word, frequency 0–255, up to four
 * lower-case swipe spellings). Every spelling is its own entry pointing at the same word, so
 * "valo" and "bhalo" both find ভালো. A swipe carries no capital letters; the Bangla word itself
 * decides ত vs ট, so case never has to be guessed.
 */
class BanglaGlideLexicon private constructor(
    private val paths: Array<String>,
    private val wordOf: IntArray,
    private val words: Array<String>,
    private val freqs: IntArray,
) : GlideLexicon {
    override fun path(i: Int) = paths[i]
    override fun word(i: Int) = words[wordOf[i]]
    override fun freq(i: Int) = freqs[wordOf[i]]

    override val byFirstLetter: Array<IntArray> by lazy {
        val buckets = Array(26) { ArrayList<Int>() }
        // Entries are already in frequency order (the file is sorted most-common first).
        for (i in paths.indices) {
            val c = paths[i][0] - 'a'
            if (c in 0..25) buckets[c] += i
        }
        Array(26) { buckets[it].toIntArray() }
    }

    companion object {
        fun load(input: InputStream): BanglaGlideLexicon {
            val paths = ArrayList<String>(160_000)
            val wordOf = ArrayList<Int>(160_000)
            val words = ArrayList<String>(60_000)
            val freqs = ArrayList<Int>(60_000)
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t1 = line.indexOf('\t'); if (t1 <= 0) continue
                    val t2 = line.indexOf('\t', t1 + 1); if (t2 < 0) continue
                    val wi = words.size
                    words += line.substring(0, t1)
                    freqs += line.substring(t1 + 1, t2).toIntOrNull() ?: 0
                    for (p in line.substring(t2 + 1).split(' ')) if (p.isNotEmpty()) { paths += p; wordOf += wi }
                }
            }
            return BanglaGlideLexicon(paths.toTypedArray(), wordOf.toIntArray(), words.toTypedArray(), freqs.toIntArray())
        }
    }
}

/** How often the user ended up with each Bangla word (glided, picked, or tap-typed). */
class WordUses(private val file: File?) {
    private val counts = HashMap<String, Int>()
    @Volatile private var dirty = false

    init {
        try {
            file?.takeIf { it.exists() }?.forEachLine { l ->
                val t = l.lastIndexOf('\t'); if (t > 0) counts[l.substring(0, t)] = l.substring(t + 1).toIntOrNull() ?: 1
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun count(w: String) = counts[w] ?: 0
    @Synchronized fun add(w: String) { if (file != null && w.isNotEmpty()) { counts[w] = (counts[w] ?: 0) + 1; dirty = true } }

    fun saveIfDirty() {
        val f = file ?: return
        if (!dirty) return
        val text = synchronized(this) { dirty = false; counts.entries.joinToString("") { "${it.key}\t${it.value}\n" } }
        try { f.writeText(text) } catch (_: Exception) {}
    }
}
