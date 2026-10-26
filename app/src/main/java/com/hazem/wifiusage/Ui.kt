package com.hazem.wifiusage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** Colors. Dark is the main look (like the design reference); light is optional. */
object Palette {
    var bg = 0
    var card = 0
    var cardAlt = 0
    var text = 0
    var muted = 0
    var primary = 0
    var primaryEnd = 0
    var track = 0
    var divider = 0

    const val GREEN = 0xFF22C55E.toInt()
    const val ORANGE = 0xFFF59E0B.toInt()
    const val RED = 0xFFEF4444.toInt()
    const val PURPLE = 0xFF7C3AED.toInt()

    fun apply(dark: Boolean) {
        if (dark) {
            bg = 0xFF0A101F.toInt(); card = 0xFF121A2E.toInt(); cardAlt = 0xFF1A2540.toInt()
            text = 0xFFF3F6FC.toInt(); muted = 0xFF8B97AD.toInt()
            primary = 0xFF2F6BFF.toInt(); primaryEnd = 0xFF4F8CFF.toInt()
            track = 0xFF1F2A45.toInt(); divider = 0xFF1B2540.toInt()
        } else {
            bg = 0xFFF4F6FB.toInt(); card = Color.WHITE; cardAlt = 0xFFEAF0FF.toInt()
            text = 0xFF1B1F3B.toInt(); muted = 0xFF6B7280.toInt()
            primary = 0xFF2F6BFF.toInt(); primaryEnd = 0xFF4F8CFF.toInt()
            track = 0xFFE5E7EB.toInt(); divider = 0xFFE5E9F2.toInt()
        }
    }
}

/** Small icons drawn by code, so they can take any color and need no image files. */
class IconView(context: Context, private val kind: Int) : View(context) {

    var color: Int = Palette.muted
        set(value) { field = value; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.09f

        when (kind) {
            HOME -> {
                val roof = Path().apply {
                    moveTo(cx - 0.38f * s, cy)
                    lineTo(cx, cy - 0.36f * s)
                    lineTo(cx + 0.38f * s, cy)
                }
                c.drawPath(roof, p)
                val body = Path().apply {
                    moveTo(cx - 0.27f * s, cy - 0.08f * s)
                    lineTo(cx - 0.27f * s, cy + 0.34f * s)
                    lineTo(cx + 0.27f * s, cy + 0.34f * s)
                    lineTo(cx + 0.27f * s, cy - 0.08f * s)
                }
                c.drawPath(body, p)
            }
            WIFI -> {
                val oy = cy + 0.30f * s
                for (r in floatArrayOf(0.20f * s, 0.40f * s, 0.60f * s)) {
                    c.drawArc(RectF(cx - r, oy - r, cx + r, oy + r), 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(cx, oy, 0.06f * s, p)
            }
            BARS -> {
                p.strokeWidth = s * 0.14f
                val base = cy + 0.34f * s
                c.drawLine(cx - 0.26f * s, base, cx - 0.26f * s, base - 0.30f * s, p)
                c.drawLine(cx, base, cx, base - 0.62f * s, p)
                c.drawLine(cx + 0.26f * s, base, cx + 0.26f * s, base - 0.46f * s, p)
            }
            MORE -> {
                p.style = Paint.Style.FILL
                for (dx in floatArrayOf(-0.26f, 0f, 0.26f)) c.drawCircle(cx + dx * s, cy, 0.07f * s, p)
            }
            GEAR -> {
                c.drawCircle(cx, cy, 0.17f * s, p)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val x1 = cx + (0.30f * s * Math.cos(a)).toFloat()
                    val y1 = cy + (0.30f * s * Math.sin(a)).toFloat()
                    val x2 = cx + (0.42f * s * Math.cos(a)).toFloat()
                    val y2 = cy + (0.42f * s * Math.sin(a)).toFloat()
                    c.drawLine(x1, y1, x2, y2, p)
                }
            }
        }
    }

    companion object {
        const val HOME = 0
        const val WIFI = 1
        const val BARS = 2
        const val MORE = 3
        const val GEAR = 4
    }
}

/** Full ring with a colored arc that starts at the top and goes clockwise. */
class GaugeView(context: Context) : View(context) {

