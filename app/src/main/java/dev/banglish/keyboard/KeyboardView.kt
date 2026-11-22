package dev.banglish.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import dev.banglish.keyboard.layout.Codes
import dev.banglish.keyboard.layout.Key
import dev.banglish.keyboard.layout.Keyboard
import dev.banglish.keyboard.layout.Layouts
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The key grid. Draws straight onto a Canvas with precomputed geometry and tracks every
 * finger separately so fast two-thumb typing never drops or reorders letters.
 *
 * Rules it follows:
 *  - feedback (haptic, sound, preview) on finger DOWN; letters commit on UP, except that a
 *    second finger going down commits the first finger's key immediately (rollover);
 *  - each finger has its own long-press timer;
 *  - nothing allocates in onDraw.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKeyDown(key: Key)
        fun onKey(key: Key, text: String)
        /** Return true if the long-press did something (copy/paste, IME picker...). */
        fun onLongPress(key: Key): Boolean
        fun onCursorMove(steps: Int)
        /** Quick horizontal swipe on the space bar: -1 left, +1 right. */
        fun onSpaceSwipe(direction: Int)
        fun onDeleteWords(count: Int)
        fun onGlide(xs: FloatArray, ys: FloatArray, n: Int)
        fun onShiftLock()
        fun requestOverlayRedraw()
    }

    var keyboard: Keyboard? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var theme: Theme = Theme.LIGHT
        set(v) { field = v; invalidate() }
    var shiftState = 0 // 0 off, 1 shift, 2 caps lock
        set(v) { if (field != v) { field = v; invalidate() } }
    var spaceLabel = "English"
        set(v) { if (field != v) { field = v; invalidate() } }
    /** 0 enter, 1 go, 2 search, 3 send, 4 next, 5 done, 6 previous */
    var enterAction = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Visible height of a letter key, of a number-row key, and the gaps between keys (px). */
    var rowHeightPx = 0
    var numberRowHeightPx = 0
    var hGapPx = 0f
    var vGapPx = 0f
    /** Every page is stretched to at least this height so switching pages never jumps. */
    var minHeightPx = 0
    /** ln P(next letter) for a–z while a word is being typed; null = no bias. */
    var letterPrior: FloatArray? = null
    var adaptiveTouch = true
    var glideEnabled = true
    var showPreviews = true

    private val density = resources.displayMetrics.density
    private val radius = 6f * density
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val popupText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 5f * density
    }
    private val icons = Icons()
    private val trailPath = Path()
    private val handler = Handler(Looper.getMainLooper())

    private enum class Mode { NORMAL, CONSUMED, POPUP, GLIDE, CURSOR, DELETE_SWIPE }

    private inner class Pointer(val id: Int) {
        var key: Key? = null
        var downX = 0f; var downY = 0f
        var lastX = 0f
        var mode = Mode.NORMAL
        var committed = false
        var cursorAnchor = 0f
        var popupItems: List<String> = emptyList()
        var popupSel = 0
        var popupLeft = 0f
        val longPress = Runnable { onLongPressTimeout(this) }
        val repeat = Repeater(this)
    }

    /** Backspace auto-repeat that speeds up the longer it is held. */
    private inner class Repeater(val p: Pointer) : Runnable {
        var count = 0
        override fun run() {
            val k = p.key ?: return
            listener.onKey(k, "")
            count++
            handler.postDelayed(this, max(25L, 60L - count * 3L))
        }
    }

    private val pointers = SparseArray<Pointer>()

    // Glide capture (single pointer only).
    private var glideXs = FloatArray(512)
    private var glideYs = FloatArray(512)
    private var glideN = 0
    private var trailFadeAt = 0L

    init {
        isHapticFeedbackEnabled = true
    }

    private val pitch get() = (rowHeightPx + vGapPx).toInt()
    private val shortPitch get() = (numberRowHeightPx + vGapPx).toInt()

    private fun doLayout(kb: Keyboard, w: Int) = kb.layout(w, pitch, shortPitch, minHeightPx)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val kb = keyboard
        val natural = kb?.rows?.sumOf { if (it.short) shortPitch else pitch } ?: (4 * pitch)
        setMeasuredDimension(w, maxOf(natural, minHeightPx) + paddingTop + paddingBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        keyboard?.let { doLayout(it, w) }
    }

    fun relayout() {
        val kb = keyboard ?: return
        if (width > 0) doLayout(kb, width)
        requestLayout(); invalidate()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val kb = keyboard ?: return
        if (kb.width != width) doLayout(kb, width)
        val t = theme
        canvas.drawColor(t.background)
        val keyH = rowHeightPx.toFloat()
        textPaint.textSize = keyH * 0.5f
        hintPaint.textSize = keyH * 0.26f
        hintPaint.color = t.hintText
        for (k in kb.keys) {
            val pressed = isPressed(k)
            keyPaint.color = when {
                k.code == Codes.ENTER -> t.accent
                pressed -> t.keyPressed
                k.isFunction && k.code != Codes.SPACE -> t.functionKey
                else -> t.key
            }
            val hg = hGapPx / 2; val vg = vGapPx / 2
            canvas.drawRoundRect(k.x + hg, k.y + vg, k.x + k.w - hg, k.y + k.h - vg, radius, radius, keyPaint)
            drawKeyFace(canvas, k, t)
        }
    }

    private fun drawKeyFace(c: Canvas, k: Key, t: Theme) {
        val cx = k.cx; val cy = k.cy
        val s = min(k.h - vGapPx, k.w - hGapPx) * 0.5f
        val labelSmall = rowHeightPx * 0.34f
        val fg = if (k.code == Codes.ENTER) t.accentText else t.text
        icons.setColor(fg, 2f * density)
        when (k.code) {
            Codes.SHIFT -> icons.shift(c, cx, cy, s, shiftState)
            Codes.DELETE -> icons.delete(c, cx, cy, s)
            Codes.EMOJI -> icons.emoji(c, cx, cy, s)
            Codes.LANG -> icons.globe(c, cx, cy, s)
            Codes.ENTER -> when (enterAction) {
                2 -> icons.search(c, cx, cy, s)
                1, 3, 4, 5, 6 -> {
                    textPaint.color = fg
                    val old = textPaint.textSize
                    textPaint.textSize = labelSmall
                    textPaint.typeface = Typeface.DEFAULT_BOLD
                    c.drawText(ENTER_LABELS[enterAction], cx, baseline(cy, textPaint), textPaint)
                    textPaint.typeface = Typeface.DEFAULT
                    textPaint.textSize = old
                }
                else -> icons.enter(c, cx, cy, s)
            }
            Codes.SPACE -> {
                textPaint.color = t.hintText
                val old = textPaint.textSize
                textPaint.textSize = labelSmall
                c.drawText(spaceLabel, cx, baseline(cy, textPaint), textPaint)
                textPaint.textSize = old
            }
            else -> {
                textPaint.color = fg
                val label = if (k.isLetter && shiftState > 0) k.upper else k.label
                val old = textPaint.textSize
                if (label.length > 1) textPaint.textSize = labelSmall
                c.drawText(label, cx, baseline(cy, textPaint), textPaint)
                textPaint.textSize = old
                val hint = k.hint
                if (hint != null) c.drawText(hint, k.x + k.w - hGapPx / 2 - 4 * density, k.y + vGapPx / 2 + hintPaint.textSize, hintPaint)
            }
        }
    }

    private fun baseline(cy: Float, p: Paint) = cy - (p.descent() + p.ascent()) / 2f

    private fun isPressed(k: Key): Boolean {
        for (i in 0 until pointers.size()) {
            val p = pointers.valueAt(i)
            if (p.key === k && p.mode != Mode.GLIDE) return true
        }
        return false
    }

    /**
     * Drawn by the overlay above this view (so previews of the top row can rise over the
     * suggestion strip). [dy] = this view's top in overlay coordinates.
     */
    fun drawOverlay(c: Canvas, dy: Float) {
        val t = theme
        // Glide trail.
        if (glideN > 1) {
            val now = SystemClock.uptimeMillis()
            val alpha = if (trailFadeAt == 0L) 255 else (255 * (1f - (now - trailFadeAt) / 200f)).toInt()
            if (alpha > 0) {
                trailPath.reset()
                trailPath.moveTo(glideXs[0], glideYs[0] + dy)
                for (i in 1 until glideN) trailPath.lineTo(glideXs[i], glideYs[i] + dy)
                trailPaint.color = t.trail
                trailPaint.alpha = min(alpha, 0xCC)
                c.drawPath(trailPath, trailPaint)
                if (trailFadeAt != 0L) listener.requestOverlayRedraw()
            } else glideN = 0
        }
        for (i in 0 until pointers.size()) {
            val p = pointers.valueAt(i)
            val k = p.key ?: continue
            when (p.mode) {
                Mode.POPUP -> drawPopup(c, p, k, dy, t)
                Mode.NORMAL -> if (showPreviews && !k.isFunction && !p.committed) drawPreview(c, k, dy, t)
                else -> {}
            }
        }
    }

    private fun drawPreview(c: Canvas, k: Key, dy: Float, t: Theme) {
        val w = k.w * 1.15f; val h = k.h * 1.25f
        val left = (k.cx - w / 2).coerceIn(0f, width - w)
        val bottom = k.y + dy - vGapPx / 2
        keyPaint.color = t.popup
        keyPaint.setShadowLayer(4f * density, 0f, 1f * density, 0x40000000)
        c.drawRoundRect(left, bottom - h, left + w, bottom, radius, radius, keyPaint)
        keyPaint.clearShadowLayer()
        popupText.color = t.text
        popupText.textSize = k.h * 0.6f
        val label = if (k.isLetter && shiftState > 0) k.upper else k.label
        c.drawText(label, left + w / 2, baseline(bottom - h / 2, popupText), popupText)
    }

    private fun drawPopup(c: Canvas, p: Pointer, k: Key, dy: Float, t: Theme) {
        val cellW = k.w; val h = k.h * 1.1f
        val n = p.popupItems.size
        val bottom = k.y + dy - vGapPx / 2
        keyPaint.color = t.popup
        keyPaint.setShadowLayer(6f * density, 0f, 2f * density, 0x50000000)
        c.drawRoundRect(p.popupLeft, bottom - h, p.popupLeft + cellW * n, bottom, radius, radius, keyPaint)
        keyPaint.clearShadowLayer()
        popupText.textSize = k.h * 0.4f
        for (i in 0 until n) {
            val l = p.popupLeft + i * cellW
            if (i == p.popupSel) {
                keyPaint.color = t.popupSelected
                c.drawRoundRect(l + (4f * density), bottom - h + (4f * density), l + cellW - (4f * density), bottom - (4f * density), radius, radius, keyPaint)
            }
            popupText.color = if (i == p.popupSel) t.accentText else t.text
            val item = p.popupItems[i]
            val old = popupText.textSize
            if (item.length > 2) popupText.textSize = k.h * 0.26f
            c.drawText(item, l + cellW / 2, baseline(bottom - h / 2, popupText), popupText)
            popupText.textSize = old
        }
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val kb = keyboard ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                // Rollover: a new finger commits every finger still resting on a key.
                for (i in 0 until pointers.size()) {
                    val other = pointers.valueAt(i)
                    if (other.mode == Mode.GLIDE) return true // ignore extra fingers while gliding
                    if (other.mode == Mode.NORMAL && !other.committed) commitPending(other)
                }
                val p = Pointer(e.getPointerId(idx))
                p.downX = e.getX(idx); p.downY = e.getY(idx); p.lastX = p.downX
                p.key = resolveKey(kb, p.downX, p.downY)
                pointers.put(p.id, p)
                val k = p.key ?: return true
                listener.onKeyDown(k)
                if (k.code == Codes.DELETE) {
                    listener.onKey(k, "")
                    p.committed = true
                    p.repeat.count = 0
                    handler.postDelayed(p.repeat, 400)
                } else {
                    handler.postDelayed(p.longPress, LONG_PRESS_MS)
                }
                if (k.code == Codes.SHIFT) {
                    // Shift acts on DOWN so "hold shift + tap letter" works as a chord.
                    listener.onKey(k, "")
                    p.committed = true
                }
                invalidate(); listener.requestOverlayRedraw()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val p = pointers.get(e.getPointerId(i)) ?: continue
                    onMove(p, e, i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                val p = pointers.get(e.getPointerId(idx)) ?: return true
                onUp(p, e.getX(idx), e.getY(idx))
                pointers.remove(p.id)
                invalidate(); listener.requestOverlayRedraw()
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    /**
     * Touch areas meet edge to edge. With adaptive touch on, a tap near the border between two
     * letters goes to the one more likely to come next in a real word: each candidate scores a
     * Gaussian distance term plus a weighted log-probability of being the next letter. Taps well
     * inside a key never flip, because only neighbours within a quarter key of the touch compete.
     */
    private fun resolveKey(kb: Keyboard, x: Float, y: Float): Key? {
        val hit = kb.keyAt(x, y) ?: return null
        val prior = letterPrior
        if (!adaptiveTouch || prior == null || !hit.isLetter) return hit
        fun score(k: Key): Float {
            val dx = (k.cx - x) / k.w; val dy = (k.cy - y) / k.h
            val li = k.label[0].lowercaseChar() - 'a'
            val pr = if (li in 0..25) prior[li] else -6f
            return -(dx * dx + dy * dy) / (2f * 0.3f * 0.3f) + 0.5f * pr
        }
        var best = hit; var bestS = score(hit)
        val reachX = width / 10f * 0.25f
        for (k in kb.keys) {
            if (k === hit || !k.isLetter) continue
            val ox = maxOf(0f, k.x - x, x - (k.x + k.w))
            val oy = maxOf(0f, k.y - y, y - (k.y + k.h))
            if (ox > reachX || oy > k.h * 0.2f) continue
            val sc = score(k)
            if (sc > bestS) { bestS = sc; best = k }
        }
        return best
    }

    private fun commitPending(p: Pointer) {
        val k = p.key ?: return
        cancelTimers(p)
        p.committed = true
        if (k.code != Codes.SHIFT && k.code != Codes.DELETE) listener.onKey(k, k.text)
    }

    private fun onMove(p: Pointer, e: MotionEvent, i: Int) {
        val k = p.key ?: return
        val x = e.getX(i); val y = e.getY(i)
        val keyW = k.w.coerceAtMost(width / 10f * 1.5f)
        when (p.mode) {
            Mode.NORMAL -> {
                if (p.committed && k.code != Codes.DELETE) return
                p.lastX = x
                val dx = x - p.downX; val dy = y - p.downY
                val dist = hypot(dx, dy)
                if (k.code == Codes.SPACE) {
                    // Swipe before the hold timer fires = switch language (Samsung-style).
                    // Holding first turns the space bar into a cursor trackpad (see onLongPressTimeout).
                    if (abs(dx) > width / 10f * 1.2f) {
                        cancelTimers(p); p.mode = Mode.CONSUMED
                        listener.onSpaceSwipe(if (dx < 0) -1 else 1)
                        invalidate()
                    }
                } else if (k.code == Codes.DELETE && dx < -keyW * 0.8f) {
                    cancelTimers(p); p.mode = Mode.DELETE_SWIPE
                } else if (glideEnabled && k.isLetter && pointers.size() == 1 && keyboard?.id == Layouts.QWERTY &&
                    dist > (width / 10f) * 0.7f
                ) {
                    cancelTimers(p)
                    p.mode = Mode.GLIDE
                    glideN = 0; trailFadeAt = 0
                    addGlidePoint(p.downX, p.downY)
                    for (h in 0 until e.historySize) addGlidePoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                    addGlidePoint(x, y)
                    invalidate(); listener.requestOverlayRedraw()
                } else if (!k.isLetter && !k.isFunction && dist > keyW) {
                    // Slid off a symbol key: follow the finger like a normal keyboard.
                    val nk = keyboard?.keyAt(x, y)
                    if (nk != null && nk !== k && !nk.isFunction) {
                        cancelTimers(p); p.key = nk; p.downX = x; p.downY = y
                        handler.postDelayed(p.longPress, LONG_PRESS_MS)
                        invalidate(); listener.requestOverlayRedraw()
                    }
                } else if (k.isLetter && !glideEnabled && dist > keyW * 0.8f) {
                    val nk = keyboard?.keyAt(x, y)
                    if (nk != null && nk !== k) {
                        cancelTimers(p); p.key = nk; p.downX = x; p.downY = y
                        handler.postDelayed(p.longPress, LONG_PRESS_MS)
                        invalidate(); listener.requestOverlayRedraw()
                    }
                }
            }
            Mode.GLIDE -> {
                for (h in 0 until e.historySize) addGlidePoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                addGlidePoint(x, y)
                listener.requestOverlayRedraw()
            }
            Mode.CURSOR -> {
                val step = width / 10f * 0.33f
                val steps = ((x - p.cursorAnchor) / step).toInt()
                if (steps != 0) {
                    listener.onCursorMove(steps)
                    p.cursorAnchor += steps * step
                }
            }
            Mode.POPUP -> {
                val sel = ((x - p.popupLeft) / k.w).toInt().coerceIn(0, p.popupItems.size - 1)
                if (sel != p.popupSel) { p.popupSel = sel; listener.requestOverlayRedraw() }
            }
            Mode.DELETE_SWIPE -> p.lastX = x
            Mode.CONSUMED -> {}
        }
    }

    private fun addGlidePoint(x: Float, y: Float) {
        if (glideN == glideXs.size) {
            glideXs = glideXs.copyOf(glideN * 2); glideYs = glideYs.copyOf(glideN * 2)
        }
        glideXs[glideN] = x; glideYs[glideN] = y; glideN++
    }

    private fun onUp(p: Pointer, x: Float, y: Float) {
        cancelTimers(p)
        val k = p.key ?: return
        when (p.mode) {
            Mode.NORMAL -> if (!p.committed && k.code != Codes.SHIFT) listener.onKey(k, k.text)
            Mode.POPUP -> {
                val item = p.popupItems.getOrNull(p.popupSel)
                if (item != null) listener.onKey(k, if (k.isLetter && shiftState > 0) item.uppercase() else item)
            }
            Mode.GLIDE -> {
                listener.onGlide(glideXs, glideYs, glideN)
                trailFadeAt = SystemClock.uptimeMillis()
                listener.requestOverlayRedraw()
            }
            Mode.DELETE_SWIPE -> {
                val keyW = width / 10f
                val words = 1 + ((p.downX - x) / (keyW * 1.5f)).toInt().coerceAtLeast(0)
                listener.onDeleteWords(words)
            }
            Mode.CURSOR, Mode.CONSUMED -> {}
        }
    }

    private fun onLongPressTimeout(p: Pointer) {
        val k = p.key ?: return
        if (p.mode != Mode.NORMAL || p.committed) return
        if (k.code == Codes.SHIFT) { listener.onShiftLock(); p.mode = Mode.CONSUMED; return }
        if (k.code == Codes.SPACE) {
            // Hold space, then slide: move the cursor one character per step.
            p.mode = Mode.CURSOR
            p.cursorAnchor = p.lastX
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            return
        }
        if (listener.onLongPress(k)) {
            p.mode = Mode.CONSUMED
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate(); listener.requestOverlayRedraw()
            return
        }
        if (k.popup.isNotEmpty()) {
            p.mode = Mode.POPUP
            p.popupItems = k.popup
            p.popupSel = 0
            val total = k.w * k.popup.size
            p.popupLeft = (k.cx - k.w / 2).coerceAtMost(width - total).coerceAtLeast(0f)
            invalidate(); listener.requestOverlayRedraw()
        }
    }

    private fun cancelTimers(p: Pointer) {
        handler.removeCallbacks(p.longPress)
        handler.removeCallbacks(p.repeat)
    }

    fun cancelAll() {
        for (i in 0 until pointers.size()) cancelTimers(pointers.valueAt(i))
        pointers.clear()
        glideN = 0
        invalidate(); listener.requestOverlayRedraw()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAll()
    }

    companion object {
        const val LONG_PRESS_MS = 350L
        private val ENTER_LABELS = arrayOf("", "Go", "", "Send", "Next", "Done", "Prev")
    }
}
