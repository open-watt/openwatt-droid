package com.openwatt.droid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.openwatt.droid.util.UnitConverter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Generic time-series line chart for element history: recorded backfill plus
 * the live feed. Unit-aware Y labels via [UnitConverter]; X axis spans a
 * fixed rolling window ending "now" so the trace scrolls as live samples
 * land. Gaps larger than [gapBreakMs] break the line rather than drawing a
 * misleading ramp.
 */
class HistoryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var times: LongArray = LongArray(0)
    private var values: FloatArray = FloatArray(0)
    private var windowStart = 0L
    private var windowEnd = 0L
    private var unit: String = ""
    private var message: String? = "Loading…"

    /** Samples further apart than this break the trace (no data recorded). */
    var gapBreakMs = 0L

    private val dp = context.resources.displayMetrics.density
    private val sp = context.resources.displayMetrics.scaledDensity

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        color = 0xFF2563EB.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1A2563EB
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * dp
        color = 0xFFE2E8F0.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * sp
        color = 0xFF64748B.toInt()
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * sp
        color = 0xFF64748B.toInt()
        textAlign = Paint.Align.CENTER
    }

    /** Replace the series. Samples must be time-ordered (unix ms). */
    fun setData(ts: LongArray, vs: FloatArray, windowStart: Long, windowEnd: Long, unit: String) {
        times = ts
        values = vs
        this.windowStart = windowStart
        this.windowEnd = windowEnd
        this.unit = unit
        message = if (ts.isEmpty()) "No data in this range" else null
        invalidate()
    }

    fun setMessage(text: String?) {
        message = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val leftPad = 52f * dp
        val bottomPad = 20f * dp
        val topPad = 8f * dp
        val w = width - leftPad
        val h = height - bottomPad - topPad
        if (w <= 0 || h <= 0) return

        message?.let {
            canvas.drawText(it, width / 2f, height / 2f, messagePaint)
            if (times.isEmpty()) return
        }
        if (times.isEmpty() || windowEnd <= windowStart) return

        // Y range with a little headroom; degenerate flat series get ±1.
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (v in values) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val pad = max((maxV - minV) * 0.1f, 1e-6f)
        var lo = minV - pad
        var hi = maxV + pad
        if (hi - lo < 1e-6f) {
            lo -= 1f
            hi += 1f
        }

        fun x(t: Long): Float =
            leftPad + (t - windowStart).toFloat() / (windowEnd - windowStart) * w

        fun y(v: Float): Float = topPad + (hi - v) / (hi - lo) * h

        // Horizontal grid + unit-aware labels.
        val rows = 4
        for (i in 0..rows) {
            val v = hi - (hi - lo) * i / rows
            val yy = y(v)
            canvas.drawLine(leftPad, yy, width.toFloat(), yy, gridPaint)
            val label = UnitConverter.formatWithPrefix(v.toDouble(), unit, 3).formatted
            canvas.drawText(label, 4f * dp, yy + 3.5f * dp, labelPaint)
        }

        // Time labels at the edges of the window.
        val fmt = if (windowEnd - windowStart > 26 * 3600_000L) {
            SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        } else {
            SimpleDateFormat("HH:mm", Locale.getDefault())
        }
        canvas.drawText(fmt.format(Date(windowStart)), leftPad, height - 4f * dp, labelPaint)
        val endLabel = fmt.format(Date(windowEnd))
        val endWidth = labelPaint.measureText(endLabel)
        canvas.drawText(endLabel, width - endWidth - 2f * dp, height - 4f * dp, labelPaint)

        // Trace, broken at recording gaps; each run gets a soft fill to zero
        // of the visible range.
        val line = Path()
        val fill = Path()
        var runStartX = 0f
        var lastX = 0f
        var inRun = false
        val baseline = y(lo.coerceAtLeast(0f).coerceAtMost(hi))

        fun closeRun() {
            if (!inRun) return
            fill.lineTo(lastX, baseline)
            fill.lineTo(runStartX, baseline)
            fill.close()
            inRun = false
        }

        for (i in times.indices) {
            val xx = x(times[i])
            val yy = y(values[i])
            val gap = i > 0 && gapBreakMs > 0 && times[i] - times[i - 1] > gapBreakMs
            if (!inRun || gap) {
                closeRun()
                line.moveTo(xx, yy)
                fill.moveTo(xx, baseline)
                fill.lineTo(xx, yy)
                runStartX = xx
                inRun = true
            } else {
                line.lineTo(xx, yy)
                fill.lineTo(xx, yy)
            }
            lastX = xx
        }
        closeRun()

        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
    }
}
