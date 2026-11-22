package dev.banglish.keyboard.glide

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * Glide (swipe) typing: matches the finger path against the ideal path of each candidate word —
 * the polyline through its letters' key centres — the shape-matching idea behind SHARK² and
 * FlorisBoard's statistical glide classifier (Apache-2.0).
 *
 * Steps: prune by start/end key, check the path passes near every letter in order, then rank
 * by location distance + normalised shape distance + word frequency.
 */
/** Anything glide can search: words with a lower-case a–z key path and a frequency (0–255). */
interface GlideLexicon {
    /** Entry indices whose path starts with 'a'+i, most frequent first. */
    val byFirstLetter: Array<IntArray>
    fun path(i: Int): String
    fun word(i: Int): String
    fun freq(i: Int): Int
}

/**
 * The ranking weights. cost = loc·w0 + shape·w1 − ln(freq+2)·w2 − ln(1+uses)·w3 + letters·w4.
 * Tuned on the phone from the user's own picks (see [dev.banglish.keyboard.glide.GlideTuner]).
 */
class GlideWeights(val w: FloatArray = DEFAULT.copyOf()) {
    fun cost(f: FloatArray): Float = w[0] * f[0] + w[1] * f[1] - w[2] * f[2] - w[3] * f[3] + w[4] * f[4]
    companion object {
        val DEFAULT = floatArrayOf(1.0f, 1.6f, 0.11f, 0.35f, 0.02f)
        const val FEATURES = 5
    }
}

class GlideClassifier(private val lex: GlideLexicon) {

    /** Key centre of each letter a–z in the current layout, and the letter key width. */
    class KeyMap(val cx: FloatArray, val cy: FloatArray, val keyW: Float)

    /** [features]: loc, shape, ln(freq+2), ln(1+uses), letters — kept for logging and tuning. */
    class Result(val word: String, val path: String, var cost: Float, val features: FloatArray)

    private val ux = FloatArray(N); private val uy = FloatArray(N)
    private val px = FloatArray(N); private val py = FloatArray(N)
    private val lx = FloatArray(32); private val ly = FloatArray(32)

    fun classify(
        xs: FloatArray, ys: FloatArray, n: Int, keys: KeyMap, limit: Int = 8,
        weights: GlideWeights = GlideWeights(), uses: (String) -> Int = { 0 },
    ): List<Result> {
        if (n < 2) return emptyList()
        val keyW = keys.keyW
        val userLen = resample(xs, ys, n, ux, uy)
        if (userLen < keyW * 0.5f) return emptyList()
        val sx = xs[0]; val sy = ys[0]; val ex = xs[n - 1]; val ey = ys[n - 1]
        val starts = nearLetters(keys, sx, sy, keyW * 1.1f)
        val ends = nearLetters(keys, ex, ey, keyW * 1.1f)
        val userShape = shapeNorm(ux, uy, keyW)
        val results = ArrayList<Result>()
        val raw = Raw(xs, ys, n)

        for (s in starts) {
            for (idx in lex.byFirstLetter[s]) {
                val path = lex.path(idx)
                val last = lastLetter(path)
                if (last < 0 || ends.indexOf(last) < 0) continue
                // Build the ideal path (letters only, consecutive duplicates merged: "hello" → h-e-l-o).
                var m = 0
                var prev = -1
                var ok = true
                for (ch in path) {
                    if (ch == '\'') continue
                    val li = ch - 'a'
                    if (li !in 0..25) { ok = false; break }
                    if (li == prev) continue
                    if (m == lx.size) { ok = false; break }
                    lx[m] = keys.cx[li]; ly[m] = keys.cy[li]; m++; prev = li
                }
                if (!ok || m < 1) continue
                var idealLen = 0f
                for (k in 1 until m) idealLen += hypot(lx[k] - lx[k - 1], ly[k] - ly[k - 1])
                val ratio = (userLen + keyW) / (idealLen + keyW)
                if (ratio < 0.55f || ratio > 1.9f) continue
                if (!passesLetters(raw, m, keyW * 0.95f)) continue
                resample(lx, ly, m, px, py)
                var loc = 0f
                for (k in 0 until N) loc += hypot(ux[k] - px[k], uy[k] - py[k])
                loc /= N * keyW
                val shape = shapeDistance(userShape, px, py, keyW)
                val word = lex.word(idx)
                val f = floatArrayOf(loc, shape, ln(lex.freq(idx) + 2f), ln(1f + uses(word)), m.toFloat())
                insert(results, Result(word, path, weights.cost(f), f), limit)
            }
        }
        return results
    }

