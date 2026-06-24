package com.openwatt.droid.ui.config

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * Canvas chart views for the config editor's Traffic panel. Ported from the web
 * client's bandwidth-chart.js / backpressure-chart.js.
 *
 * [RateChartView] is one area+line panel (used side-by-side for RX and TX).
 * [BackpressureChartView] stacks queue + service time and overlays the max as a
 * red line. Both draw their label and current value as an in-canvas overlay so
 * the chart uses the full panel height.
 */

private const val CHART_BG = 0xFFF8FAFC.toInt()
private const val GRID = 0xFFE2E8F0.toInt()
private const val TEXT_MUTED = 0xFF64748B.toInt()

private fun areaAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

class RateChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private var data = FloatArray(0)
    private var label = ""
    private var lineColor = 0xFF2563EB.toInt()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = CHART_BG }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp; color = GRID
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * sp; isFakeBoldText = true }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * sp; color = TEXT_MUTED; textAlign = Paint.Align.RIGHT
    }

    private val path = Path()

    fun configure(label: String, lineColor: Int) {
        this.label = label
        this.lineColor = lineColor
        invalidate()
    }

    /** Series values in display units (bits/s). */
    fun setData(values: FloatArray) {
        data = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 4f * dp
        val left = pad
        val top = pad
        val right = width - pad
        val bottom = height - pad
        val cw = right - left
        val ch = bottom - top
        if (cw <= 0 || ch <= 0) return

        canvas.drawRect(left, top, right, bottom, bgPaint)
        for (i in 0..3) {
            val y = top + i / 3f * ch
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        if (data.size >= 2) {
            var maxV = MIN_Y
            for (v in data) if (v > maxV) maxV = v
            val yMax = maxV * 1.1f
            val xStep = cw / (data.size - 1)

            fun yAt(v: Float) = top + (1f - v / yMax) * ch

            fillPaint.color = areaAlpha(lineColor, 51)
            path.reset()
            path.moveTo(left, bottom)
            for (i in data.indices) path.lineTo(left + i * xStep, yAt(data[i]))
            path.lineTo(left + (data.size - 1) * xStep, bottom)
            path.close()
            canvas.drawPath(path, fillPaint)

            linePaint.color = lineColor
            path.reset()
            for (i in data.indices) {
                val x = left + i * xStep
                val y = yAt(data[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }

        val baseline = top + 13f * dp
        labelPaint.color = lineColor
        canvas.drawText(label, left + 4f * dp, baseline, labelPaint)
        canvas.drawText(formatBps(data.lastOrNull() ?: 0f), right - 4f * dp, baseline, valuePaint)
    }

    companion object {
        private const val MIN_Y = 100f // bits/s — avoids jitter on a flat line
    }
}

class BackpressureChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private var queue = FloatArray(0)
    private var service = FloatArray(0)
    private var maxSvc = FloatArray(0)

    private val queueLine = 0xFFD97706.toInt()   // amber — back-pressure
    private val serviceLine = 0xFF0D9488.toInt()  // teal — normal transmit
    private val maxColor = 0xFFDC2626.toInt()     // red — peak service time

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = CHART_BG }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp; color = GRID
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * sp; isFakeBoldText = true }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * sp; color = TEXT_MUTED; textAlign = Paint.Align.RIGHT
    }

    private val path = Path()

    /** All series in milliseconds. */
    fun setData(queue: FloatArray, service: FloatArray, maxSvc: FloatArray) {
        this.queue = queue
        this.service = service
        this.maxSvc = maxSvc
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 4f * dp
        val left = pad
        val top = pad
        val right = width - pad
        val bottom = height - pad
        val cw = right - left
        val ch = bottom - top
        if (cw <= 0 || ch <= 0) return

        canvas.drawRect(left, top, right, bottom, bgPaint)
        for (i in 0..3) {
            val y = top + i / 3f * ch
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        val n = queue.size
        if (n >= 2 && service.size == n) {
            val stacked = FloatArray(n) { queue[it] + service[it] }
            var peak = MIN_Y
            for (v in stacked) if (v > peak) peak = v
            for (v in maxSvc) if (v > peak) peak = v
            val yMax = peak * 1.1f
            val xStep = cw / (n - 1)
            fun yAt(v: Float) = top + (1f - v / yMax) * ch

            // Service area (full stacked total), then queue area on the bottom.
            fillArea(canvas, stacked, left, bottom, xStep, ::yAt, areaAlpha(serviceLine, 64))
            fillArea(canvas, queue, left, bottom, xStep, ::yAt, areaAlpha(queueLine, 64))

            // Boundary lines: stacked total (teal) and queue (amber).
            strokeSeries(canvas, stacked, left, xStep, ::yAt, serviceLine)
            strokeSeries(canvas, queue, left, xStep, ::yAt, queueLine)

            // Max service time as a red line.
            if (maxSvc.size == n) strokeSeries(canvas, maxSvc, left, xStep, ::yAt, maxColor)
        }

        val baseline = top + 13f * dp
        labelPaint.color = queueLine
        canvas.drawText("Back-pressure", left + 4f * dp, baseline, labelPaint)
        val text = "Queue ${formatMs(queue.lastOrNull())} · Svc ${formatMs(service.lastOrNull())}" +
            " · Max ${formatMs(maxSvc.lastOrNull())}"
        canvas.drawText(text, right - 4f * dp, baseline, valuePaint)
    }

    private fun fillArea(
        canvas: Canvas, values: FloatArray, left: Float, bottom: Float, xStep: Float,
        yAt: (Float) -> Float, color: Int,
    ) {
        fillPaint.color = color
        path.reset()
        path.moveTo(left, bottom)
        for (i in values.indices) path.lineTo(left + i * xStep, yAt(values[i]))
        path.lineTo(left + (values.size - 1) * xStep, bottom)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun strokeSeries(
        canvas: Canvas, values: FloatArray, left: Float, xStep: Float,
        yAt: (Float) -> Float, color: Int,
    ) {
        linePaint.color = color
        path.reset()
        for (i in values.indices) {
            val x = left + i * xStep
            val y = yAt(values[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }

    companion object {
        private const val MIN_Y = 0.5f // ms — avoids jitter on flat lines
    }
}

// ── value formatting (mirrors the web charts) ────────────────────────────────

/** Format bits/s with an SI prefix: "0 bps", "12.3 Mbps", "1.20 Gbps". */
private fun formatBps(value: Float): String {
    if (value.isNaN()) return "—"
    if (value <= 0f) return "0 bps"
    val tiers = arrayOf(1e9f to "Gbps", 1e6f to "Mbps", 1e3f to "kbps")
    for ((threshold, suffix) in tiers) {
        if (value >= threshold) {
            val scaled = value / threshold
            val str = when {
                scaled < 10 -> "%.2f".format(scaled)
                scaled < 100 -> "%.1f".format(scaled)
                else -> scaled.roundToInt().toString()
            }
            return "$str $suffix"
        }
    }
    return "${value.roundToInt()} bps"
}

/** Format a millisecond value: "0 ms", "0.45 ms", "12.3 ms", "1.23 s". */
private fun formatMs(value: Float?): String {
    if (value == null || value.isNaN()) return "—"
    if (value == 0f) return "0 ms"
    if (value >= 1000f) {
        val s = value / 1000f
        return when {
            s < 10 -> "%.2f s".format(s)
            s < 100 -> "%.1f s".format(s)
            else -> "${s.roundToInt()} s"
        }
    }
    return when {
        value < 1 -> "%.2f ms".format(value)
        value < 100 -> "%.1f ms".format(value)
        else -> "${value.roundToInt()} ms"
    }
}
