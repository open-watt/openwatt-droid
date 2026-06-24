package com.openwatt.droid.ui.energy

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.openwatt.droid.R
import com.openwatt.droid.model.Archetypes
import com.openwatt.droid.model.energy.PowerState
import com.openwatt.droid.model.energy.graph.Forest
import com.openwatt.droid.model.energy.graph.ForestNode
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders the energy render-forest (from buildForest) as a vertical indented
 * list of cards with flow-lines. Each [ForestNode] becomes a card; tree lines
 * on the left show hierarchy and energy flow, with line width proportional to
 * the incoming edge power and colour indicating direction.
 *
 * The forest is already a tree (roots -> children), so this only flattens it
 * with depth/spine context and builds the rows. Mirrors the web flow.js +
 * nodes.js rendering, adapted to the native card-tree style.
 */
class CircuitTreeBuilder(
    private val context: Context,
    private val onNodeClick: (nodeKey: String) -> Unit,
) {
    private val dp = context.resources.displayMetrics.density

    private var selectedNodeKey: String? = null

    fun setSelectedNode(key: String?) {
        selectedNodeKey = key
    }

    // --- Flat tree data model ---

    private data class FlatNode(
        val node: ForestNode,
        val depth: Int,
        val spines: List<TreeLineView.SpineSegment>,
    )

    private data class DepthContext(
        val power: Double,
        val color: Int,
        var continues: Boolean,
    )

    // --- Public entry point ---

    fun buildTree(container: LinearLayout, forest: Forest) {
        container.removeAllViews()
        if (forest.roots.isEmpty()) return

        val flatNodes = mutableListOf<FlatNode>()
        val stack = mutableListOf<DepthContext>()
        for (root in forest.roots) {
            flatten(flatNodes, stack, root, depth = 0)
        }

        val maxPower = flatNodes
            .maxOfOrNull { abs(branchPowerOf(it.node)) }
            ?.coerceAtLeast(100.0) ?: 1000.0

        for (node in flatNodes) {
            container.addView(buildNodeRow(node, maxPower))
        }
    }

    // --- Tree flattening ---

    private fun flatten(
        result: MutableList<FlatNode>,
        stack: MutableList<DepthContext>,
        node: ForestNode,
        depth: Int,
    ) {
        val spines = stack.map { TreeLineView.SpineSegment(it.continues, it.power, it.color) }
        result.add(FlatNode(node, depth, spines))

        val children = node.children
        if (children.isEmpty()) return

        val ctx = DepthContext(
            power = node.power ?: branchPowerOf(node),
            color = stateColor(nodeState(node).stateClass),
            continues = true,
        )
        stack.add(ctx)
        for ((i, child) in children.withIndex()) {
            ctx.continues = i < children.size - 1
            flatten(result, stack, child, depth + 1)
        }
        stack.removeAt(stack.lastIndex)
    }

    // --- View building ---

    private fun buildNodeRow(flat: FlatNode, maxPower: Double): View {
        val node = flat.node
        val isSelected = node.key == selectedNodeKey
        val state = nodeState(node)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Tree/flow line area (drawn for non-root rows)
        if (flat.depth > 0) {
            row.addView(TreeLineView(context).apply {
                nodeDepth = flat.depth
                spines = flat.spines
                branchPower = branchPowerOf(node)
                branchColor = stateColor(EnergyFormatters.getFlowClass(node.edgePower ?: node.power ?: 0.0))
                this.maxPower = maxPower
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
            })
        }

        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply {
                topMargin = (3 * dp).toInt()
                bottomMargin = (3 * dp).toInt()
                if (flat.depth == 0) marginStart = (4 * dp).toInt()
                marginEnd = (4 * dp).toInt()
            }
            radius = 10 * dp
            cardElevation = if (isSelected) 4 * dp else 1 * dp
            strokeWidth = if (isSelected) (2 * dp).toInt() else 0
            strokeColor = if (isSelected) 0xFF2563EB.toInt() else 0
            setContentPadding(
                (10 * dp).toInt(), (6 * dp).toInt(),
                (10 * dp).toInt(), (6 * dp).toInt(),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onNodeClick(node.key) }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // Main row: icon + name (+ flags) + power + state
        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        mainRow.addView(TextView(context).apply {
            text = nodeIcon(node)
            textSize = 18f
        })

        mainRow.addView(TextView(context).apply {
            text = node.name + nodeFlags(node)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply {
                marginStart = (6 * dp).toInt()
                marginEnd = (6 * dp).toInt()
            }
            maxLines = 1
        })

        mainRow.addView(TextView(context).apply {
            text = if (node.power == null) "--" else "${state.arrow} ${EnergyFormatters.formatPower(abs(node.power!!))}"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(stateColor(state.stateClass))
        })

        mainRow.addView(TextView(context).apply {
            text = state.label
            textSize = 9f
            setTextColor(0xFF94A3B8.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (4 * dp).toInt() }
        })

        content.addView(mainRow)

        // Inline solar/battery badges
        nodeBadges(node)?.let { content.addView(it) }

        // Breaker loading caption (edge current vs capacity)
        breakerLoadCaption(node)?.let { content.addView(it) }

        card.addView(content)
        row.addView(card)
        return row
    }

    /** Inline ☀️ solar-output / 🔋 battery-SOC badges, like the web renderNodeBadges. */
    private fun nodeBadges(node: ForestNode): View? {
        val parts = mutableListOf<String>()
        for (p in node.production) {
            val power = p.aggregatePower ?: p.memberPower ?: p.meter.power
            if (power != null) parts.add("☀️ ${EnergyFormatters.formatPower(abs(power))}")
        }
        node.batteryStore?.soc?.let { soc ->
            val pct = soc.coerceIn(0.0, 100.0).roundToInt()
            parts.add("🔋 $pct%")
        }
        if (parts.isEmpty()) return null
        return TextView(context).apply {
            text = parts.joinToString("   ")
            textSize = 11f
            setTextColor(0xFF475569.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (2 * dp).toInt() }
        }
    }

    /** "50.4 / 63 A (80%)" loading caption when the incoming edge is a rated breaker. */
    private fun breakerLoadCaption(node: ForestNode): View? {
        val cap = node.edgeCapacity ?: return null
        val cur = node.edgeCurrent ?: return null
        if (cap <= 0) return null
        val pct = min(100.0, abs(cur) / cap * 100.0)
        val color = when {
            pct >= 100 -> ContextCompat.getColor(context, R.color.state_error)
            pct >= 80 -> 0xFFF59E0B.toInt()
            else -> 0xFF94A3B8.toInt()
        }
        return TextView(context).apply {
            text = "${"%.1f".format(abs(cur))} / ${cap.roundToInt()} A (${pct.roundToInt()}%)"
            textSize = 9f
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (1 * dp).toInt() }
        }
    }

    // --- Node helpers ---

    private fun nodeState(node: ForestNode): PowerState {
        val nodeType = if (node.kind == "appliance") "appliance" else "circuit"
        val meterType = EnergyFormatters.meterTypeOf(node.circuitType)
        return EnergyFormatters.getPowerState(node.power, meterType, node.circuitType, nodeType)
    }

    private fun nodeIcon(node: ForestNode): String = when (node.kind) {
        "appliance" -> Archetypes.resolve(node.applianceType ?: "").icon
        "residual" -> "❓" // ❓
        "port" -> portRoleIcon(node.port?.portRole)
        else -> EnergyFormatters.getMeterIcon(EnergyFormatters.meterTypeOf(node.circuitType), node.circuitType)
    }

    /** Short confidence/island flags appended to a bus node's name. */
    private fun nodeFlags(node: ForestNode): String {
        if (node.kind != "bus") return ""
        val bus = node.bus ?: return ""
        val flags = mutableListOf<String>()
        bus.darkPorts?.let { if (it > 0) flags.add("${it}d") }
        if (bus.anomaly) flags.add("⚠")
        if (node.isRoot && node.islandMode != null && node.islandMode != "unknown") {
            flags.add(if (node.islandMode == "on_grid") "⚡" else "🔋")
        }
        return if (flags.isEmpty()) "" else "  ${flags.joinToString(" ")}"
    }

    private fun portRoleIcon(role: String?): String = when (role) {
        "battery" -> "🔋"
        "pv", "solar" -> "☀️"
        "car" -> "🚗"
        "grid" -> "🏭"
        "backup" -> "🛟"
        "outlet" -> "🔌"
        else -> "🔌"
    }

    /** Throughput on the line into a node — its incoming edge power, else its own. */
    private fun branchPowerOf(node: ForestNode): Double = node.edgePower ?: node.power ?: 0.0

    private fun stateColor(stateClass: String): Int {
        return when (stateClass) {
            "consuming" -> ContextCompat.getColor(context, R.color.state_error)
            "producing" -> ContextCompat.getColor(context, R.color.state_ok)
            else -> ContextCompat.getColor(context, R.color.state_idle)
        }
    }
}
