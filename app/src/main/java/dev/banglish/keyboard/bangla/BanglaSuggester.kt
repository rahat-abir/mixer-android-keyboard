package dev.banglish.keyboard.bangla

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.regex.Pattern
import kotlin.math.min

class BanglaSuggestions(val words: List<String>, val selected: Int)

/**
 * Avro-style Bangla suggestions, ported from ibus-avro's suggestionbuilder.js + dbsearch.js
 * (MPL-2.0): autocorrect entry, dictionary words matched by the Avro regex (with suffix
 * support), then the classic phonetic conversion. Remembers which candidate you picked for a
 * given Banglish word, like Avro does.
 */
class BanglaSuggester(
    private val phonetic: AvroPhonetic,
    private val regex: AvroPhonetic,
    private val tables: Map<String, Array<String>>,
    private val suffixes: Map<String, String>,
    private val autocorrect: Map<String, String>,
    private val selectionsFile: File?,
) {
    private val selections = HashMap<String, String>()
    private val phoneticCache = HashMap<String, List<String>>()
    private val tempCache = HashMap<String, Pair<String, String>>() // full -> (base, eng)
    @Volatile private var dirty = false

    init {
        try {
            selectionsFile?.takeIf { it.exists() }?.forEachLine { l ->
                val t = l.indexOf('\t'); if (t > 0) selections[l.substring(0, t)] = l.substring(t + 1)
            }
        } catch (_: Exception) {}
    }

    fun convert(banglish: String): String = phonetic.parse(banglish)

    @Synchronized
    fun suggest(word: String): BanglaSuggestions {
        val m = PADDING.matcher(word)
        val (begin0, middle, end0) = if (m.matches()) Triple(m.group(1) ?: "", m.group(2) ?: "", m.group(3) ?: "") else Triple("", word, "")
        val begin = phonetic.parse(begin0)
        val end = phonetic.parse(end0)
        val conv = phonetic.parse(middle)
        if (middle.isEmpty()) return BanglaSuggestions(listOf(begin + conv + end), 0)

        val key = middle.lowercase()
        val dictWords = ArrayList(phoneticCache[key] ?: search(key))

        // Autocorrect
        var corrected: String? = null
        var exact = false
        val ac = autocorrect[word]
        if (ac != null) {
            if (ac == word) { corrected = word; exact = true } else corrected = phonetic.parse(ac)
        } else {
            autocorrect[phonetic.fixString(middle)]?.let { corrected = phonetic.parse(it) }
        }

        val words = ArrayList<String>()
        corrected?.let { words += it; if (!exact) dictWords += it }
        if (phoneticCache[key] == null && dictWords.isNotEmpty()) phoneticCache[key] = ArrayList(dictWords)
        val withSuffix = addSuffix(key)
        val sorted = withSuffix.sortedBy { levenshtein(conv, it) }
        for (w in sorted) if (w !in words) words += w
        if (conv !in words) words += conv

        val sel = previousSelection(middle, words)
        val padded = words.map { w -> if (exact && w == corrected) w else begin + w + end }
        return BanglaSuggestions(padded, sel)
    }

    /** Called when the user commits a candidate (tap, or space with the default selection). */
    @Synchronized
    fun commitSelection(word: String, candidate: String) {
        val m = PADDING.matcher(word)
        val middle = if (m.matches()) m.group(2) ?: word else word
        if (middle.isEmpty()) return
        // Store the bare candidate (padding stripped).
        val begin = phonetic.parse(if (m.matches()) m.group(1) ?: "" else "")
        val end = phonetic.parse(if (m.matches()) m.group(3) ?: "" else "")
        var bare = candidate
        if (begin.isNotEmpty() && bare.startsWith(begin)) bare = bare.substring(begin.length)
        if (end.isNotEmpty() && bare.endsWith(end)) bare = bare.substring(0, bare.length - end.length)
        if (selections[middle] != bare) { selections[middle] = bare; dirty = true }
        tempCache[bare]?.let { (base, eng) -> if (selections[eng] == null) { selections[eng] = base; dirty = true } }
    }

    fun saveIfDirty() {
        val f = selectionsFile ?: return
        if (!dirty) return
        val text = synchronized(this) { dirty = false; selections.entries.joinToString("") { "${it.key}\t${it.value}\n" } }
        try { f.writeText(text) } catch (_: Exception) {}
    }

    private fun search(enText: String): List<String> {
        val lmc = enText.firstOrNull() ?: return emptyList()
        val list = TABLES[lmc] ?: return emptyList()
        val pattern = try { Pattern.compile(regex.parse(enText)) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<String>()
        val matcher = pattern.matcher("")
        for (t in list) {
            val arr = tables["w_$t"] ?: continue
            for (w in arr) if (matcher.reset(w).matches()) out += w
        }
        // Not in avro: a few completions (longer words that start like this) for fewer keystrokes.
        if (out.size < 3 && enText.length >= 3) {
            val comps = ArrayList<String>()
            val maxLen = phonetic.parse(enText).length + 3
            outer@ for (t in list) {
                val arr = tables["w_$t"] ?: continue
                for (w in arr) {
                    if (w.length > maxLen || w in out) continue
                    if (matcher.reset(w).lookingAt()) { comps += w; if (comps.size >= 24) break@outer }
                }
            }
            out += comps.sortedBy { it.length }.take(5)
        }
        return out
    }

    private fun addSuffix(word: String): List<String> {
        val r = ArrayList(phoneticCache[word] ?: emptyList())
        tempCache.clear()
        val len = word.length
        if (len >= 2) {
            for (j in 1..len) {
                val testSuffix = word.substring(j)
                val suffix = suffixes[testSuffix] ?: continue
                val key = word.substring(0, len - testSuffix.length)
                // Like Avro, rely on the cache filled while the user typed the shorter prefix.
                val base = phoneticCache[key] ?: continue
                for (item in base) {
                    val right = item.last()
                    val full = when {
                        isVowel(right) && isKar(suffix[0]) -> item + "য়" + suffix
                        right == 'ৎ' -> item.dropLast(1) + "ত" + suffix
                        right == 'ং' -> item.dropLast(1) + "ঙ" + suffix
                        else -> item + suffix
                    }
                    r += full
                    if (right != 'ং' && tempCache[full] == null) tempCache[full] = item to key
                }
            }
        }
        return r
    }

    private fun previousSelection(word: String, words: List<String>): Int {
        var selected = selections[word]
        if (selected == null && word.length >= 2) {
            for (j in 1 until word.length) {
                val testSuffix = word.substring(word.length - j).lowercase()
                val suffix = suffixes[testSuffix] ?: continue
                val key = word.substring(0, word.length - testSuffix.length)
                val kw = selections[key] ?: continue
                val right = kw.last()
                selected = when {
                    isVowel(right) && isKar(suffix[0]) -> kw + "য়" + suffix
                    right == 'ৎ' -> kw.dropLast(1) + "ত" + suffix
                    right == 'ং' -> kw.dropLast(1) + "ঙ" + suffix
                    else -> kw + suffix
                }
                selections[word] = selected
                break
            }
        }
        val i = if (selected == null) -1 else words.indexOf(selected)
        return if (i < 0) 0 else i
    }

    companion object {
        private const val PUNC = "(?::`|\\.`|[\\-\\]~!@#%&*()_=+\\[{}'\";<>/?|.,])"
        val PADDING: Pattern = Pattern.compile("(^$PUNC*?(?=(?:,{2,}))|^$PUNC*)(.*?(?:,,)*)($PUNC*$)", Pattern.DOTALL)

        private val TABLES = mapOf(
            'a' to listOf("a", "aa", "e", "oi", "o", "nya", "y"), 'b' to listOf("b", "bh"),
            'c' to listOf("c", "ch", "k"), 'd' to listOf("d", "dh", "dd", "ddh"),
            'e' to listOf("i", "ii", "e", "y"), 'f' to listOf("ph"), 'g' to listOf("g", "gh", "j"),
            'h' to listOf("h"), 'i' to listOf("i", "ii", "y"), 'j' to listOf("j", "jh", "z"),
            'k' to listOf("k", "kh"), 'l' to listOf("l"), 'm' to listOf("h", "m"),
            'n' to listOf("n", "nya", "nga", "nn"), 'o' to listOf("a", "u", "uu", "oi", "o", "ou", "y"),
            'p' to listOf("p", "ph"), 'q' to listOf("k"), 'r' to listOf("rri", "h", "r", "rr", "rrh"),
            's' to listOf("s", "sh", "ss"), 't' to listOf("t", "th", "tt", "tth", "khandatta"),
            'u' to listOf("u", "uu", "y"), 'v' to listOf("bh"), 'w' to listOf("o"), 'x' to listOf("e", "k"),
            'y' to listOf("i", "y"), 'z' to listOf("h", "j", "jh", "z"),
        )

        private fun isKar(c: Char) = c in "ািীুূৃেৈোৌৄ"
        private fun isVowel(c: Char) = c in "অআইঈউঊঋএঐওঔঌৡািীুূৃেৈোৌ"

        fun levenshtein(a: String, b: String): Int {
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + cost)
            }
            return d[a.length][b.length]
        }

        fun loadTables(input: InputStream): Map<String, Array<String>> {
            val out = HashMap<String, Array<String>>()
            var name: String? = null
            var cur = ArrayList<String>()
            input.bufferedReader().useLines { lines ->
                for (l in lines) {
                    if (l.startsWith("#")) {
                        name?.let { out[it] = cur.toTypedArray() }
                        name = l.substring(1); cur = ArrayList()
                    } else if (l.isNotEmpty()) cur += l
                }
            }
            name?.let { out[it] = cur.toTypedArray() }
            return out
        }

        fun loadMap(json: String): Map<String, String> {
            val o = JSONObject(json)
            val m = HashMap<String, String>(o.length() * 2)
            for (k in o.keys()) m[k] = o.getString(k)
            return m
        }
    }
}
