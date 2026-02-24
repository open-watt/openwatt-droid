package com.openwatt.droid.ui.config

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.openwatt.droid.R

/**
 * A tiny line chart for showing rate history inline.
 * Draws a filled area under a line path, auto-scaling Y to the data range.
 * Shows a label (current value) at the top and a high-water mark dashed line.
 * Framed with a thin rounded-rect border.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var data: FloatArray = FloatArray(0)
    private var label: String? = null
    private var highWaterMark: Float = Float.NaN

    private val dp = context.resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = ContextCompat.getColor(context, R.color.state_ok)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.state_ok)
        alpha = 30
    }

    private val hwmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.state_ok)
        alpha = 80
        pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8f * context.resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.state_ok)
        alpha = 200
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp * 0.5f
        color = 0x30808080 // light grey, semi-transparent
    }

    private val path = Path()
    private val fillPath = Path()
    private val borderRect = RectF()

    fun setData(values: FloatArray) {
        data = values
        invalidate()
    }

    fun setLabel(text: String?) {
        label = text
        invalidate()
    }

    fun setHighWaterMark(value: Float) {
        highWaterMark = value
        invalidate()
    }

    fun setColor(color: Int) {
        linePaint.color = color
        fillPaint.color = color
        fillPaint.alpha = 30
        hwmPaint.color = color
        hwmPaint.alpha = 80
        labelPaint.color = color
        labelPaint.alpha = 200
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val inset = dp * 1f
        val cornerRadius = dp * 3f

        // Draw border
        borderRect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, borderPaint)

        // Inner area (inset from border)
        val pad = dp * 3f
        val left = inset + pad
        val right = width - inset - pad
        val w = right - left
        val topInset = inset + dp * 1f

        // Reserve space for label at the top
        val graphTop: Float
        if (label != null) {
            val textH = labelPaint.textSize
            graphTop = topInset + textH + dp * 1f
            canvas.drawText(label!!, left, topInset + textH, labelPaint)
        } else {
            graphTop = topInset
        }
        val graphBottom = height - inset - pad
        val h = graphBottom - graphTop

        if (data.size < 2 || w <= 0 || h <= 0) return

        val drawData = data

        // Compute Y range — always include 0 as floor, and HWM as ceiling if set
        var min = 0f
        var max = 0f
        for (v in drawData) {
            if (v > max) max = v
        }
        if (!highWaterMark.isNaN() && highWaterMark > max) {
            max = highWaterMark
        }
        if (max - min < 0.001f) {
            max = 1f
        }
        val range = max - min

        val stepX = w / (drawData.size - 1)

        // Draw high-water mark dashed line
        if (!highWaterMark.isNaN() && highWaterMark > 0) {
            val hwmY = graphBottom - ((highWaterMark - min) / range) * h
            canvas.drawLine(left, hwmY, right, hwmY, hwmPaint)
        }

        // Build line and fill paths
        path.reset()
        fillPath.reset()

        for (i in drawData.indices) {
            val x = left + i * stepX
            val y = graphBottom - ((drawData[i] - min) / range) * h
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, graphBottom)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(left + (drawData.size - 1) * stepX, graphBottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
