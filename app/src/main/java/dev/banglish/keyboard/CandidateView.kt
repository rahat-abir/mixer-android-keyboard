package dev.banglish.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * The strip above the keys: suggestions when there are any, otherwise a small toolbar
 * (clipboard, settings, hide). Horizontally scrollable when there are many Bangla candidates.
 */
@SuppressLint("ViewConstructor")
class CandidateView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onPickSuggestion(index: Int, word: String)
        fun onToolbar(action: Int)
    }

    var theme: Theme = Theme.LIGHT
        set(v) { field = v; invalidate() }

    private var items: List<String> = emptyList()
    private var bold = -1
    private var message: String? = null
    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 17f * density }
    private val boldText = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    private val line = Paint()
    private val icons = Icons()
    private var lefts = FloatArray(8)
    private var widths = FloatArray(8)
    private var contentW = 0f
    private var scrollPos = 0f
    private var downX = 0f
    private var downScroll = 0f
    private var dragging = false
    private var pressed = -1

    fun setSuggestions(list: List<String>, boldIndex: Int) {
        items = list; bold = boldIndex; message = null; scrollPos = 0f
        measureItems(); invalidate()
    }

    fun showMessage(msg: String) {
        message = msg; invalidate()
        removeCallbacks(clearMessage); postDelayed(clearMessage, 1500)
    }

    private val clearMessage = Runnable { message = null; invalidate() }

    val hasSuggestions get() = items.isNotEmpty()

    private fun measureItems() {
        if (lefts.size < items.size) { lefts = FloatArray(items.size * 2); widths = FloatArray(items.size * 2) }
        val minW = if (width > 0) width / 3f else 0f
        var x = 0f
        for (i in items.indices) {
            val w = max(minW, text.measureText(items[i]) + 32f * density)
            lefts[i] = x; widths[i] = w; x += w
        }
        contentW = x
        // Three or fewer fit exactly: spread them across the full width.
        if (items.size in 1..3 && contentW <= width) {
            val w = width / items.size.toFloat()
            for (i in items.indices) { lefts[i] = i * w; widths[i] = w }
            contentW = width.toFloat()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = measureItems()

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        text.color = t.text; boldText.color = t.text
        val cy = height / 2f
        val msg = message
        if (msg != null) {
            boldText.color = t.accent
            c.drawText(msg, width / 2f, cy - (boldText.descent() + boldText.ascent()) / 2, boldText)
            return
        }
        if (items.isEmpty()) { drawToolbar(c, t); return }
        line.color = t.divider
        for (i in items.indices) {
            val l = lefts[i] - scrollPos
            if (l > width || l + widths[i] < 0) continue
            if (i == pressed) { line.color = t.keyPressed; c.drawRect(l, 0f, l + widths[i], height.toFloat(), line); line.color = t.divider }
            val p = if (i == bold) boldText else text
            var s = items[i]
            if (p.measureText(s) > widths[i] - 8 * density) s = ellipsize(s, p, widths[i] - 8 * density)
            c.drawText(s, l + widths[i] / 2, cy - (p.descent() + p.ascent()) / 2, p)
            if (i > 0) c.drawRect(l, height * 0.25f, l + density, height * 0.75f, line)
        }
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    // Toolbar slots: clipboard, emoji (left); settings, hide (right).
    private fun toolbarSlot(x: Float): Int {
        val slot = height * 1.3f
        return when {
            x < slot -> TOOL_CLIPBOARD
            x < slot * 2 -> TOOL_EMOJI
            x > width - slot -> TOOL_HIDE
            x > width - slot * 2 -> TOOL_SETTINGS
            else -> -1
        }
    }

    private fun drawToolbar(c: Canvas, t: Theme) {
        val slot = height * 1.3f
        val s = height * 0.5f
        val cy = height / 2f
        icons.setColor(t.hintText, 1.8f * density)
        icons.clipboard(c, slot / 2, cy, s)
        icons.emoji(c, slot * 1.5f, cy, s)
        icons.gear(c, width - slot * 1.5f, cy, s)
        icons.chevronDown(c, width - slot / 2, cy, s)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downScroll = scrollPos; dragging = false
                pressed = indexAt(e.x); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (contentW > width && abs(e.x - downX) > 8 * density) {
                    dragging = true; pressed = -1
                    scrollPos = (downScroll - (e.x - downX)).coerceIn(0f, contentW - width)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    if (items.isEmpty()) {
                        val a = toolbarSlot(e.x)
                        if (a >= 0) listener.onToolbar(a)
                    } else {
                        val i = indexAt(e.x)
                        if (i >= 0) listener.onPickSuggestion(i, items[i])
                    }
                }
                pressed = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    private fun indexAt(x: Float): Int {
        val cx = x + scrollPos
        for (i in items.indices) if (cx >= lefts[i] && cx < lefts[i] + widths[i]) return i
        return -1
    }

    companion object {
        const val TOOL_CLIPBOARD = 0
        const val TOOL_EMOJI = 1
        const val TOOL_SETTINGS = 2
        const val TOOL_HIDE = 3
    }
}
