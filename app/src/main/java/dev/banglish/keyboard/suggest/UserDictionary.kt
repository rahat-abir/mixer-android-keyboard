package dev.banglish.keyboard.suggest

import java.io.File

/**
 * Words the user actually typed and kept, with a use count. Stored as plain text on the phone
 * only (credential-protected storage, so it is never read before first unlock).
 * Thread-safe: touched from the main thread (learn) and the suggestion thread (lookup).
 */
class UserDictionary(private val file: File?) {
    private val counts = HashMap<String, Int>()      // key -> count
    private val display = HashMap<String, String>()  // key -> as typed
    @Volatile private var dirty = false

    init {
        try {
            file?.takeIf { it.exists() }?.forEachLine { line ->
                val t = line.lastIndexOf('\t')
                if (t > 0) {
                    val w = line.substring(0, t)
                    counts[w.lowercase()] = line.substring(t + 1).toIntOrNull() ?: 1
                    display[w.lowercase()] = w
                }
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun count(lower: String): Int = counts[lower] ?: 0
    @Synchronized fun display(lower: String): String? = display[lower]

    @Synchronized fun learn(word: String) {
        if (file == null || word.length < 2 || word.length > 40) return
        val k = word.lowercase()
        counts[k] = (counts[k] ?: 0) + 1
        if (display[k] == null || word != k) display[k] = word
        dirty = true
    }

    /** The user explicitly kept this spelling (tapped it, or undid an autocorrect). */
    @Synchronized fun learnNow(word: String) {
        learn(word)
        val k = word.lowercase()
        if ((counts[k] ?: 0) < KNOWN) counts[k] = KNOWN
    }

    @Synchronized fun forget(word: String) {
        val k = word.lowercase()
        if (counts.remove(k) != null) { display.remove(k); dirty = true }
    }

    /** Learned words starting with [prefix] (lower-case), with their counts. */
    @Synchronized fun withPrefix(prefix: String, out: MutableList<Pair<String, Int>>) {
        for ((k, c) in counts) if (k.startsWith(prefix) && k != prefix) out += (display[k] ?: k) to c
    }

    @Synchronized fun snapshotKeys(): List<String> = counts.keys.toList()

    fun saveIfDirty() {
        val f = file ?: return
        if (!dirty) return
        val text: String
        synchronized(this) {
            dirty = false
            text = buildString { for ((k, c) in counts) append(display[k] ?: k).append('\t').append(c).append('\n') }
        }
        try { f.writeText(text) } catch (_: Exception) {}
    }

    companion object {
        /** Uses before a typed word counts as "known" (suggested, never autocorrected away). */
        const val KNOWN = 2
    }
}
