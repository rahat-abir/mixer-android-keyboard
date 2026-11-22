package dev.banglish.keyboard.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import dev.banglish.keyboard.Icons
import dev.banglish.keyboard.Theme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class EmojiGroup(val name: String, val icon: String, val items: List<Emoji>)
class Emoji(val e: String, val tones: List<String>)

/** Emoji data from assets/emoji.txt, filtered to what this phone's font can draw. */
object EmojiData {
    @Volatile var groups: List<EmojiGroup>? = null
        private set

    private val ICONS = mapOf(
        "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐻",
        "Food & Drink" to "🍔", "Travel & Places" to "🚗", "Activities" to "⚽",
        "Objects" to "💡", "Symbols" to "❤️", "Flags" to "🏁",
    )

    fun load(context: Context) {
        if (groups != null) return
        val paint = Paint()
        val out = ArrayList<EmojiGroup>()
        var name = ""
        var items = ArrayList<Emoji>()
        context.assets.open("emoji.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                if (line[0] == '@') {
                    if (items.isNotEmpty()) out += EmojiGroup(name, ICONS[name] ?: items[0].e, items)
                    name = line.substring(1); items = ArrayList()
                    continue
                }
                val tab = line.indexOf('\t')
                val e = if (tab < 0) line else line.substring(0, tab)
                if (!paint.hasGlyph(e)) continue
                val tones = if (tab < 0) emptyList() else line.substring(tab + 1).split(' ').filter { paint.hasGlyph(it) }
                items += Emoji(e, tones)
            }
        }
        if (items.isNotEmpty()) out += EmojiGroup(name, ICONS[name] ?: items[0].e, items)
        groups = out
    }
}

