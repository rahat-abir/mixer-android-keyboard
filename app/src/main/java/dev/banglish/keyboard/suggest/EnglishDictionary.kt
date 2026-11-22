package dev.banglish.keyboard.suggest

import java.io.InputStream

/**
 * ~110k English words with frequencies (0–255), held in parallel sorted arrays: binary search
 * for exact and prefix lookups, no per-word HashMap. Built once on the background thread.
 */
class EnglishDictionary private constructor(
    /** Display form ("I", "London", "don't"), sorted by [keys]. */
    val words: Array<String>,
    /** Lower-case form used for searching. */
    val keys: Array<String>,
    val freq: IntArray,
    val offensive: BooleanArray,
    val shortcuts: Map<String, String>,
    val bigrams: Map<String, List<String>>,
) : dev.banglish.keyboard.glide.GlideLexicon {
    val size get() = words.size
    override fun path(i: Int) = keys[i]
    override fun word(i: Int) = words[i]
    override fun freq(i: Int) = freq[i]

    /** Index of the exact (case-insensitive) word, or -1. */
    fun indexOf(lower: String): Int {
        var lo = 0; var hi = keys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = keys[mid].compareTo(lower)
            if (c == 0) return mid
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    /** First index whose key is >= [prefix]. */
    fun lowerBound(prefix: String): Int {
        var lo = 0; var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Indices of words starting with [c], most frequent first (for glide typing). */
    override val byFirstLetter: Array<IntArray> by lazy {
        Array(26) { li ->
            val ch = 'a' + li
            val start = lowerBound(ch.toString())
            var end = start
            while (end < keys.size && keys[end].isNotEmpty() && keys[end][0] == ch) end++
            (start until end).filter { !offensive[it] && keys[it].length >= 2 }
                .sortedByDescending { freq[it] }
                .take(9000)
                .toIntArray()
        }
    }

    companion object {
        private val CONTRACTIONS = listOf(
            "I'm", "I've", "I'd", "I'll", "don't", "can't", "won't", "didn't", "doesn't", "isn't", "wasn't",
            "that's", "what's", "you're", "they're", "couldn't", "wouldn't", "shouldn't", "haven't", "hasn't",
            "aren't", "weren't", "let's", "there's", "here's", "who's", "we've", "you've", "they've", "you'll",
            "they'll", "you'd", "they'd", "ain't", "where's", "how's", "mustn't", "needn't",
        )

        fun load(words: InputStream, shortcuts: InputStream, bigrams: InputStream): EnglishDictionary {
            val w = ArrayList<String>(120_000)
            val f = ArrayList<Int>(120_000)
            val off = ArrayList<Boolean>(120_000)
            words.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t1 = line.indexOf('\t'); if (t1 <= 0) continue
                    val t2 = line.indexOf('\t', t1 + 1)
                    w += line.substring(0, t1)
                    f += (if (t2 < 0) line.substring(t1 + 1) else line.substring(t1 + 1, t2)).toIntOrNull() ?: 0
                    off += t2 > 0
                }
            }
            val lower = Array(w.size) { w[it].lowercase().let { l -> if (l == w[it]) w[it] else l } }
            // Sort by key; for duplicate keys ("us"/"US") keep the most frequent first.
            val order = (0 until w.size).sortedWith(compareBy<Int> { lower[it] }.thenByDescending { f[it] })
            val dedup = ArrayList<Int>(order.size)
            for (i in order) if (dedup.isEmpty() || lower[dedup.last()] != lower[i]) dedup += i
            val sc = HashMap<String, String>()
            shortcuts.bufferedReader().useLines { lines ->
                for (line in lines) { val t = line.indexOf('\t'); if (t > 0) sc[line.substring(0, t)] = line.substring(t + 1) }
            }
            sc["i"] = "I"
            for (c in CONTRACTIONS) sc.putIfAbsent(c.lowercase().replace("'", ""), c)
            val bg = HashMap<String, List<String>>()
            bigrams.bufferedReader().useLines { lines ->
                for (line in lines) { val t = line.indexOf('\t'); if (t > 0) bg[line.substring(0, t).lowercase()] = line.substring(t + 1).split(' ') }
            }
            return EnglishDictionary(
                Array(dedup.size) { w[dedup[it]] },
                Array(dedup.size) { lower[dedup[it]] },
                IntArray(dedup.size) { f[dedup[it]] },
                BooleanArray(dedup.size) { off[dedup[it]] },
                sc, bg,
            )
        }
    }
}