    var fraction = 0f
    var arcColor = Palette.primary

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val stroke = width * 0.07f
        val rect = RectF(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        trackPaint.strokeWidth = stroke
        trackPaint.color = Palette.track
        c.drawArc(rect, 0f, 360f, false, trackPaint)

        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            arcPaint.strokeWidth = stroke
            arcPaint.color = arcColor
            c.drawArc(rect, -90f, 360f * f, false, arcPaint)
        }
    }
}

/** Thin rounded progress bar. */
class ProgressBarView(context: Context) : View(context) {

    var fraction = 0f
    var color = Palette.primary

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        trackPaint.color = Palette.track
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, trackPaint)

        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return
        fillPaint.color = color
        val fw = (w * f).coerceAtLeast(h)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val rect = if (rtl) RectF(w - fw, 0f, w, h) else RectF(0f, 0f, fw, h)
        c.drawRoundRect(rect, r, r, fillPaint)
    }
}

/** Bars with a label under each one. The last bar can be highlighted (today / this month). */
class BarChartView(context: Context) : View(context) {

    var values: List<Long> = emptyList()
    var labels: List<String> = emptyList()
    var highlightLast = true

    private val d = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val n = values.size
        if (n == 0) return
        val max = values.max().coerceAtLeast(1L)
        val labelH = 24 * d
        val chartH = height - labelH
        val slot = width.toFloat() / n
        val barW = slot * 0.5f
        val step = if (n > 12) (n + 11) / 12 else 1
        textPaint.color = Palette.muted
        textPaint.textSize = 11 * d

        for (i in 0 until n) {
            val cx = slot * i + slot / 2
            val h = (chartH * values[i] / max).coerceAtLeast(5 * d)
            val top = chartH - h
            val lastBar = i == n - 1
            barPaint.shader = LinearGradient(
                0f, top, 0f, chartH,
                if (lastBar && highlightLast) Palette.primaryEnd else Palette.primary,
                if (lastBar && highlightLast) Palette.primary else 0x992F6BFF.toInt(),
                Shader.TileMode.CLAMP
            )
            c.drawRoundRect(RectF(cx - barW / 2, top, cx + barW / 2, chartH), 6 * d, 6 * d, barPaint)
            if (i % step == 0) c.drawText(labels[i], cx, height - 6 * d, textPaint)
        }
    }
}

/** Cumulative usage line with a soft fill and a GB scale on the left. */
class LineChartView(context: Context) : View(context) {

    /** Running total (in GB) for each day so far. */
    var values: List<Float> = emptyList()
    var totalDays = 30
    var yMax = 100f

    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        val left = 38 * d
        val right = width - 6 * d
        val top = 8 * d
        val bottom = height - 24 * d
        val plotW = right - left
        val plotH = bottom - top
        val max = yMax.coerceAtLeast(1f)

        grid.color = Palette.divider
        text.color = Palette.muted
        text.textSize = 10 * d

        // horizontal grid + GB labels
        text.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val y = bottom - plotH * i / 4f
            c.drawLine(left, y, right, y, grid)
            c.drawText("%d".format((max * i / 4f).toInt()), left - 6 * d, y + 3 * d, text)
        }

        // day labels
        text.textAlign = Paint.Align.CENTER
        val days = totalDays.coerceAtLeast(2)
        var day = 1
        while (day <= days) {
            val x = left + plotW * (day - 1) / (days - 1f)
            c.drawText(day.toString(), x, height - 6 * d, text)
            day = if (day == 1) 5 else day + 5
        }

        if (values.isEmpty()) return

        val path = Path()
        val area = Path()
        for (i in values.indices) {
            val x = left + plotW * i / (days - 1f)
            val y = bottom - plotH * (values[i] / max).coerceIn(0f, 1f)
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, bottom)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        val lastX = left + plotW * (values.size - 1) / (days - 1f)
        area.lineTo(lastX, bottom)
        area.close()

        fill.shader = LinearGradient(0f, top, 0f, bottom, 0x662F6BFF, 0x002F6BFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        line.color = Palette.primaryEnd
        c.drawPath(path, line)
    }
}
