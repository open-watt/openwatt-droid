package com.openwatt.droid.ui.energy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.abs

/**
 * Custom View that draws tree-line/flow-line segments in the indent area.
 * Line width is proportional to power magnitude, color indicates flow direction.
 *
 * For a node at depth N, this view draws N columns:
 * - Columns 0..N-2: pass-through vertical lines (if ancestor has more siblings)
 * - Column N-1: junction — L-shape (last child) or T-shape (more siblings below)
 *   plus a horizontal branch to the right edge connecting to the node card
 */
class TreeLineView(context: Context) : View(context) {

    private val dp = context.resources.displayMetrics.density

    val colWidth = 28f * dp
    private val minStroke = 2.5f * dp
    private val maxStroke = 12f * dp

    var nodeDepth: Int = 0
    var spines: List<SpineSegment> = emptyList()
    var branchPower: Double = 0.0
    var branchColor: Int = IDLE_COLOR
    var maxPower: Double = 1000.0

    data class SpineSegment(
        val continues: Boolean,
        val power: Double,
        val color: Int,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((nodeDepth * colWidth).toInt(), widthMeasureSpec)
        val h = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (nodeDepth == 0 || spines.isEmpty()) return

        val h = height.toFloat()
        val midY = h / 2f

        for (d in spines.indices) {
            val spine = spines[d]
            val cx = (d + 0.5f) * colWidth

            if (d < nodeDepth - 1) {
                // Pass-through: full-height vertical if ancestor spine continues
                if (spine.continues) {
                    val sw = strokeWidth(spine.power)
                    paint.color = spine.color
                    canvas.drawRect(cx - sw / 2, 0f, cx + sw / 2, h, paint)
                }
            } else {
                // Junction depth — L or T shape
                val sw = strokeWidth(spine.power)
                paint.color = spine.color

                // Vertical: always top→midY, also midY→bottom if continues
                canvas.drawRect(cx - sw / 2, 0f, cx + sw / 2, midY, paint)
                if (spine.continues) {
                    canvas.drawRect(cx - sw / 2, midY, cx + sw / 2, h, paint)
                }

                // Horizontal branch to node card
                val bw = strokeWidth(branchPower)
                paint.color = branchColor
                canvas.drawRect(cx, midY - bw / 2, width.toFloat(), midY + bw / 2, paint)
            }
        }
    }

    private fun strokeWidth(power: Double): Float {
        if (maxPower <= 0) return minStroke
        val ratio = (abs(power).toFloat() / maxPower.toFloat()).coerceIn(0f, 1f)
        return minStroke + ratio * (maxStroke - minStroke)
    }

    companion object {
        const val IDLE_COLOR = 0xFF94A3B8.toInt()
    }
}
