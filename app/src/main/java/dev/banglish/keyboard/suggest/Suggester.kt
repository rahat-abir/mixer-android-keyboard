package dev.banglish.keyboard.suggest

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/** What the strip should show for the word being typed, and what space should commit. */
class Suggestions(
    val words: List<String>,
    /** Index in [words] that space/punctuation will commit; -1 = commit what was typed. */
    val autoIndex: Int,
)

/**
 * English completion + typo correction. Correction cost is a Damerau–Levenshtein distance where
 * swapping in a neighbouring key is cheaper than a far one, so "teh"→"the", "hwllo"→"hello".
 */
class Suggester(private val dict: EnglishDictionary, private val user: UserDictionary) {

    private class Cand(val word: String, val score: Double, val distance: Double)

    fun suggest(typed: String, allowAutocorrect: Boolean): Suggestions {
        if (typed.isEmpty()) return Suggestions(emptyList(), -1)
        val lower = typed.lowercase()
        val exact = dict.indexOf(lower)
        val userCount = user.count(lower)
        val known = exact >= 0 || userCount >= UserDictionary.KNOWN
        val cands = HashMap<String, Cand>()
        fun add(w: String, score: Double, dist: Double) {
            val k = w.lowercase()
            if (k == lower) return
            val old = cands[k]
            if (old == null || old.score < score) cands[k] = Cand(w, score, dist)
        }

        dict.shortcuts[lower]?.let { add(it, 100.0, 0.01) }

        // Completions: words that start with what was typed.
        val start = dict.lowerBound(lower)
        var i = start
        val best = ArrayList<Int>()
        while (i < dict.size && dict.keys[i].startsWith(lower)) {
            if (!dict.offensive[i] && dict.keys[i] != lower) insertTop(best, i, 6)
            i++
        }
        for (idx in best) add(dict.words[idx], freqScore(dict.freq[idx]) - 0.6 * (dict.keys[idx].length - lower.length).coerceAtMost(6) / 6.0, 0.0)
        val learned = ArrayList<Pair<String, Int>>()
        user.withPrefix(lower, learned)
        for ((w, c) in learned) if (c >= UserDictionary.KNOWN) add(w, 4.0 + min(c, 20) * 0.1, 0.0)

        // Corrections: only when the typed word isn't a known word.
        if (!known && lower.length >= 2) {
            val first = lower[0]
            val firsts = (NEIGHBORS[first] ?: "") + first
            for (fc in firsts) {
                var j = dict.lowerBound(fc.toString())
                while (j < dict.size && dict.keys[j].isNotEmpty() && dict.keys[j][0] == fc) {
                    val key = dict.keys[j]
                    if (!dict.offensive[j] && abs(key.length - lower.length) <= 2) {
                        val maxD = if (lower.length <= 4) 1.0 else 2.0
                        val d = distance(lower, key, maxD + 0.01)
                        if (d <= maxD) add(dict.words[j], freqScore(dict.freq[j]) - d * 2.2 - (if (fc != first) 0.6 else 0.0), d)
                    }
                    j++
                }
            }
        }

        val sorted = cands.values.sortedByDescending { it.score }
        val out = ArrayList<String>(3)
        val case = caseOf(typed)
        val top = sorted.take(2).map { applyCase(it.word, case) }
        val shortcut = dict.shortcuts[lower]
        // Decide what space will commit.
        // Space means the word is finished, so autocorrect only picks among corrections
        // (distance > 0), never among longer completions ("teh" → "the", not "Tehran").
        val bestCand = sorted.firstOrNull { it.distance > 0 }
        val autocorrect = allowAutocorrect && !known && bestCand != null &&
            (bestCand.distance <= 1.0 || lower.length >= 5)
        val forcedShortcut = allowAutocorrect && shortcut != null && lower in ALWAYS_SHORTCUT
        return if (autocorrect || forcedShortcut) {
            val pick = if (forcedShortcut) shortcut!! else applyCase(bestCand!!.word, case)
            out += typed; out += pick
            sorted.map { applyCase(it.word, case) }.firstOrNull { it != pick }?.let { out += it }
            Suggestions(out, 1)
        } else {
            // Typed word in the middle (bold), completions either side.
            if (top.isNotEmpty()) out += top[0]
            out += typed
            if (top.size > 1) out += top[1]
            Suggestions(out, out.indexOf(typed))
        }
    }

