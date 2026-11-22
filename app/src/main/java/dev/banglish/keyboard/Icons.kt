package dev.banglish.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Function-key icons drawn with paths, so they look the same on every phone regardless of
 * which symbol glyphs its fonts happen to include. One reusable Path/RectF: no allocation per frame.
 */
class Icons {
    private val path = Path()
    private val rect = RectF()
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setColor(c: Int, strokeWidth: Float) {
        stroke.color = c; fill.color = c; stroke.strokeWidth = strokeWidth
    }

    /** state: 0 off, 1 shift, 2 caps lock */
    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, state: Int) {
        path.reset()
        path.moveTo(cx, cy - s * 0.55f)
        path.lineTo(cx + s * 0.5f, cy)
        path.lineTo(cx + s * 0.22f, cy)
        path.lineTo(cx + s * 0.22f, cy + s * 0.38f)
        path.lineTo(cx - s * 0.22f, cy + s * 0.38f)
        path.lineTo(cx - s * 0.22f, cy)
        path.lineTo(cx - s * 0.5f, cy)
        path.close()
        c.drawPath(path, if (state > 0) fill else stroke)
        if (state == 2) c.drawLine(cx - s * 0.3f, cy + s * 0.58f, cx + s * 0.3f, cy + s * 0.58f, stroke)
    }

    fun delete(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx - s * 0.6f, cy)
        path.lineTo(cx - s * 0.25f, cy - s * 0.38f)
        path.lineTo(cx + s * 0.6f, cy - s * 0.38f)
        path.lineTo(cx + s * 0.6f, cy + s * 0.38f)
        path.lineTo(cx - s * 0.25f, cy + s * 0.38f)
        path.close()
        c.drawPath(path, stroke)
        val x0 = cx + s * 0.02f; val d = s * 0.15f
        c.drawLine(x0 + d, cy - d, x0 + d * 3, cy + d, stroke)
        c.drawLine(x0 + d * 3, cy - d, x0 + d, cy + d, stroke)
    }

    fun enter(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx + s * 0.45f, cy - s * 0.4f)
        path.lineTo(cx + s * 0.45f, cy + s * 0.1f)
        path.lineTo(cx - s * 0.45f, cy + s * 0.1f)
        c.drawPath(path, stroke)
        c.drawLine(cx - s * 0.45f, cy + s * 0.1f, cx - s * 0.2f, cy - s * 0.15f, stroke)
        c.drawLine(cx - s * 0.45f, cy + s * 0.1f, cx - s * 0.2f, cy + s * 0.35f, stroke)
    }

    fun search(c: Canvas, cx: Float, cy: Float, s: Float) {
        c.drawCircle(cx - s * 0.08f, cy - s * 0.08f, s * 0.3f, stroke)
        c.drawLine(cx + s * 0.14f, cy + s * 0.14f, cx + s * 0.45f, cy + s * 0.45f, stroke)
    }

    fun globe(c: Canvas, cx: Float, cy: Float, s: Float) {
        val r = s * 0.45f
        c.drawCircle(cx, cy, r, stroke)
        rect.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r)
        c.drawOval(rect, stroke)
        c.drawLine(cx - r, cy, cx + r, cy, stroke)
    }

    fun emoji(c: Canvas, cx: Float, cy: Float, s: Float) {
        val r = s * 0.45f
        c.drawCircle(cx, cy, r, stroke)
        c.drawCircle(cx - r * 0.38f, cy - r * 0.25f, s * 0.05f, fill)
        c.drawCircle(cx + r * 0.38f, cy - r * 0.25f, s * 0.05f, fill)
        rect.set(cx - r * 0.5f, cy - r * 0.2f, cx + r * 0.5f, cy + r * 0.55f)
        c.drawArc(rect, 20f, 140f, false, stroke)
    }

    fun clipboard(c: Canvas, cx: Float, cy: Float, s: Float) {
        rect.set(cx - s * 0.35f, cy - s * 0.4f, cx + s * 0.35f, cy + s * 0.5f)
        c.drawRoundRect(rect, s * 0.08f, s * 0.08f, stroke)
        rect.set(cx - s * 0.17f, cy - s * 0.5f, cx + s * 0.17f, cy - s * 0.3f)
        c.drawRoundRect(rect, s * 0.05f, s * 0.05f, fill)
    }

    fun gear(c: Canvas, cx: Float, cy: Float, s: Float) {
        val r = s * 0.3f
        c.drawCircle(cx, cy, r, stroke)
        c.drawCircle(cx, cy, r * 0.35f, fill)
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            val cos = Math.cos(a).toFloat(); val sin = Math.sin(a).toFloat()
            c.drawLine(cx + cos * r, cy + sin * r, cx + cos * r * 1.45f, cy + sin * r * 1.45f, stroke)
        }
    }

    fun chevronDown(c: Canvas, cx: Float, cy: Float, s: Float) {
        c.drawLine(cx - s * 0.35f, cy - s * 0.15f, cx, cy + s * 0.2f, stroke)
        c.drawLine(cx, cy + s * 0.2f, cx + s * 0.35f, cy - s * 0.15f, stroke)
    }

    fun pin(c: Canvas, cx: Float, cy: Float, s: Float, filled: Boolean) {
        c.drawCircle(cx, cy - s * 0.15f, s * 0.22f, if (filled) fill else stroke)
        c.drawLine(cx, cy + s * 0.07f, cx, cy + s * 0.45f, stroke)
    }
}
