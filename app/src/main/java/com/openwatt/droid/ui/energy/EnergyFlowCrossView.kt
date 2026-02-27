package com.openwatt.droid.ui.energy

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.openwatt.droid.model.energy.EnergyFlowState
import kotlin.math.abs
import kotlin.math.sqrt

class EnergyFlowCrossView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val dp = context.resources.displayMetrics.density
    private val sp = context.resources.displayMetrics.scaledDensity

    private val POWER_DEAD_BAND = 10.0
    private val NODE_CORNER = 12f * dp
    private val NODE_PAD = 8f * dp
    private val MIN_LINE_WIDTH = 1.5f * dp
    private val MAX_LINE_WIDTH = 5f * dp
    private val MIN_DOT_RADIUS = 2f * dp
    private val MAX_DOT_RADIUS = 4.5f * dp
    private val DOT_SPACING = 18f * dp
    private val VIEW_PADDING = 8f * dp

    // Colors
    private val COLOR_CONSUMING = 0xFFDC2626.toInt()  // red — importing, charging, consuming
    private val COLOR_PRODUCING = 0xFF16A34A.toInt()   // green — producing, exporting, discharging
    private val COLOR_IDLE = 0xFF94A3B8.toInt()
    private val COLOR_NODE_BG = 0xFFF8FAFC.toInt()
    private val COLOR_NODE_STROKE = 0xFFE2E8F0.toInt()
    private val COLOR_TEXT_SECONDARY = 0xFF64748B.toInt()

    // Paints
    private val nodeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NODE_BG; style = Paint.Style.FILL
    }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NODE_STROKE; style = Paint.Style.STROKE; strokeWidth = 1f * dp
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f * sp; textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * sp; textAlign = Paint.Align.CENTER; color = COLOR_TEXT_SECONDARY
    }
    private val powerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * sp; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * sp; textAlign = Paint.Align.CENTER; color = COLOR_TEXT_SECONDARY
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * sp; textAlign = Paint.Align.CENTER; color = COLOR_TEXT_SECONDARY
    }

    // Animation
    private var flowPhase = 0f
    private val flowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            flowPhase = it.animatedValue as Float
            invalidate()
        }
    }

    // State
    private var state: EnergyFlowState? = null
    private var emptyMessage: String? = null

    // Computed layout
    private data class FlowNode(
        val rect: RectF,
        val icon: String,
        val label: String,
        val powerText: String,
        val powerColor: Int,
        val subLines: List<SubLine> = emptyList(),
    )

    private data class SubLine(val icon: String, val text: String, val color: Int)

    /**
     * A connection between two points.
     * from→to defines the segment direction.
     * dotsForward=true means dots travel from→to (energy flows that way).
     */
    private data class FlowConnection(
        val from: PointF,
        val to: PointF,
        val power: Double,
        val color: Int,
        val dotsForward: Boolean,
        val lineWidth: Float,
        val dotRadius: Float,
    )

    private val nodes = mutableMapOf<String, FlowNode>()
    private val connections = mutableListOf<FlowConnection>()
    private var maxPower = 1.0 // for scaling

    // --- Public API ---

    fun setState(newState: EnergyFlowState?) {
        state = newState
        emptyMessage = null
        computeLayout()
        invalidate()
        if (newState != null && !flowAnimator.isRunning) flowAnimator.start()
        if (newState == null && flowAnimator.isRunning) flowAnimator.cancel()
    }

    fun setEmptyMessage(message: String) {
        state = null
        emptyMessage = message
        nodes.clear()
        connections.clear()
        invalidate()
        if (flowAnimator.isRunning) flowAnimator.cancel()
    }

    // --- Measure ---

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = (w * 0.70f).toInt()
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    // --- Layout ---

    private fun computeLayout() {
        nodes.clear()
        connections.clear()
        val s = state ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val hasSolar = s.solarPower != null
        val hasBattery = s.batteryPower != null
        val hasGas = s.gasPower != null
        val hasEvs = s.evs != null && s.evs.isNotEmpty()

        val pad = VIEW_PADDING
        val availW = w - 2 * pad
        val availH = h - 2 * pad

        // Column centers
        val col0W = availW * 0.25f
        val col1W = availW * 0.30f
        val col0Cx = pad + col0W / 2
        val col1Cx = pad + col0W + col1W / 2
        val col2Cx = pad + col0W + col1W + (availW * 0.45f) / 2

        // Row positions
        val centerY = pad + availH / 2
        val topY = centerY - availH * 0.30f
        val bottomY = centerY + availH * 0.30f

        val nodeW = 88f * dp
        val nodeH = 56f * dp

        // Crossing point: where horizontal bus meets vertical axis
        val crossPt = PointF(col1Cx, centerY)

        // Find max power for scaling line thickness
        maxPower = listOfNotNull(
            abs(s.gridPower),
            s.solarPower?.let { abs(it) },
            s.batteryPower?.let { abs(it) },
            abs(s.homePower),
            s.evs?.sumOf { abs(it.power) },
            s.gasPower?.let { abs(it) },
        ).maxOrNull()?.coerceAtLeast(100.0) ?: 100.0

        // ---- Build nodes ----

        val gridRect = centeredRect(col0Cx, centerY, nodeW, nodeH)
        nodes["grid"] = buildNode(gridRect, "\u26A1", "Grid", s.gridPower, "grid")

        val homeRect = centeredRect(col2Cx, centerY, nodeW, nodeH)
        nodes["home"] = buildNode(homeRect, "\uD83C\uDFE0", "Home", s.homePower, "home")

        if (hasSolar) {
            val solarRect = centeredRect(col1Cx, topY, nodeW, nodeH)
            nodes["solar"] = buildNode(solarRect, "\u2600\uFE0F", "Solar", s.solarPower!!, "solar")
        }

        if (hasBattery) {
            val batH = nodeH + (if (s.batterySoc != null) 14f * dp else 0f)
            val batRect = centeredRect(col1Cx, bottomY, nodeW, batH)
            nodes["battery"] = buildNode(
                batRect, "\uD83D\uDD0B", "Battery", s.batteryPower!!, "battery",
                subLines = if (s.batterySoc != null) listOf(
                    SubLine("", "${s.batterySoc.toInt()}%", COLOR_TEXT_SECONDARY)
                ) else emptyList()
            )
        }

        if (hasGas) {
            val gasRect = centeredRect(col2Cx, topY, nodeW, nodeH)
            nodes["gas"] = buildNode(gasRect, "\uD83D\uDD25", "Gas", s.gasPower!!, "gas")
        }

        if (hasEvs) {
            val extraH = (s.evs!!.size - 1).coerceAtLeast(0) * 16f * dp
            val evRect = centeredRect(col2Cx, bottomY, nodeW, nodeH + extraH)
            val totalEvPower = s.evs.sumOf { it.power }

            val subLines = if (s.evs.size > 1) {
                s.evs.map { ev ->
                    val evPowerText = EnergyFormatters.formatPower(ev.power)
                    val socText = ev.soc?.let { " ${it.toInt()}%" } ?: ""
                    SubLine("\uD83D\uDE97", "${ev.name}$socText $evPowerText", nodeColor(ev.power, "evs"))
                }
            } else {
                val ev = s.evs[0]
                if (ev.soc != null) listOf(SubLine("", "${ev.soc.toInt()}%", COLOR_TEXT_SECONDARY))
                else emptyList()
            }

            nodes["evs"] = FlowNode(
                rect = evRect,
                icon = "\uD83D\uDE97",
                label = if (s.evs.size == 1) s.evs[0].name else "EVs",
                powerText = EnergyFormatters.formatPower(totalEvPower),
                powerColor = nodeColor(totalEvPower, "evs"),
                subLines = subLines,
            )
        }

        // ---- Build connections as explicit directed segments ----

        val gridRight = PointF(gridRect.right, gridRect.centerY())
        val homeLeft = PointF(homeRect.left, homeRect.centerY())

        // Horizontal bus segment 1: Grid → crossing point
        // Grid importing (+ive): dots flow right (grid→cross). Exporting (-ive): dots flow left.
        addConnection(
            from = gridRight, to = crossPt,
            power = s.gridPower,
            color = nodeColor(s.gridPower, "grid"),
            dotsForward = s.gridPower > POWER_DEAD_BAND,
        )

        // Horizontal bus segment 2: crossing point → Home
        // Home consuming (+ive): dots flow right (cross→home).
        addConnection(
            from = crossPt, to = homeLeft,
            power = s.homePower,
            color = nodeColor(s.homePower, "home"),
            dotsForward = s.homePower > POWER_DEAD_BAND,
        )

        // Vertical axis: Solar → crossing point
        if (hasSolar) {
            val solarBottom = PointF(col1Cx, nodes["solar"]!!.rect.bottom)
            // Solar producing (+ive): dots flow down (solar→cross) = forward
            addConnection(
                from = solarBottom, to = crossPt,
                power = s.solarPower!!,
                color = nodeColor(s.solarPower, "solar"),
                dotsForward = s.solarPower > POWER_DEAD_BAND,
            )
        }

        // Vertical axis: crossing point → Battery
        if (hasBattery) {
            val batteryTop = PointF(col1Cx, nodes["battery"]!!.rect.top)
            // Battery discharging (+ive): energy flows up (battery→cross) = BACKWARD
            // Battery charging (-ive): energy flows down (cross→battery) = FORWARD
            addConnection(
                from = crossPt, to = batteryTop,
                power = s.batteryPower!!,
                color = nodeColor(s.batteryPower, "battery"),
                dotsForward = s.batteryPower < -POWER_DEAD_BAND,
            )
        }

        // Right column: Gas → Home (vertical)
        if (hasGas) {
            val gasBottom = PointF(col2Cx, nodes["gas"]!!.rect.bottom)
            val homeTop = PointF(col2Cx, homeRect.top)
            // Gas consumed (+ive): dots flow down (gas→home) = forward
            addConnection(
                from = gasBottom, to = homeTop,
                power = s.gasPower!!,
                color = nodeColor(s.gasPower, "gas"),
                dotsForward = s.gasPower > POWER_DEAD_BAND,
            )
        }

        // Right column: Home → EVs (vertical)
        if (hasEvs) {
            val homeBottom = PointF(col2Cx, homeRect.bottom)
            val evsTop = PointF(col2Cx, nodes["evs"]!!.rect.top)
            val totalEvPower = s.evs!!.sumOf { it.power }
            // EV charging (+ive): dots flow down (home→EV) = forward
            // EV V2G (-ive): dots flow up (EV→home) = backward
            addConnection(
                from = homeBottom, to = evsTop,
                power = totalEvPower,
                color = nodeColor(totalEvPower, "evs"),
                dotsForward = totalEvPower > POWER_DEAD_BAND,
            )
        }
    }

    private fun centeredRect(cx: Float, cy: Float, w: Float, h: Float): RectF {
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    private fun buildNode(
        rect: RectF,
        icon: String,
        label: String,
        power: Double,
        nodeType: String,
        subLines: List<SubLine> = emptyList(),
    ): FlowNode {
        return FlowNode(
            rect = rect,
            icon = icon,
            label = label,
            powerText = EnergyFormatters.formatPower(power),
            powerColor = nodeColor(power, nodeType),
            subLines = subLines,
        )
    }

    private fun addConnection(
        from: PointF,
        to: PointF,
        power: Double,
        color: Int,
        dotsForward: Boolean,
    ) {
        val ratio = (abs(power).toFloat() / maxPower.toFloat()).coerceIn(0f, 1f)
        connections.add(
            FlowConnection(
                from = PointF(from.x, from.y),
                to = PointF(to.x, to.y),
                power = power,
                color = color,
                dotsForward = dotsForward,
                lineWidth = MIN_LINE_WIDTH + ratio * (MAX_LINE_WIDTH - MIN_LINE_WIDTH),
                dotRadius = MIN_DOT_RADIUS + ratio * (MAX_DOT_RADIUS - MIN_DOT_RADIUS),
            )
        )
    }

    /** Color based on what the power means for this node type. */
    private fun nodeColor(power: Double, nodeType: String): Int {
        val absPower = abs(power)
        if (absPower <= POWER_DEAD_BAND) return COLOR_IDLE

        return when (nodeType) {
            // Grid: +ive = importing (red), -ive = exporting (green)
            "grid" -> if (power > 0) COLOR_CONSUMING else COLOR_PRODUCING
            // Solar: +ive = producing (green)
            "solar" -> COLOR_PRODUCING
            // Battery: +ive = discharging (green), -ive = charging (red)
            "battery" -> if (power > 0) COLOR_PRODUCING else COLOR_CONSUMING
            // Home: +ive = consuming (red)
            "home" -> if (power > 0) COLOR_CONSUMING else COLOR_PRODUCING
            // Gas: always consumed (red)
            "gas" -> COLOR_CONSUMING
            // EVs: +ive = charging (red), -ive = V2G (green)
            "evs" -> if (power > 0) COLOR_CONSUMING else COLOR_PRODUCING
            else -> COLOR_IDLE
        }
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (state == null) {
            emptyMessage?.let {
                canvas.drawText(it, width / 2f, height / 2f, emptyPaint)
            }
            return
        }

        // 1. Draw connections (behind nodes)
        for (conn in connections) {
            drawConnection(canvas, conn)
        }

        // 2. Draw nodes (on top)
        for ((_, node) in nodes) {
            drawNode(canvas, node)
        }
    }

    private fun drawConnection(canvas: Canvas, conn: FlowConnection) {
        val isIdle = abs(conn.power) <= POWER_DEAD_BAND
        val dx = conn.to.x - conn.from.x
        val dy = conn.to.y - conn.from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1f) return

        // Base line (always drawn)
        linePaint.color = if (isIdle) COLOR_IDLE else conn.color
        linePaint.alpha = if (isIdle) 40 else 35
        linePaint.strokeWidth = conn.lineWidth
        canvas.drawLine(conn.from.x, conn.from.y, conn.to.x, conn.to.y, linePaint)
        linePaint.alpha = 255

        if (isIdle) return

        // Animated dots — more dots for higher power (denser)
        val spacing = DOT_SPACING
        val numDots = (length / spacing).toInt().coerceAtLeast(2)
        dotPaint.color = conn.color

        for (i in 0..numDots) {
            var t = (i.toFloat() / numDots + flowPhase) % 1f
            if (!conn.dotsForward) t = 1f - t

            val x = conn.from.x + dx * t
            val y = conn.from.y + dy * t

            // Fade dots near segment endpoints so they don't pop in/out
            val edgeFade = minOf(t, 1f - t) * 4f  // fade over first/last 25%
            val alpha = (edgeFade.coerceIn(0f, 1f) * 230).toInt() + 25
            dotPaint.alpha = alpha.coerceIn(0, 255)

            canvas.drawCircle(x, y, conn.dotRadius, dotPaint)
        }
        dotPaint.alpha = 255
    }

    private fun drawNode(canvas: Canvas, node: FlowNode) {
        val r = node.rect

        // Background + stroke
        canvas.drawRoundRect(r, NODE_CORNER, NODE_CORNER, nodeBgPaint)
        canvas.drawRoundRect(r, NODE_CORNER, NODE_CORNER, nodeStrokePaint)

        // Layout text vertically within the node
        var y = r.top + NODE_PAD

        // Icon
        y += 16f * sp
        canvas.drawText(node.icon, r.centerX(), y, iconPaint)

        // Label
        y += 14f * sp
        canvas.drawText(node.label, r.centerX(), y, labelPaint)

        // Power
        y += 15f * sp
        powerPaint.color = node.powerColor
        canvas.drawText(node.powerText, r.centerX(), y, powerPaint)

        // Sub-lines (SoC, individual EVs)
        for (sub in node.subLines) {
            y += 13f * sp
            subTextPaint.color = sub.color
            val text = if (sub.icon.isNotEmpty()) "${sub.icon} ${sub.text}" else sub.text
            canvas.drawText(text, r.centerX(), y, subTextPaint)
        }
    }

    // --- Lifecycle ---

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (state != null && !flowAnimator.isRunning) flowAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        flowAnimator.cancel()
    }
}
