package com.openwatt.droid.ui.energy

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.openwatt.droid.R
import com.openwatt.droid.model.Archetypes
import com.openwatt.droid.model.energy.DetailLevel
import com.openwatt.droid.model.energy.MeterType
import com.openwatt.droid.model.energy.graph.BatteryStore
import com.openwatt.droid.model.energy.graph.ForestNode
import com.openwatt.droid.model.energy.graph.Meter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds the detail-panel content for a selected render-forest node (bus,
 * appliance, port or residual). Supports progressive disclosure (SIMPLE ->
 * DETAIL -> ADVANCED). Mirrors the web details.js / meters.js renderers,
 * adapted to the scalar circuit.* meter model.
 */
class MeterDetailView(
    private val context: Context,
    private val onDetailMore: () -> Unit,
    private val onDetailLess: () -> Unit,
    private val onDetailBack: () -> Unit,
) {
    private val dp = context.resources.displayMetrics.density

    fun buildDetail(container: LinearLayout, node: ForestNode, detailLevel: DetailLevel) {
        container.removeAllViews()

        val typeLabel = when (node.kind) {
            "appliance" -> (node.applianceType ?: "Appliance").replaceFirstChar { it.uppercase() }
            "bus" -> "Circuit"
            "port" -> "Port"
            "residual" -> "Unaccounted"
            else -> ""
        }
        addHeader(container, nodeIcon(node), node.name, typeLabel, node.maxCurrent?.let { "${it.roundToInt()}A" })
        if (node.open) addBadge(container, "OPEN", 0xFFF59E0B.toInt())

        val meterType = EnergyFormatters.meterTypeOf(node.circuitType)
        val nodeType = if (node.kind == "appliance") "appliance" else "circuit"
        addMeterDisplay(container, node.meter, node.power, detailLevel, node.circuitType, nodeType)

        when (node.kind) {
            "appliance" -> {
                renderProduction(container, node)
                node.batteryStore?.let { renderBattery(container, it) }
                renderPorts(container, node)
            }
            "bus" -> {
                renderBusInfo(container, node)
                node.batteryStore?.let { renderBattery(container, it) }
            }
        }

        val m = node.meter ?: Meter()
        addDetailButtons(container, detailLevel, hasDetailData(m), hasAdvancedData(m, meterType))
    }

    // --- meter display ---

    private fun addMeterDisplay(
        container: LinearLayout,
        meter: Meter?,
        power: Double?,
        detailLevel: DetailLevel,
        circuitType: String?,
        nodeType: String,
    ) {
        val m = meter ?: Meter()
        val meterType = EnergyFormatters.meterTypeOf(circuitType)
        val state = EnergyFormatters.getPowerState(power, meterType, circuitType, nodeType)

        val powerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (6 * dp).toInt() }
        }
        powerRow.addView(TextView(context).apply {
            text = if (power == null) "--" else "${state.arrow} ${EnergyFormatters.formatPower(abs(power))}"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(stateColor(state.stateClass))
        })
        powerRow.addView(TextView(context).apply {
            text = state.label
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (8 * dp).toInt() }
        })
        container.addView(powerRow)

        if (detailLevel >= DetailLevel.DETAIL) {
            m.voltage?.let { addInfoRow(container, "Voltage", EnergyFormatters.formatValue(it, "V")) }
            m.current?.let { addInfoRow(container, "Current", EnergyFormatters.formatValue(it, "A")) }
        }
        if (detailLevel >= DetailLevel.ADVANCED && meterType != MeterType.DC) {
            m.frequency?.let { addInfoRow(container, "Frequency", EnergyFormatters.formatValue(it, "Hz")) }
            m.pf?.let { addInfoRow(container, "Power Factor", EnergyFormatters.formatPowerFactor(it)) }
            m.apparent?.let { addInfoRow(container, "Apparent", EnergyFormatters.formatValue(it, "VA")) }
            m.reactive?.let { addInfoRow(container, "Reactive", EnergyFormatters.formatValue(it, "var")) }
        }

        // Today's energy (the circuit.* model reports import/export in kWh)
        if (m.import != null || m.export != null) {
            val energyRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (4 * dp).toInt() }
            }
            m.import?.let {
                energyRow.addView(TextView(context).apply {
                    text = "${EnergyFormatters.formatEnergyKWh(it)} ↓"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.state_error))
                })
            }
            m.export?.let {
                energyRow.addView(TextView(context).apply {
                    text = "  ${EnergyFormatters.formatEnergyKWh(it)} ↑"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.state_ok))
                })
            }
            container.addView(energyRow)
        }
    }

    // --- appliance/bus sections ---

    private fun renderProduction(container: LinearLayout, node: ForestNode) {
        for (p in node.production) {
            addSeparator(container)
            val title = if (p.group.isNotEmpty()) "Solar — ${p.group}" else "Solar"
            addSubsectionHeader(container, "☀️", title)
            val agg = p.aggregatePower ?: p.memberPower ?: p.meter.power
            if (agg != null) addInfoRow(container, "Output", EnergyFormatters.formatPower(abs(agg)))
            if (p.mismatch) addBadge(container, "⚠ mismatch", 0xFFF59E0B.toInt())
            for (member in p.members) {
                val label = member.component.ifEmpty { member.port.ifEmpty { "MPPT" } }
                val v = member.meter.power?.let { EnergyFormatters.formatPower(abs(it)) } ?: "--"
                addInfoRow(container, "  $label", v)
            }
        }
    }

    private fun renderBattery(container: LinearLayout, store: BatteryStore) {
        addSeparator(container)
        addSubsectionHeader(container, "🔋", "Battery")
        store.soc?.let { addInfoRow(container, "SoC", "${it.roundToInt()}%") }
        store.soh?.let { addInfoRow(container, "Health", "${it.roundToInt()}%") }
        if (store.remainCapacity != null || store.fullCapacity != null) {
            val remain = store.remainCapacity?.let { "%.1f".format(it) } ?: "--"
            val full = store.fullCapacity?.let { "%.1f".format(it) } ?: "--"
            addInfoRow(container, "Capacity", "$remain / $full Ah")
        }
        if (store.maxChargePower != null || store.maxDischargePower != null) {
            val charge = store.maxChargePower?.let { EnergyFormatters.formatPower(abs(it)) } ?: "--"
            val discharge = store.maxDischargePower?.let { EnergyFormatters.formatPower(abs(it)) } ?: "--"
            addInfoRow(container, "Limits", "↓ $charge / ↑ $discharge")
        }
        if (store.socAnomaly) addBadge(container, "SOC anomaly", 0xFFEF4444.toInt())
        for (c in store.contributors) {
            val label = c.owner.ifEmpty { c.component }.ifEmpty { "source" }
            addInfoRow(container, "  $label", c.component)
        }
    }

    private fun renderPorts(container: LinearLayout, node: ForestNode) {
        val ports = node.ports ?: return
        if (ports.isEmpty()) return
        addSeparator(container)
        addSubsectionHeader(container, "🔌", "Ports")
        for (t in ports) {
            val anchor = if (t === node.anchorPort) " (upstream)" else ""
            val name = t.label.ifEmpty { t.portRole ?: t.port }
            val label = "${portRoleIcon(t.portRole)} $name$anchor"
            val busPart = t.bus?.let { "→ $it  " } ?: ""
            val pwr = t.meter.power?.let { EnergyFormatters.formatPower(abs(it)) } ?: "--"
            addInfoRow(container, label, "$busPart$pwr")
        }
    }

    private fun renderBusInfo(container: LinearLayout, node: ForestNode) {
        val bus = node.bus ?: return
        addInfoRow(container, "Coverage", bus.coverage)
        bus.ports?.let { total ->
            val metered = bus.meteredPorts?.let { " ($it metered)" } ?: ""
            addInfoRow(container, "Terminals", "$total$metered")
        }
        bus.darkPorts?.let { if (it > 0) addInfoRow(container, "Unmetered", "$it") }
        if (bus.anomaly) addBadge(container, "⚠ anomaly", 0xFFF59E0B.toInt())
    }

    // --- shared helpers ---

    private fun nodeIcon(node: ForestNode): String = when (node.kind) {
        "appliance" -> Archetypes.resolve(node.applianceType ?: "").icon
        "residual" -> "❓"
        "port" -> portRoleIcon(node.port?.portRole)
        else -> EnergyFormatters.getMeterIcon(EnergyFormatters.meterTypeOf(node.circuitType), node.circuitType)
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

    private fun hasDetailData(m: Meter): Boolean = m.voltage != null || m.current != null

    private fun hasAdvancedData(m: Meter, meterType: MeterType): Boolean {
        if (meterType == MeterType.DC) return false
        return m.frequency != null || m.pf != null || m.apparent != null || m.reactive != null
    }

    private fun stateColor(stateClass: String): Int = when (stateClass) {
        "consuming" -> ContextCompat.getColor(context, R.color.state_error)
        "producing" -> ContextCompat.getColor(context, R.color.state_ok)
        else -> ContextCompat.getColor(context, R.color.state_idle)
    }

    private fun addHeader(container: LinearLayout, icon: String, name: String, type: String, badge: String? = null) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        header.addView(TextView(context).apply { text = icon; textSize = 20f })

        val nameCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (8 * dp).toInt() }
        }
        nameCol.addView(TextView(context).apply {
            text = name
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        })
        nameCol.addView(TextView(context).apply {
            text = type
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
        })
        header.addView(nameCol)

        if (badge != null) {
            header.addView(TextView(context).apply {
                text = badge
                textSize = 10f
                setTextColor(0xFF64748B.toInt())
                setBackgroundColor(0xFFF1F5F9.toInt())
                setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            })
        }
        container.addView(header)
    }

    private fun addBadge(container: LinearLayout, text: String, color: Int) {
        container.addView(TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(color)
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (4 * dp).toInt() }
        })
    }

    private fun addInfoRow(container: LinearLayout, label: String, value: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (2 * dp).toInt() }
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = value
            textSize = 12f
        })
        container.addView(row)
    }

    private fun addSubsectionHeader(container: LinearLayout, icon: String, title: String) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (6 * dp).toInt() }
        }
        header.addView(TextView(context).apply { text = icon; textSize = 14f })
        header.addView(TextView(context).apply {
            text = title
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (6 * dp).toInt() }
        })
        container.addView(header)
    }

    private fun addSeparator(container: LinearLayout) {
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * dp).toInt(),
            ).apply {
                topMargin = (8 * dp).toInt()
                bottomMargin = (4 * dp).toInt()
            }
            setBackgroundColor(0xFFE2E8F0.toInt())
        })
    }

    private fun addDetailButtons(
        container: LinearLayout,
        currentLevel: DetailLevel,
        hasDetail: Boolean,
        hasAdvanced: Boolean,
    ) {
        data class Btn(val label: String, val action: () -> Unit)

        val buttons: List<Btn> = when (currentLevel) {
            DetailLevel.SIMPLE -> if (hasDetail) listOf(Btn("DETAIL ▼") { onDetailMore() }) else emptyList()
            DetailLevel.DETAIL -> if (hasAdvanced) listOf(
                Btn("SUMMARY ▲") { onDetailLess() },
                Btn("ADVANCED ▶") { onDetailMore() },
            ) else listOf(Btn("SUMMARY ▲") { onDetailLess() })
            DetailLevel.ADVANCED -> listOf(
                Btn("SUMMARY ▲") { onDetailBack() },
                Btn("◀ LESS") { onDetailLess() },
            )
        }
        if (buttons.isEmpty()) return

        val control = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_segmented_control)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * dp).toInt() }
        }
        for ((i, btn) in buttons.withIndex()) {
            if (i > 0) {
                control.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams((1 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(0xFFBDBDBD.toInt())
                })
            }
            control.addView(TextView(context).apply {
                text = btn.label
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF616161.toInt())
                gravity = Gravity.CENTER
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                val outValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { btn.action() }
            })
        }
        container.addView(control)
    }
}
