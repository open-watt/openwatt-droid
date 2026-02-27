package com.openwatt.droid.ui.energy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Custom Canvas View for the realtime scrolling power chart.
 * Shows power (watts) over time with:
 * - Red filled area above zero (importing)
 * - Green filled area below zero (exporting)
 * - Blue line for the power trace
 * - Zero line emphasized
 * - Y-axis power labels, X-axis time labels
 *
 * Ported from openwatt-web/js/ui/energy/chart.js
 */
class PowerChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var timestamps: LongArray = LongArray(0)
    private var power: FloatArray = FloatArray(0)
    private var emptyMessage: String? = null

    private val dp = context.resources.displayMetrics.density
    private val sp = context.resources.displayMetrics.scaledDensity

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        color = 0xFF2563EB.toInt() // Blue
    }

    private val importFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33DC2626.toInt() // Red 20%
    }

    private val exportFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x3316A34A.toInt() // Green 20%
    }

    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        color = 0xFF94A3B8.toInt() // Slate gray
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * dp
        color = 0xFFE2E8F0.toInt() // Light gray
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * sp
        color = 0xFF64748B.toInt() // Medium gray
    }

    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * sp
        color = 0xFF64748B.toInt()
        textAlign = Paint.Align.CENTER
    }

    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * sp
        color = 0xFF64748B.toInt()
    }

    private val importLegendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x80DC2626.toInt()
    }

    private val exportLegendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x8016A34A.toInt()
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFF8FAFC.toInt()
    }

    private val importPath = Path()
    private val exportPath = Path()
    private val linePath = Path()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Y-axis high-water marks — tracked independently for positive/negative so the chart
    // adapts to asymmetric data (e.g., mostly importing with little/no export)
    private var highWaterPos = 0f
    private var highWaterNeg = 0f

    fun setData(ts: LongArray, pw: FloatArray) {
        timestamps = ts
        power = pw
        emptyMessage = null
        invalidate()
    }

    /** Reset the Y-axis scale (e.g., on reconnect or period change). */
    fun resetScale() {
        highWaterPos = 0f
        highWaterNeg = 0f
    }

    fun setEmptyMessage(message: String?) {
        emptyMessage = message
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = Padding(
            top = 22f * dp,
            right = 12f * dp,
            bottom = 24f * dp,
            left = 50f * dp,
        )
        val chartW = w - padding.left - padding.right
        val chartH = h - padding.top - padding.bottom

        // Chart background
        canvas.drawRect(padding.left, padding.top, w - padding.right, h - padding.bottom, bgPaint)

        // Show explicit message if set
        if (emptyMessage != null) {
            canvas.drawText(emptyMessage!!, w / 2, h / 2, messagePaint)
            return
        }

        // Calculate Y-axis range (independent high-water marks for + and -)
        var currentMaxPos = 0f
        var currentMaxNeg = 0f
        for (p in power) {
            if (p > currentMaxPos) currentMaxPos = p
            if (p < currentMaxNeg) currentMaxNeg = p
        }
        highWaterPos = max(highWaterPos, currentMaxPos)
        highWaterNeg = minOf(highWaterNeg, currentMaxNeg)

        // Each side gets its own range with a 100W floor so zero line stays visible
        val posRange = highWaterPos.coerceAtLeast(100f)
        val negRange = abs(highWaterNeg).coerceAtLeast(100f)
        val yMax = posRange * 1.15f
        val yMin = -negRange * 1.15f
        val yRange = yMax - yMin

        // Draw grid lines and Y labels
        val ySteps = 4
        for (i in 0..ySteps) {
            val y = padding.top + (i.toFloat() / ySteps) * chartH
            canvas.drawLine(padding.left, y, w - padding.right, y, gridPaint)

            val value = yMax - (i.toFloat() / ySteps) * yRange
            val label = formatPowerCompact(value)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(label, padding.left - 4 * dp, y + 4 * dp, labelPaint)
        }

        // Draw zero line
        val zeroY = padding.top + (yMax / yRange) * chartH
        canvas.drawLine(padding.left, zeroY, w - padding.right, zeroY, zeroLinePaint)

        // Draw data if we have at least 2 points
        if (power.size >= 2) {
            val xStep = chartW / (power.size - 1)

            importPath.reset()
            exportPath.reset()
            linePath.reset()

            // Import area (positive power)
            importPath.moveTo(padding.left, zeroY)
            for (i in power.indices) {
                val x = padding.left + i * xStep
                val p = max(0f, power[i])
                val y = padding.top + ((yMax - p) / yRange) * chartH
                importPath.lineTo(x, y)
            }
            importPath.lineTo(padding.left + (power.size - 1) * xStep, zeroY)
            importPath.close()

            // Export area (negative power)
            exportPath.moveTo(padding.left, zeroY)
            for (i in power.indices) {
                val x = padding.left + i * xStep
                val p = minOf(0f, power[i])
                val y = padding.top + ((yMax - p) / yRange) * chartH
                exportPath.lineTo(x, y)
            }
            exportPath.lineTo(padding.left + (power.size - 1) * xStep, zeroY)
            exportPath.close()

            // Power line
            for (i in power.indices) {
                val x = padding.left + i * xStep
                val y = padding.top + ((yMax - power[i]) / yRange) * chartH
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }

            canvas.drawPath(importPath, importFillPaint)
            canvas.drawPath(exportPath, exportFillPaint)
            canvas.drawPath(linePath, linePaint)

            // X-axis time labels
            labelPaint.textAlign = Paint.Align.CENTER
            val labelCount = minOf(5, timestamps.size)
            val labelStep = if (labelCount > 1) (timestamps.size - 1) / (labelCount - 1) else 1

            for (i in timestamps.indices step labelStep) {
                val x = padding.left + i * xStep
                val label = timeFormat.format(Date(timestamps[i]))
                canvas.drawText(label, x, h - 4 * dp, labelPaint)
            }
        } else {
            // No data yet — show current time at right edge
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(timeFormat.format(Date()), w - padding.right, h - 4 * dp, labelPaint)
        }

        // Legend
        val legendY = 14f * dp
        val legendX = padding.left
        canvas.drawRect(legendX, legendY - 10 * dp, legendX + 10 * dp, legendY, importLegendPaint)
        legendPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Import", legendX + 14 * dp, legendY, legendPaint)

        val exportLegendX = legendX + 65 * dp
        canvas.drawRect(exportLegendX, legendY - 10 * dp, exportLegendX + 10 * dp, legendY, exportLegendPaint)
        canvas.drawText("Export", exportLegendX + 14 * dp, legendY, legendPaint)
    }

    private fun formatPowerCompact(watts: Float): String {
        val abs = abs(watts)
        return if (abs >= 1000) "${"%.1f".format(watts / 1000)} kW" else "${watts.toInt()} W"
    }

    private data class Padding(val top: Float, val right: Float, val bottom: Float, val left: Float)
}