    private class Raw(val xs: FloatArray, val ys: FloatArray, val n: Int)

    /** Every letter of the ideal path must come within [thr] of the finger path, in order. */
    private fun passesLetters(raw: Raw, m: Int, thr: Float): Boolean {
        var j = 0
        val t2 = thr * thr
        for (k in 0 until m) {
            var found = false
            while (j < raw.n) {
                val dx = raw.xs[j] - lx[k]; val dy = raw.ys[j] - ly[k]
                if (dx * dx + dy * dy <= t2) { found = true; break }
                j++
            }
            if (!found) return false
        }
        return true
    }

    private fun lastLetter(w: String): Int {
        for (i in w.length - 1 downTo 0) { val c = w[i]; if (c in 'a'..'z') return c - 'a' }
        return -1
    }

    private fun nearLetters(keys: KeyMap, x: Float, y: Float, r: Float): IntArray {
        val out = ArrayList<Pair<Int, Float>>()
        for (i in 0 until 26) {
            val d = hypot(keys.cx[i] - x, keys.cy[i] - y)
            if (d <= r) out += i to d
        }
        if (out.isEmpty()) { // always include the nearest
            var best = 0; var bd = Float.MAX_VALUE
            for (i in 0 until 26) { val d = hypot(keys.cx[i] - x, keys.cy[i] - y); if (d < bd) { bd = d; best = i } }
            return intArrayOf(best)
        }
        return out.sortedBy { it.second }.take(4).map { it.first }.toIntArray()
    }

    private class Shape(val x: FloatArray, val y: FloatArray)

    /** Centre on the centroid and scale so the larger side is 1 (tiny paths are not blown up). */
    private fun shapeNorm(x: FloatArray, y: FloatArray, keyW: Float): Shape {
        var mx = 0f; var my = 0f
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until N) {
            mx += x[i]; my += y[i]
            if (x[i] < minX) minX = x[i]; if (x[i] > maxX) maxX = x[i]
            if (y[i] < minY) minY = y[i]; if (y[i] > maxY) maxY = y[i]
        }
        mx /= N; my /= N
        val scale = max(max(maxX - minX, maxY - minY), keyW)
        return Shape(FloatArray(N) { (x[it] - mx) / scale }, FloatArray(N) { (y[it] - my) / scale })
    }

    private fun shapeDistance(u: Shape, x: FloatArray, y: FloatArray, keyW: Float): Float {
        val p = shapeNorm(x, y, keyW)
        var d = 0f
        for (i in 0 until N) d += hypot(u.x[i] - p.x[i], u.y[i] - p.y[i])
        return d / N
    }

    private fun insert(list: ArrayList<Result>, r: Result, limit: Int) {
        // One entry per word (Bangla words have several spellings): keep the best.
        val dup = list.indexOfFirst { it.word == r.word }
        if (dup >= 0) { if (list[dup].cost <= r.cost) return; list.removeAt(dup) }
        var pos = list.size
        while (pos > 0 && list[pos - 1].cost > r.cost) pos--
        if (pos < limit) {
            list.add(pos, r)
            if (list.size > limit) list.removeAt(list.size - 1)
        }
    }

    companion object {
        const val N = 64

        /** Resample a polyline to [N] equally spaced points; returns its length. */
        fun resample(xs: FloatArray, ys: FloatArray, n: Int, ox: FloatArray, oy: FloatArray): Float {
            var total = 0f
            for (i in 1 until n) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            if (n == 1 || total == 0f) {
                for (i in 0 until N) { ox[i] = xs[0]; oy[i] = ys[0] }
                return 0f
            }
            val step = total / (N - 1)
            ox[0] = xs[0]; oy[0] = ys[0]
            var out = 1
            var acc = 0f
            var px = xs[0]; var py = ys[0]
            var i = 1
            while (i < n && out < N) {
                val qx = xs[i]; val qy = ys[i]
                val d = hypot(qx - px, qy - py)
                if (acc + d >= step && d > 0f) {
                    val t = (step - acc) / d
                    val nx = px + t * (qx - px); val ny = py + t * (qy - py)
                    ox[out] = nx; oy[out] = ny; out++
                    px = nx; py = ny; acc = 0f
                } else {
                    acc += d; px = qx; py = qy; i++
                }
            }
            while (out < N) { ox[out] = xs[n - 1]; oy[out] = ys[n - 1]; out++ }
            return total
        }
    }
}
