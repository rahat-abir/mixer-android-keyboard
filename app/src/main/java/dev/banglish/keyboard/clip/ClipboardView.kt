package dev.banglish.keyboard.clip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import dev.banglish.keyboard.Icons
import dev.banglish.keyboard.Theme
import kotlin.math.abs
import kotlin.math.max

/** Clipboard history panel: tap to paste, pin icon to keep, long-press to delete. */
@SuppressLint("ViewConstructor")
class ClipboardView(context: Context, private val history: ClipHistory, private val listener: Listener) : View(context) {

    interface Listener {
        fun onClipPaste(text: String)
        fun onClipBack()
    }

    var theme: Theme = Theme.LIGHT
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val headerH = 42f * density
    private val rowH = 64f * density
    private val pad = 12f * density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * density }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * density }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons = Icons()
    private var scroll = 0f
    private var downX = 0f; private var downY = 0f; private var downScroll = 0f
    private var dragging = false
    private var pressed = -1
    private var longPressed = false
    private val layouts = HashMap<String, StaticLayout>()
    private val longPress = Runnable {
        if (pressed >= 0) { history.remove(pressed); longPressed = true; pressed = -1; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); invalidate() }
    }

    fun refresh() { layouts.clear(); scroll = 0f; invalidate() }

    private fun layoutFor(text: String, w: Int): StaticLayout = layouts.getOrPut(text) {
        val oneLine = text.replace('\n', ' ')
        StaticLayout.Builder.obtain(oneLine, 0, oneLine.length, textPaint, max(1, w))
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        textPaint.color = t.text
        val items = history.items
        c.save()
        c.clipRect(0f, headerH, width.toFloat(), height.toFloat())
        if (items.isEmpty()) {
            headPaint.color = t.hintText
            headPaint.textAlign = Paint.Align.CENTER
            c.drawText("Copied text shows up here. Long-press C to copy, V to paste.", width / 2f, headerH + 48 * density, headPaint)
            headPaint.textAlign = Paint.Align.LEFT
        }
        val pinX = width - pad - 14 * density
        for (i in items.indices) {
            val top = headerH + i * rowH - scroll
            if (top > height || top + rowH < headerH) continue
            bg.color = if (i == pressed) t.keyPressed else t.key
            c.drawRoundRect(pad / 2, top + 4 * density, width - pad / 2, top + rowH - 4 * density, 8 * density, 8 * density, bg)
            val l = layoutFor(items[i].text, (width - pad * 4 - 28 * density).toInt())
            c.save()
            c.translate(pad, top + (rowH - l.height) / 2)
            l.draw(c)
            c.restore()
            icons.setColor(if (items[i].pinned) t.accent else t.hintText, 1.8f * density)
            icons.pin(c, pinX, top + rowH / 2, 26 * density, items[i].pinned)
        }
        c.restore()
        // Header: ABC (back) ... Clear
        bg.color = t.functionKey
        c.drawRect(0f, 0f, width.toFloat(), headerH, bg)
        headPaint.color = t.text
        val base = headerH / 2 - (headPaint.descent() + headPaint.ascent()) / 2
        c.drawText("ABC", pad, base, headPaint)
        headPaint.textAlign = Paint.Align.CENTER
        c.drawText("Clipboard", width / 2f, base, headPaint)
        headPaint.textAlign = Paint.Align.RIGHT
        headPaint.color = t.accent
        c.drawText("Clear", width - pad, base, headPaint)
        headPaint.textAlign = Paint.Align.LEFT
    }

    private fun maxScroll() = max(0f, history.items.size * rowH - (height - headerH))

    private fun indexAt(y: Float): Int {
        if (y < headerH) return -1
        val i = ((y - headerH + scroll) / rowH).toInt()
        return if (i in history.items.indices) i else -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downScroll = scroll; dragging = false; longPressed = false
                pressed = indexAt(e.y)
                if (pressed >= 0 && e.x < width - 48 * density) postDelayed(longPress, 500)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging || abs(e.y - downY) > 8 * density) {
                if (!dragging) { dragging = true; removeCallbacks(longPress); pressed = -1 }
                scroll = (downScroll - (e.y - downY)).coerceIn(0f, maxScroll()); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!dragging && !longPressed) {
                    if (e.y < headerH) {
                        if (e.x < width / 3f) listener.onClipBack()
                        else if (e.x > width * 2 / 3f) { history.clearUnpinned(); refresh() }
                    } else {
                        val i = indexAt(e.y)
                        if (i >= 0) {
                            if (e.x > width - 48 * density) history.togglePin(i)
                            else listener.onClipPaste(history.items[i].text)
                        }
                    }
                }
                pressed = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); pressed = -1; invalidate() }
        }
        return true
    }
}
