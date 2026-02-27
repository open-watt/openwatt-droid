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
import com.openwatt.droid.model.energy.Appliance
import com.openwatt.droid.model.energy.Circuit
import com.openwatt.droid.model.energy.MeterType
import com.openwatt.droid.model.energy.PowerState
import kotlin.math.abs

/**
 * Builds the circuit tree as a vertical indented list with flow-lines.
 * Each node is a card; tree lines on the left show hierarchy and energy flow,
 * with line width proportional to power magnitude and color indicating direction.
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
        val nodeKey: String,
        val icon: String,
        val name: String,
        val power: Double,
        val state: PowerState,
        val badge: String?,
        val depth: Int,
        val spines: List<TreeLineView.SpineSegment>,
        val appliance: Appliance?,
        val allAppliances: Map<String, Appliance>?,
    )

    private data class DepthContext(
        val power: Double,
        val color: Int,
        var continues: Boolean,
    )

    // --- Public entry point ---

    fun buildTree(
        container: LinearLayout,
        circuits: Map<String, Circuit>,
        appliances: Map<String, Appliance>,
    ) {
        container.removeAllViews()
        if (circuits.isEmpty()) return

        val flatNodes = mutableListOf<FlatNode>()
        val stack = mutableListOf<DepthContext>()

        val entries = circuits.entries.toList()
        for ((i, entry) in entries.withIndex()) {
            val (id, circuit) = entry
            flattenCircuit(flatNodes, stack, id, circuit, appliances, depth = 0)
        }

        val maxPower = flatNodes.maxOfOrNull { abs(it.power) }?.coerceAtLeast(100.0) ?: 1000.0

        for (node in flatNodes) {
            container.addView(buildNodeRow(node, maxPower))
        }
    }

    // --- Tree flattening ---

    private fun flattenCircuit(
        result: MutableList<FlatNode>,
        stack: MutableList<DepthContext>,
        id: String,
        circuit: Circuit,
        appliances: Map<String, Appliance>,
        depth: Int,
    ) {
        val nodeKey = "circuit:$id"
        val meterType = circuit.meterData?.inferMeterType() ?: MeterType.SINGLE_PHASE
        val icon = EnergyFormatters.getMeterIcon(meterType, circuit.type)
        val name = circuit.name ?: id
        val power = circuit.meterData?.powerWatts ?: 0.0
        val state = EnergyFormatters.getPowerState(power, meterType, circuit.type, "circuit")

        val spines = stack.map { TreeLineView.SpineSegment(it.continues, it.power, it.color) }

        result.add(FlatNode(
            nodeKey = nodeKey, icon = icon, name = name, power = power, state = state,
            badge = circuit.maxCurrent?.let { "${it}A" },
            depth = depth, spines = spines,
            appliance = null, allAppliances = null,
        ))

        // Collect children
        val childAppliances = circuit.appliances.mapNotNull { appId ->
            appliances[appId]?.let { appId to it }
        }.filter { it.second.type != "car" }
        val childCircuits = circuit.subCircuits.entries.toList()
        val totalChildren = childAppliances.size + childCircuits.size
        if (totalChildren == 0) return

        val ctx = DepthContext(power = power, color = stateColor(state.stateClass), continues = true)
        stack.add(ctx)

        var childIndex = 0
        for ((appId, appliance) in childAppliances) {
            ctx.continues = childIndex < totalChildren - 1
            flattenAppliance(result, stack, appId, appliance, appliances, depth + 1)
            childIndex++
        }
        for ((subId, subCircuit) in childCircuits) {
            ctx.continues = childIndex < totalChildren - 1
            flattenCircuit(result, stack, subId, subCircuit, appliances, depth + 1)
            childIndex++
        }

        stack.removeAt(stack.lastIndex)
    }

    private fun flattenAppliance(
        result: MutableList<FlatNode>,
        stack: MutableList<DepthContext>,
        id: String,
        appliance: Appliance,
        allAppliances: Map<String, Appliance>,
        depth: Int,
    ) {
        val nodeKey = "appliance:$id"
        val icon = Archetypes.resolve(appliance.type).icon
        val name = appliance.name ?: id
        val power = appliance.meterData?.powerWatts ?: 0.0
        val meterType = appliance.meterData?.inferMeterType() ?: MeterType.SINGLE_PHASE
        val state = EnergyFormatters.getPowerState(power, meterType, null, "appliance")

        val spines = stack.map { TreeLineView.SpineSegment(it.continues, it.power, it.color) }

        result.add(FlatNode(
            nodeKey = nodeKey, icon = icon, name = name, power = power, state = state,
            badge = if (!appliance.enabled) "OFF" else null,
            depth = depth, spines = spines,
            appliance = appliance, allAppliances = allAppliances,
        ))
    }

    // --- View building ---

    private fun buildNodeRow(node: FlatNode, maxPower: Double): View {
        val isSelected = node.nodeKey == selectedNodeKey

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Tree/flow line area
        if (node.depth > 0) {
            row.addView(TreeLineView(context).apply {
                nodeDepth = node.depth
                spines = node.spines
                branchPower = node.power
                branchColor = stateColor(node.state.stateClass)
                this.maxPower = maxPower
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
            })
        }

        // Node card
        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply {
                topMargin = (3 * dp).toInt()
                bottomMargin = (3 * dp).toInt()
                if (node.depth == 0) marginStart = (4 * dp).toInt()
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
            setOnClickListener { onNodeClick(node.nodeKey) }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // Main row: icon + name + power + state
        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        mainRow.addView(TextView(context).apply {
            text = node.icon
            textSize = 18f
        })

        mainRow.addView(TextView(context).apply {
            text = node.name
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
            text = "${node.state.arrow} ${EnergyFormatters.formatPower(abs(node.power))}"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(stateColor(node.state.stateClass))
        })

        mainRow.addView(TextView(context).apply {
            text = node.state.label
            textSize = 9f
            setTextColor(0xFF94A3B8.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (4 * dp).toInt() }
        })

        content.addView(mainRow)

        // Badge (max current or disabled)
        node.badge?.let { b ->
            content.addView(TextView(context).apply {
                text = b
                textSize = 9f
                setTextColor(0xFF64748B.toInt())
                setBackgroundColor(0xFFF1F5F9.toInt())
                setPadding(
                    (4 * dp).toInt(), (1 * dp).toInt(),
                    (4 * dp).toInt(), (1 * dp).toInt(),
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (2 * dp).toInt() }
            })
        }

        // Subcontent for inverter/EVSE appliances
        if (node.appliance != null && node.allAppliances != null) {
            buildApplianceSubcontent(node.appliance, node.allAppliances)?.let {
                content.addView(it)
            }
        }

        card.addView(content)
        row.addView(card)
        return row
    }

    // --- Appliance subcontent ---

    private fun buildApplianceSubcontent(
        appliance: Appliance,
        allAppliances: Map<String, Appliance>,
    ): LinearLayout? {
        val hasInverter = appliance.inverter != null && appliance.inverter.mppt.isNotEmpty()
        val hasEvse = appliance.evse?.connectedCar != null
        if (!hasInverter && !hasEvse) return null

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        content.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt(),
            ).apply { topMargin = (4 * dp).toInt() }
            setBackgroundColor(0xFFE2E8F0.toInt())
        })

        if (hasInverter) {
            val inverter = appliance.inverter!!
            for (mppt in inverter.mppt.filter { it.isBattery }) {
                val power = mppt.meterData?.powerWatts ?: 0.0
                content.addView(buildSubRow(
                    icon = "\uD83D\uDD0B",
                    label = "Battery${mppt.soc?.let { " ${it.toInt()}%" } ?: ""}",
                    value = "${EnergyFormatters.formatPower(abs(power))} ${EnergyFormatters.getFlowArrow(power)}",
                    powerClass = EnergyFormatters.getFlowClass(power),
                ))
            }
            val solars = inverter.mppt.filter { it.isSolar }
            if (solars.isNotEmpty()) {
                val totalSolarPower = solars.sumOf { it.meterData?.powerWatts ?: 0.0 }
                content.addView(buildSubRow(
                    icon = "\u2600\uFE0F",
                    label = "Solar",
                    value = "${EnergyFormatters.formatPower(abs(totalSolarPower))} ${EnergyFormatters.getFlowArrow(-totalSolarPower)}",
                    powerClass = if (totalSolarPower > 10) "producing" else "idle",
                ))
            }
        }

        if (hasEvse) {
            val carId = appliance.evse!!.connectedCar!!
            val car = allAppliances[carId]
            content.addView(buildSubRow(
                icon = "\uD83D\uDE97",
                label = car?.name ?: carId,
                value = car?.meterData?.powerWatts?.let { EnergyFormatters.formatPower(abs(it)) } ?: "",
                powerClass = "consuming",
            ))
        }

        return content
    }

    private fun buildSubRow(icon: String, label: String, value: String, powerClass: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (3 * dp).toInt() }

            addView(TextView(context).apply { text = icon; textSize = 12f })

            addView(TextView(context).apply {
                text = label; textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { marginStart = (4 * dp).toInt() }
            })

            addView(TextView(context).apply {
                text = value; textSize = 11f
                setTextColor(stateColor(powerClass))
            })
        }
    }

    // --- Utilities ---

    private fun stateColor(stateClass: String): Int {
        return when (stateClass) {
            "consuming" -> ContextCompat.getColor(context, R.color.state_error)
            "producing" -> ContextCompat.getColor(context, R.color.state_ok)
            else -> ContextCompat.getColor(context, R.color.state_idle)
        }
    }
}