    /** Next-word predictions after a committed word. */
    fun predict(prev: String): List<String> = dict.bigrams[prev.lowercase()]?.take(3) ?: emptyList()

    fun isKnown(word: String): Boolean {
        val l = word.lowercase()
        return dict.indexOf(l) >= 0 || user.count(l) >= UserDictionary.KNOWN
    }

    private fun insertTop(list: ArrayList<Int>, idx: Int, k: Int) {
        val f = dict.freq[idx]
        var pos = list.size
        while (pos > 0 && dict.freq[list[pos - 1]] < f) pos--
        if (pos < k) { list.add(pos, idx); if (list.size > k) list.removeAt(list.size - 1) }
    }

    private fun freqScore(f: Int) = ln(f + 2.0)

    companion object {
        private val ALWAYS_SHORTCUT = setOf("i", "im", "ive", "id", "dont", "cant", "wont", "didnt", "doesnt", "isnt", "wasnt", "thats", "whats", "youre", "theyre", "couldnt", "wouldnt", "shouldnt", "havent", "hasnt", "arent", "werent")

        private val ROWS = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val OFFS = floatArrayOf(0f, 0.5f, 1.5f)
        private val POS = HashMap<Char, Pair<Float, Float>>().apply {
            ROWS.forEachIndexed { r, row -> row.forEachIndexed { i, c -> put(c, (i + OFFS[r]) to r.toFloat()) } }
        }
        val NEIGHBORS: Map<Char, String> = POS.keys.associateWith { c ->
            val (x, y) = POS[c]!!
            POS.filter { (o, p) -> o != c && abs(p.first - x) <= 1.0f && abs(p.second - y) <= 1f }.keys.joinToString("")
        }

        private fun subCost(a: Char, b: Char): Double = when {
            a == b -> 0.0
            NEIGHBORS[a]?.indexOf(b) ?: -1 >= 0 -> 0.5
            else -> 1.0
        }

        /** Weighted Damerau–Levenshtein with early exit once every cell exceeds [limit]. */
        fun distance(a: String, b: String, limit: Double): Double {
            // Apostrophes are free: "dont" vs "don't".
            val s = a.replace("'", ""); val t = b.replace("'", "")
            val n = s.length; val m = t.length
            var prev2 = DoubleArray(m + 1)
            var prev = DoubleArray(m + 1) { it.toDouble() }
            var cur = DoubleArray(m + 1)
            for (i in 1..n) {
                cur[0] = i.toDouble()
                var rowMin = cur[0]
                for (j in 1..m) {
                    var v = min(min(prev[j] + 1.0, cur[j - 1] + 1.0), prev[j - 1] + subCost(s[i - 1], t[j - 1]))
                    if (i > 1 && j > 1 && s[i - 1] == t[j - 2] && s[i - 2] == t[j - 1]) v = min(v, prev2[j - 2] + 0.6)
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > limit) return Double.MAX_VALUE
                val tmp = prev2; prev2 = prev; prev = cur; cur = tmp
            }
            return prev[m]
        }

        fun caseOf(s: String): Int = when {
            s.length > 1 && s.all { !it.isLetter() || it.isUpperCase() } -> 2
            s.isNotEmpty() && s[0].isUpperCase() -> 1
            else -> 0
        }

        fun applyCase(w: String, case: Int): String = when (case) {
            2 -> w.uppercase()
            1 -> w.replaceFirstChar { it.uppercaseChar() }
            else -> w
        }
    }
}