@SuppressLint("ViewConstructor")
class EmojiView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onEmoji(e: String)
        fun onEmojiDelete()
        fun onEmojiSpace()
        fun onEmojiBack()
    }

    var theme: Theme = Theme.LIGHT
        set(v) { field = v; invalidate() }
    var recents: MutableList<String> = ArrayList()

    private val density = resources.displayMetrics.density
    private val tabH = 42f * density
    private val barH = 46f * density
    private val minCell = 44f * density
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 15f * density }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tabEmoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = tabH * 0.5f }
    private val icons = Icons()
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val handler = Handler(Looper.getMainLooper())

    // Flattened grid: sections[0] = recents, then each group.
    private var sectionItems: List<List<Emoji>> = emptyList()
    private var sectionIcons: List<String> = emptyList()
    private var sectionStartRow = IntArray(0)
    private var cols = 8
    private var cell = minCell
    private var totalRows = 0
    private var scrollY2 = 0f

    private var downX = 0f; private var downY = 0f; private var downScroll = 0f
    private var dragging = false
    private var pressedEmoji: Emoji? = null
    private var pressedCx = 0f; private var pressedCy = 0f
    private var popup: List<String>? = null
    private var popupSel = 0
    private var popupLeft = 0f
    private var region = 0 // 0 grid, 1 tabs, 2 bar
    private val longPress = Runnable { openPopup() }
    private val deleteRepeat = object : Runnable {
        override fun run() { listener.onEmojiDelete(); handler.postDelayed(this, 60) }
    }

    fun refresh() {
        val groups = EmojiData.groups ?: emptyList()
        val recentItems = recents.map { Emoji(it, emptyList()) }
        sectionItems = listOf(recentItems) + groups.map { it.items }
        sectionIcons = listOf("🕘") + groups.map { it.icon }
        rebuildRows()
        invalidate()
    }

    private fun rebuildRows() {
        if (width == 0) return
        cols = max(6, (width / minCell).toInt())
        cell = width / cols.toFloat()
        sectionStartRow = IntArray(sectionItems.size)
        var r = 0
        for (i in sectionItems.indices) {
            sectionStartRow[i] = r
            r += (sectionItems[i].size + cols - 1) / cols
        }
        totalRows = r
        emojiPaint.textSize = cell * 0.62f
        // Start on smileys when there are no recents yet.
        if (recents.isEmpty() && scrollY2 == 0f && sectionStartRow.size > 1) scrollY2 = sectionStartRow[1] * cell
        clampScroll()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuildRows()

    private val gridTop get() = tabH
    private val gridH get() = height - tabH - barH
    private fun maxScroll() = max(0f, totalRows * cell - gridH)
    private fun clampScroll() { scrollY2 = scrollY2.coerceIn(0f, maxScroll()) }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY2 = scroller.currY.toFloat(); clampScroll(); postInvalidateOnAnimation()
        }
    }

    private fun currentSection(): Int {
        val row = ((scrollY2 + 1) / cell).toInt()
        var s = 0
        for (i in sectionStartRow.indices) if (sectionStartRow[i] <= row && sectionItems[i].isNotEmpty()) s = i
        return s
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        // Grid
        c.save()
        c.clipRect(0f, gridTop, width.toFloat(), gridTop + gridH)
        val firstRow = (scrollY2 / cell).toInt()
        val lastRow = ((scrollY2 + gridH) / cell).toInt() + 1
        for (s in sectionItems.indices) {
            val items = sectionItems[s]
            val start = sectionStartRow.getOrElse(s) { 0 }
            val rows = (items.size + cols - 1) / cols
            if (start + rows < firstRow || start > lastRow) continue
            for (i in items.indices) {
                val row = start + i / cols
                if (row < firstRow || row > lastRow) continue
                val cx = (i % cols) * cell + cell / 2
                val cy = gridTop + row * cell - scrollY2 + cell / 2
                if (items[i] === pressedEmoji && popup == null) {
                    bg.color = t.keyPressed
                    c.drawCircle(cx, cy, cell * 0.46f, bg)
                }
                c.drawText(items[i].e, cx, cy - (emojiPaint.descent() + emojiPaint.ascent()) / 2, emojiPaint)
            }
        }
        c.restore()
        // Tabs
        bg.color = t.functionKey
        c.drawRect(0f, 0f, width.toFloat(), tabH, bg)
        val n = sectionIcons.size
        if (n > 0) {
            val tw = width / n.toFloat()
            val cur = currentSection()
            for (i in 0 until n) {
                val cx = i * tw + tw / 2
                if (i == cur) { bg.color = t.accent; c.drawRect(i * tw + tw * 0.2f, tabH - 3 * density, (i + 1) * tw - tw * 0.2f, tabH, bg) }
                tabEmoji.alpha = if (i == cur) 255 else 150
                c.drawText(sectionIcons[i], cx, tabH / 2 - (tabEmoji.descent() + tabEmoji.ascent()) / 2, tabEmoji)
            }
        }
        // Bottom bar: ABC | space | delete
        val by = height - barH
        bg.color = t.functionKey
        c.drawRect(0f, by, width.toFloat(), height.toFloat(), bg)
        labelPaint.color = t.text
        val third = width / 4f
        c.drawText("ABC", third / 2, by + barH / 2 - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint)
        bg.color = t.key
        val g = 5 * density
        c.drawRoundRect(third + g, by + g, width - third - g, height - g, g, g, bg)
        icons.setColor(t.text, 2f * density)
        icons.delete(c, width - third / 2, by + barH / 2, barH * 0.45f)
        // Skin-tone popup
        val p = popup
        if (p != null) {
            val h = cell * 1.1f
            val top = (pressedCy - cell / 2 - h).coerceAtLeast(0f)
            bg.color = t.popup
            bg.setShadowLayer(6 * density, 0f, 2 * density, 0x50000000)
            c.drawRoundRect(popupLeft, top, popupLeft + cell * p.size, top + h, g * 2, g * 2, bg)
            bg.clearShadowLayer()
            for (i in p.indices) {
                val l = popupLeft + i * cell
                if (i == popupSel) { bg.color = t.keyPressed; c.drawCircle(l + cell / 2, top + h / 2, cell * 0.45f, bg) }
                c.drawText(p[i], l + cell / 2, top + h / 2 - (emojiPaint.descent() + emojiPaint.ascent()) / 2, emojiPaint)
            }
        }
    }

    private fun emojiAt(x: Float, y: Float): Triple<Emoji, Float, Float>? {
        if (y < gridTop || y > gridTop + gridH) return null
        val row = ((y - gridTop + scrollY2) / cell).toInt()
        val col = (x / cell).toInt().coerceIn(0, cols - 1)
        for (s in sectionItems.indices.reversed()) {
            val start = sectionStartRow[s]
            if (row >= start) {
                val idx = (row - start) * cols + col
                val e = sectionItems[s].getOrNull(idx) ?: return null
                return Triple(e, col * cell + cell / 2, gridTop + row * cell - scrollY2 + cell / 2)
            }
        }
        return null
    }

    private fun openPopup() {
        val e = pressedEmoji ?: return
        if (e.tones.isEmpty()) return
        val list = listOf(e.e) + e.tones
        popup = list; popupSel = 0
        popupLeft = (pressedCx - cell / 2).coerceAtMost(width - cell * list.size).coerceAtLeast(0f)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; downScroll = scrollY2; dragging = false
                velocity?.recycle(); velocity = VelocityTracker.obtain(); velocity?.addMovement(e)
                region = when { e.y < tabH -> 1; e.y > height - barH -> 2; else -> 0 }
                if (region == 0) {
                    val hit = emojiAt(e.x, e.y)
                    pressedEmoji = hit?.first; pressedCx = hit?.second ?: 0f; pressedCy = hit?.third ?: 0f
                    if (hit != null) handler.postDelayed(longPress, 350)
                } else if (region == 2 && e.x > width * 0.75f) {
                    listener.onEmojiDelete(); handler.postDelayed(deleteRepeat, 400)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (popup != null) {
                    popupSel = ((e.x - popupLeft) / cell).toInt().coerceIn(0, popup!!.size - 1); invalidate()
                } else if (region == 0 && (dragging || abs(e.y - downY) > 8 * density)) {
                    if (!dragging) { dragging = true; handler.removeCallbacks(longPress); pressedEmoji = null }
                    scrollY2 = downScroll - (e.y - downY); clampScroll(); invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress); handler.removeCallbacks(deleteRepeat)
                velocity?.addMovement(e)
                val p = popup
                when {
                    p != null -> commit(p[popupSel])
                    region == 0 && dragging -> {
                        velocity?.computeCurrentVelocity(1000)
                        val vy = velocity?.yVelocity ?: 0f
                        scroller.fling(0, scrollY2.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
                        postInvalidateOnAnimation()
                    }
                    region == 0 -> pressedEmoji?.let { commit(it.e) }
                    region == 1 -> {
                        val n = sectionIcons.size
                        if (n > 0) {
                            val i = min(n - 1, (e.x / (width / n.toFloat())).toInt())
                            scrollY2 = sectionStartRow[i] * cell; clampScroll()
                        }
                    }
                    region == 2 && e.x < width * 0.25f -> listener.onEmojiBack()
                    region == 2 && e.x < width * 0.75f -> listener.onEmojiSpace()
                }
                popup = null; pressedEmoji = null
                velocity?.recycle(); velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress); handler.removeCallbacks(deleteRepeat)
                popup = null; pressedEmoji = null; invalidate()
            }
        }
        return true
    }

    private fun commit(e: String) {
        listener.onEmoji(e)
        recents.remove(e); recents.add(0, e)
        while (recents.size > 32) recents.removeAt(recents.size - 1)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}
