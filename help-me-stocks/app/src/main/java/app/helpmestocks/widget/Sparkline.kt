package app.helpmestocks.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import app.helpmestocks.data.Quote
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Intraday chart drawn as white-on-transparent bitmaps that the widget tints with theme
 * colours (SRC_IN keeps the alpha), so one bitmap follows light/dark and gain/loss colours
 * without re-rendering. Like Apple's widget, the x-axis spans the whole session: a chart drawn
 * mid-day stops part-way across, and a dotted line marks the previous close.
 */
object Sparkline {
    private const val MAX_DENSITY = 2.75f
    private const val MAX_PIXELS = 200_000f // ~800 KB per ARGB bitmap

    class Bitmaps(val line: Bitmap, val baseline: Bitmap?)

    fun draw(q: Quote, widthDp: Float, heightDp: Float, density: Float, fill: Boolean): Bitmaps? {
        if (q.times.size < 2 || widthDp < 8f || heightDp < 8f) return null
        // Widget bitmaps share a budget (about 1.5× the screen for all of a widget's layouts), so
        // each chart is capped: plenty sharp at row and 4×2 sizes, softer only on huge resizes.
        var d = density.coerceIn(1f, MAX_DENSITY)
        val area = widthDp * heightDp * d * d
        if (area > MAX_PIXELS) d *= kotlin.math.sqrt(MAX_PIXELS / area)
        val w = (widthDp * d).roundToInt().coerceAtLeast(1)
        val h = (heightDp * d).roundToInt().coerceAtLeast(1)
        val stroke = 1.75f * d
        val pad = stroke
        val span = (q.sessionEnd - q.sessionStart).coerceAtLeast(1L).toFloat()
        val t0 = q.sessionStart
        val prev = q.previousClose
        var lo = q.prices.min(); var hi = q.prices.max()
        if (prev != null && prev.isFinite()) { lo = min(lo, prev); hi = max(hi, prev) }
        val range = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun x(t: Long) = pad + ((t - t0) / span).coerceIn(0f, 1f) * (w - 2 * pad)
        fun y(v: Double) = (pad + (1 - (v - lo) / range) * (h - 2 * pad)).toFloat()

        val path = Path()
        q.times.forEachIndexed { i, t -> if (i == 0) path.moveTo(x(t), y(q.prices[i])) else path.lineTo(x(t), y(q.prices[i])) }

        val line = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(line).apply {
            if (fill) {
                val area = Path(path).apply {
                    lineTo(x(q.times.last()), h.toFloat()); lineTo(x(q.times.first()), h.toFloat()); close()
                }
                drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x47FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                })
            }
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; color = 0xFFFFFFFF.toInt()
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            })
        }
        val baseline = prev?.takeIf { it.isFinite() }?.let { p ->
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val yy = y(p)
                Canvas(b).drawLine(pad, yy, w - pad, yy, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 1.25f * d; color = 0xFFFFFFFF.toInt()
                    pathEffect = DashPathEffect(floatArrayOf(1.25f * d, 3f * d), 0f); strokeCap = Paint.Cap.ROUND
                })
            }
        }
        return Bitmaps(line, baseline)
    }
}
