package dev.banglish.keyboard.bangla

import org.json.JSONObject

/**
 * Port of OmicronLab's Avro Phonetic rule engine (jsAvroPhonetic / ibus-avro, MPL-2.0).
 * The same engine runs two rule sets:
 *  - phonetic.json: Banglish → Bangla text ("ami" → "আমি");
 *  - regex.json: Banglish → a regular expression that matches every dictionary spelling the
 *    input could mean, used for word suggestions. In that mode each replacement is followed by
 *    an optional-modifier group, exactly like avroregexlib.js.
 *
 * Behaviour mirrors the JavaScript, including its quirks (e.g. isExact's `end < length`).
 */
class AvroPhonetic(json: String, private val regexMode: Boolean) {

    private class Match(val suffix: Boolean, val scope: Int, val negative: Boolean, val value: String)
    private class Rule(val matches: Array<Match>, val replace: String)
    private class Pattern(val find: String, val replace: String, val rules: Array<Rule>)

    private val patterns: Array<Pattern>
    private val vowel: String
    private val consonant: String
    private val caseSensitive: String
    private val ignore: String
    /** Patterns bucketed by first character for speed (order within a bucket is preserved). */
    private val byFirst = HashMap<Char, Array<Pattern>>()

    init {
        val o = JSONObject(json)
        val arr = o.getJSONArray("patterns")
        patterns = Array(arr.length()) { i ->
            val p = arr.getJSONObject(i)
            val rulesArr = p.optJSONArray("rules")
            val rules = if (rulesArr == null) emptyArray() else Array(rulesArr.length()) { r ->
                val ro = rulesArr.getJSONObject(r)
                val ms = ro.getJSONArray("matches")
                Rule(Array(ms.length()) { m ->
                    val mo = ms.getJSONObject(m)
                    var scope = mo.getString("scope")
                    val neg = scope.startsWith("!")
                    if (neg) scope = scope.substring(1)
                    Match(
                        mo.getString("type") == "suffix",
                        when (scope) { "punctuation" -> PUNCT; "vowel" -> VOWEL; "consonant" -> CONSONANT; else -> EXACT },
                        neg,
                        mo.optString("value", ""),
                    )
                }, ro.getString("replace"))
            }
            Pattern(p.getString("find"), p.getString("replace"), rules)
        }
        vowel = o.getString("vowel")
        consonant = o.getString("consonant")
        caseSensitive = o.optString("casesensitive", "")
        ignore = o.optString("ignore", "")
        val groups = LinkedHashMap<Char, MutableList<Pattern>>()
        for (p in patterns) groups.getOrPut(p.find[0]) { ArrayList() } += p
        for ((c, l) in groups) byFirst[c] = l.toTypedArray()
    }

    private fun isVowel(c: Char) = vowel.indexOf(c.lowercaseChar()) >= 0
    private fun isConsonant(c: Char) = consonant.indexOf(c.lowercaseChar()) >= 0
    private fun isPunctuation(c: Char) = !(isVowel(c) || isConsonant(c))

    fun fixString(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            if (regexMode) {
                if (ignore.indexOf(c.lowercaseChar()) < 0) sb.append(c.lowercaseChar())
            } else {
                sb.append(if (caseSensitive.indexOf(c.lowercaseChar()) >= 0) c else c.lowercaseChar())
            }
        }
        return sb.toString()
    }

    fun parse(input: String): String {
        val fixed = fixString(input)
        val out = StringBuilder()
        val len = fixed.length
        var cur = 0
        while (cur < len) {
            val start = cur
            var matched = false
            val bucket = byFirst[fixed[cur]]
            if (bucket != null) {
                for (pattern in bucket) {
                    val end = cur + pattern.find.length
                    if (end > len || !fixed.regionMatches(start, pattern.find, 0, pattern.find.length)) continue
                    val prev = start - 1
                    var replacement: String? = null
                    for (rule in pattern.rules) {
                        var replace = true
                        for (m in rule.matches) {
                            val chk = if (m.suffix) end else prev
                            val ok = when (m.scope) {
                                PUNCT -> {
                                    val x = (chk < 0 && !m.suffix) || (chk >= len && m.suffix) ||
                                        (chk in 0 until len && isPunctuation(fixed[chk]))
                                    x != m.negative
                                }
                                VOWEL -> {
                                    val x = ((chk >= 0 && !m.suffix) || (chk < len && m.suffix)) &&
                                        chk in 0 until len && isVowel(fixed[chk])
                                    x != m.negative
                                }
                                CONSONANT -> {
                                    val x = ((chk >= 0 && !m.suffix) || (chk < len && m.suffix)) &&
                                        chk in 0 until len && isConsonant(fixed[chk])
                                    x != m.negative
                                }
                                else -> {
                                    val s: Int; val e: Int
                                    if (m.suffix) { s = end; e = end + m.value.length } else { s = start - m.value.length; e = start }
                                    isExact(m.value, fixed, s, e, m.negative)
                                }
                            }
                            if (!ok) { replace = false; break }
                        }
                        if (replace) { replacement = rule.replace; break }
                    }
                    out.append(replacement ?: pattern.replace)
                    if (regexMode) out.append(REGEX_TAIL)
                    cur = end
                    matched = true
                    break
                }
            }
            if (!matched) {
                out.append(fixed[cur])
                cur++
            }
        }
        return out.toString()
    }

    // jsAvroPhonetic: (start >= 0 && end < length && substring == needle) XOR not
    private fun isExact(needle: String, hay: String, start: Int, end: Int, not: Boolean): Boolean {
        val x = start >= 0 && end < hay.length && hay.regionMatches(start, needle, 0, needle.length) && end - start == needle.length
        return x != not
    }

    companion object {
        private const val PUNCT = 0
        private const val VOWEL = 1
        private const val CONSONANT = 2
        private const val EXACT = 3
        private const val REGEX_TAIL = "(্[যবম])?(্?)([ঃঁ]?)"
    }
}
