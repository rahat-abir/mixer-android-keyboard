package dev.banglish.keyboard.glide

import android.content.SharedPreferences
import java.io.File

/**
 * What happened after each glide, so the word picker can be measured and improved.
 *
 *  - Counters (forever, a few bytes): how often the default pick was kept, how often the user
 *    chose the 2nd / 3rd / a later suggestion, and how often they deleted the word.
 *  - A rolling log of the last [MAX_ENTRIES] glides (~50 KB): the keys swiped, the top
 *    candidates with their score features, and the outcome. Enough to replay and re-tune the
 *    ranking offline. Stays on the phone; Export writes a copy you can pull over USB.
 *  - Online tuning: when the user picks a word other than the default, [GlideTuner] nudges the
 *    weights so that word would have won.
 */
class GlideLog(private val file: File?, private val prefs: SharedPreferences) {

    /** One glide waiting for its outcome. */
    class Pending(
        val lang: String,          // "en" | "bn"
        val keys: String,          // keys the finger passed over, e.g. "ksdemoin"
        val candidates: List<GlideClassifier.Result>,
    ) {
        var chosen = 0
    }

    private val lines = ArrayDeque<String>()
    private var loaded = false
    @Volatile private var dirty = false

    private fun load() {
        if (loaded) return
        loaded = true
        try { file?.takeIf { it.exists() }?.forEachLine { if (it.isNotBlank()) lines.addLast(it) } } catch (_: Exception) {}
        while (lines.size > MAX_ENTRIES) lines.removeFirst()
    }

    /** Outcome codes: KEPT (default stayed), PICKED (user chose [Pending.chosen]), DELETED. */
    @Synchronized
    fun finish(p: Pending, outcome: Int, recordLine: Boolean) {
        val lang = p.lang
        val e = prefs.edit()
        fun inc(k: String) = e.putInt("glide_${lang}_$k", prefs.getInt("glide_${lang}_$k", 0) + 1)
        inc("total")
        when {
            outcome == DELETED -> inc("deleted")
            p.chosen == 0 -> inc("top1")
            p.chosen == 1 -> inc("top2")
            p.chosen == 2 -> inc("top3")
            else -> inc("other")
        }
        e.apply()
        if (!recordLine || file == null) return
        load()
        val sb = StringBuilder()
        sb.append(System.currentTimeMillis() / 1000).append('\t').append(lang).append('\t').append(p.keys).append('\t')
        sb.append(if (outcome == DELETED) "del" else p.chosen.toString()).append('\t')
        p.candidates.take(5).forEachIndexed { i, c ->
            if (i > 0) sb.append('|')
            sb.append(c.word).append(':').append(c.path)
            for (f in c.features) sb.append(',').append("%.3f".format(f))
        }
        lines.addLast(sb.toString())
        while (lines.size > MAX_ENTRIES) lines.removeFirst()
        dirty = true
    }

    fun stats(lang: String): IntArray =
        listOf("total", "top1", "top2", "top3", "other", "deleted").map { prefs.getInt("glide_${lang}_$it", 0) }.toIntArray()

    @Synchronized
    fun clear() {
        lines.clear(); loaded = true; dirty = true
        val e = prefs.edit()
        for (l in listOf("en", "bn")) for (k in listOf("total", "top1", "top2", "top3", "other", "deleted")) e.remove("glide_${l}_$k")
        e.apply()
    }

    fun saveIfDirty() {
        val f = file ?: return
        if (!dirty) return
        val text = synchronized(this) { dirty = false; lines.joinToString("\n", postfix = "\n") }
        try { f.writeText(text) } catch (_: Exception) {}
    }

    /** Header line describing the columns, for exports. */
    fun exportText(): String = synchronized(this) {
        load()
        "# time\tlang\tswiped_keys\tchosen(0=default kept, n=picked nth, del=deleted)\tcandidates word:path,loc,shape,lnfreq,lnuses,letters|...\n" +
            lines.joinToString("\n", postfix = "\n")
    }

    companion object {
        const val KEPT = 0
        const val PICKED = 1
        const val DELETED = 2
        const val MAX_ENTRIES = 500
    }
}

/**
 * Online pairwise tuning. If the user preferred candidate c over the default t, move the weights
 * a small step so cost(c) < cost(t) next time (a perceptron update on the feature difference),
 * clamped to a sane range around the defaults so one odd pick can't wreck the ranking.
 */
class GlideTuner(private val prefs: SharedPreferences) {
    private val cache = HashMap<String, GlideWeights>()

    @Synchronized
    fun weights(lang: String): GlideWeights = cache.getOrPut(lang) {
        val saved = prefs.getString("glide_w_$lang", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        GlideWeights(if (saved != null && saved.size == GlideWeights.FEATURES) saved.toFloatArray() else GlideWeights.DEFAULT.copyOf())
    }

    @Synchronized
    fun learn(lang: String, preferred: GlideClassifier.Result, over: GlideClassifier.Result) {
        val gw = weights(lang)
        val w = gw.w
        // cost = Σ sign_i · w_i · f_i with signs (+,+,−,−,+). Want cost(pref) − cost(over) < −margin.
        val signs = floatArrayOf(1f, 1f, -1f, -1f, 1f)
        val margin = 0.05f
        val diff = gw.cost(preferred.features) - gw.cost(over.features)
        if (diff < -margin) return
        val rate = 0.04f
        for (i in w.indices) {
            val d = signs[i] * (preferred.features[i] - over.features[i])
            w[i] = (w[i] - rate * d * GlideWeights.DEFAULT[i]).coerceIn(GlideWeights.DEFAULT[i] * 0.3f, GlideWeights.DEFAULT[i] * 3f)
        }
        prefs.edit().putString("glide_w_$lang", w.joinToString(",")).apply()
    }

    @Synchronized
    fun reset() {
        cache.clear()
        prefs.edit().remove("glide_w_en").remove("glide_w_bn").apply()
    }
}
