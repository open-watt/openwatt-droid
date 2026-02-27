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
import com.openwatt.droid.model.energy.Appliance
import com.openwatt.droid.model.energy.Circuit
import com.openwatt.droid.model.energy.DetailLevel
import com.openwatt.droid.model.energy.MeterData
import com.openwatt.droid.model.energy.MeterType
import com.openwatt.droid.model.energy.PhaseValue
import kotlin.math.abs

/**
 * Builds the detail panel content for a selected circuit or appliance node.
 * Supports progressive disclosure (SIMPLE → DETAIL → ADVANCED).
 */
class MeterDetailView(
    private val context: Context,
    private val onDetailMore: () -> Unit,
    private val onDetailLess: () -> Unit,
    private val onDetailBack: () -> Unit,
) {
    private val dp = context.resources.displayMetrics.density

    fun buildCircuitDetail(
        container: LinearLayout,
        circuit: Circuit,
        detailLevel: DetailLevel,
    ) {
        container.removeAllViews()

        val meterType = circuit.meterData?.inferMeterType() ?: MeterType.SINGLE_PHASE
        val icon = EnergyFormatters.getMeterIcon(meterType, circuit.type)

        // Header with max current badge
        addHeader(container, icon, circuit.name ?: "Circuit", "Circuit", circuit.maxCurrent?.let { "${it}A" })

        // Meter data
        circuit.meterData?.let { addMeterDisplay(container, it, detailLevel, "circuit", circuit.type) }

        // Detail level toggle buttons
        addDetailButtons(container, detailLevel, circuit.meterData?.hasDetailData(meterType) == true, circuit.meterData?.hasAdvancedData(meterType) == true)
    }

    fun buildApplianceDetail(
        container: LinearLayout,
        appliance: Appliance,
        allAppliances: Map<String, Appliance>,
        detailLevel: DetailLevel,
    ) {
        container.removeAllViews()

        val icon = Archetypes.resolve(appliance.type).icon

        // Header with enabled/disabled badge
        addHeader(container, icon, appliance.name ?: appliance.id, appliance.type)
        if (!appliance.enabled) {
            addBadge(container, "DISABLED", 0xFFEF4444.toInt())
        }

        // Main meter data
        appliance.meterData?.let {
            addMeterDisplay(container, it, detailLevel, "appliance", null)
        }

        // Inverter subsections
        appliance.inverter?.let { inverter ->
            if (inverter.mppt.isNotEmpty()) {
                addSeparator(container)

                // Rated power
                inverter.ratedPower?.let { rp ->
                    addInfoRow(container, "Rated Power", EnergyFormatters.formatPower(rp))
                }

                val batteries = inverter.mppt.filter { it.isBattery }
                val solars = inverter.mppt.filter { it.isSolar }

                // Battery section
                if (batteries.isNotEmpty()) {
                    addSeparator(container)
                    val aggMeter = EnergyFormatters.aggregateMeterData(batteries.mapNotNull { it.meterData })
                    val avgSoc = batteries.mapNotNull { it.soc }.average().takeIf { !it.isNaN() }

                    addSubsectionHeader(container, "\uD83D\uDD0B", "Battery")
                    avgSoc?.let { addInfoRow(container, "SoC", "${it.toInt()}%") }
                    addMeterDisplay(container, aggMeter, minOf(detailLevel, DetailLevel.DETAIL), "appliance", "battery")

                    // Individual batteries if more than one
                    if (batteries.size > 1) {
                        for (batt in batteries) {
                            addInfoRow(container, batt.id, buildString {
                                batt.soc?.let { append("${it.toInt()}%  ") }
                                batt.meterData?.powerWatts?.let {
                                    append(EnergyFormatters.formatPower(abs(it)))
                                }
                            })
                        }
                    }
                }

                // Solar section
                if (solars.isNotEmpty()) {
                    addSeparator(container)
                    val aggMeter = EnergyFormatters.aggregateMeterData(solars.mapNotNull { it.meterData })

                    addSubsectionHeader(container, "\u2600\uFE0F", "Solar")
                    addMeterDisplay(container, aggMeter, minOf(detailLevel, DetailLevel.DETAIL), "appliance", "solar")

                    // Individual strings if more than one
                    if (solars.size > 1) {
                        for (solar in solars) {
                            addInfoRow(container, solar.id, buildString {
                                solar.meterData?.powerWatts?.let {
                                    append(EnergyFormatters.formatPower(abs(it)))
                                }
                            })
                        }
                    }
                }
            }
        }

        // EVSE section
        appliance.evse?.let { evse ->
            addSeparator(container)
            addSubsectionHeader(container, "\uD83D\uDD0C", "EV Charger")
            val carId = evse.connectedCar
            if (carId != null) {
                val car = allAppliances[carId]
                addInfoRow(container, "Connected", car?.name ?: carId)
                car?.car?.vin?.let { addInfoRow(container, "VIN", it) }
            } else {
                addInfoRow(container, "Status", "No car connected")
            }
        }

        // Car section
        appliance.car?.let { car ->
            addSeparator(container)
            addSubsectionHeader(container, "\uD83D\uDE97", "Vehicle")
            car.vin?.let { addInfoRow(container, "VIN", it) }
            car.evse?.let { evseId ->
                val evse = allAppliances[evseId]
                addInfoRow(container, "Charger", evse?.name ?: evseId)
            }
        }

        // Detail level buttons
        val appMeterType = appliance.meterData?.inferMeterType() ?: MeterType.SINGLE_PHASE
        addDetailButtons(container, detailLevel, appliance.meterData?.hasDetailData(appMeterType) == true, appliance.meterData?.hasAdvancedData(appMeterType) == true)
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

        val iconView = TextView(context).apply {
            text = icon
            textSize = 20f
        }
        header.addView(iconView)

        val nameCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = (8 * dp).toInt() }
        }

        val nameView = TextView(context).apply {
            text = name
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        nameCol.addView(nameView)

        val typeView = TextView(context).apply {
            text = type
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
        }
        nameCol.addView(typeView)

        header.addView(nameCol)

        // Optional badge (e.g., max current) — small gray chip at top-right
        if (badge != null) {
            val badgeView = TextView(context).apply {
                text = badge
                textSize = 10f
                setTextColor(0xFF64748B.toInt())
                setBackgroundColor(0xFFF1F5F9.toInt())
                setPadding(
                    (6 * dp).toInt(), (2 * dp).toInt(),
                    (6 * dp).toInt(), (2 * dp).toInt(),
                )
            }
            header.addView(badgeView)
        }

        container.addView(header)
    }

    private fun addBadge(container: LinearLayout, text: String, color: Int) {
        val badge = TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(color)
            setBackgroundColor(0x20FF0000)
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (4 * dp).toInt() }
        }
        container.addView(badge)
    }

    private fun addMeterDisplay(
        container: LinearLayout,
        meterData: MeterData,
        detailLevel: DetailLevel,
        nodeType: String,
        circuitType: String?,
    ) {
        val power = meterData.powerWatts
        val meterType = meterData.inferMeterType()
        val state = EnergyFormatters.getPowerState(power, meterType, circuitType, nodeType)

        // Power + state row
        val powerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (6 * dp).toInt() }
        }

        val powerText = TextView(context).apply {
            text = "${state.arrow} ${EnergyFormatters.formatPower(abs(power))}"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(
                when (state.stateClass) {
                    "consuming" -> ContextCompat.getColor(context, R.color.state_error)
                    "producing" -> ContextCompat.getColor(context, R.color.state_ok)
                    else -> ContextCompat.getColor(context, R.color.state_idle)
                }
            )
        }
        powerRow.addView(powerText)

        val stateText = TextView(context).apply {
            text = state.label
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (8 * dp).toInt() }
        }
        powerRow.addView(stateText)
        container.addView(powerRow)

        // DETAIL level: voltage, current, per-phase
        if (detailLevel >= DetailLevel.DETAIL) {
            meterData.voltage?.let { v ->
                addPhaseRow(container, "Voltage", v, "V")
            }
            meterData.current?.let { c ->
                addPhaseRow(container, "Current", c, "A")
            }
        }

        // ADVANCED level: frequency, PF, apparent, reactive
        if (detailLevel >= DetailLevel.ADVANCED) {
            meterData.frequency?.let { f ->
                addInfoRow(container, "Frequency", "${"%.2f".format(f)} Hz")
            }
            meterData.pf?.let { pf ->
                when (pf) {
                    is PhaseValue.Scalar -> addInfoRow(container, "Power Factor", EnergyFormatters.formatPowerFactor(pf.value))
                    is PhaseValue.ThreePhase -> {
                        addInfoRow(container, "PF L1", EnergyFormatters.formatPowerFactor(pf.l1))
                        addInfoRow(container, "PF L2", EnergyFormatters.formatPowerFactor(pf.l2))
                        addInfoRow(container, "PF L3", EnergyFormatters.formatPowerFactor(pf.l3))
                    }
                }
            }
            meterData.apparent?.let { ap ->
                addInfoRow(container, "Apparent", EnergyFormatters.formatValue(ap.scalar, "VA"))
            }
            meterData.reactive?.let { rp ->
                addInfoRow(container, "Reactive", EnergyFormatters.formatValue(rp.scalar, "var"))
            }
        }

        // Energy totals (always shown if available)
        val hasEnergy = (meterData.import?.scalar ?: 0.0) > 0 || (meterData.export?.scalar ?: 0.0) > 0
        if (hasEnergy) {
            val energyRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (4 * dp).toInt() }
            }

            meterData.import?.scalar?.takeIf { it > 0 }?.let { imp ->
                val importText = TextView(context).apply {
                    text = "${EnergyFormatters.formatEnergy(imp)} \u2193"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.state_error))
                }
                energyRow.addView(importText)
            }

            meterData.export?.scalar?.takeIf { it > 0 }?.let { exp ->
                val exportText = TextView(context).apply {
                    text = "  ${EnergyFormatters.formatEnergy(exp)} \u2191"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.state_ok))
                }
                energyRow.addView(exportText)
            }

            container.addView(energyRow)
        }
    }

    private fun addPhaseRow(container: LinearLayout, label: String, value: PhaseValue, unit: String) {
        when (value) {
            is PhaseValue.Scalar -> {
                addInfoRow(container, label, EnergyFormatters.formatValue(value.value, unit))
            }
            is PhaseValue.ThreePhase -> {
                addInfoRow(container, "$label (sum)", EnergyFormatters.formatValue(value.sum, unit))
                addInfoRow(container, "  L1", EnergyFormatters.formatValue(value.l1, unit))
                addInfoRow(container, "  L2", EnergyFormatters.formatValue(value.l2, unit))
                addInfoRow(container, "  L3", EnergyFormatters.formatValue(value.l3, unit))
            }
        }
    }

    private fun addInfoRow(container: LinearLayout, label: String, value: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (2 * dp).toInt() }
        }

        val labelView = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(labelView)

        val valueView = TextView(context).apply {
            text = value
            textSize = 12f
        }
        row.addView(valueView)

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

        val iconView = TextView(context).apply {
            text = icon
            textSize = 14f
        }
        header.addView(iconView)

        val titleView = TextView(context).apply {
            text = title
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (6 * dp).toInt() }
        }
        header.addView(titleView)

        container.addView(header)
    }

    private fun addSeparator(container: LinearLayout) {
        val sep = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * dp).toInt(),
            ).apply {
                topMargin = (8 * dp).toInt()
                bottomMargin = (4 * dp).toInt()
            }
            setBackgroundColor(0xFFE2E8F0.toInt())
        }
        container.addView(sep)
    }

    private fun addDetailButtons(
        container: LinearLayout,
        currentLevel: DetailLevel,
        hasDetail: Boolean,
        hasAdvanced: Boolean,
    ) {
        // Determine button config for current level
        data class Btn(val label: String, val action: () -> Unit)

        val buttons: List<Btn> = when (currentLevel) {
            DetailLevel.SIMPLE -> {
                if (hasDetail) listOf(Btn("DETAIL \u25BC") { onDetailMore() })
                else emptyList()
            }
            DetailLevel.DETAIL -> {
                if (hasAdvanced) listOf(
                    Btn("SUMMARY \u25B2") { onDetailLess() },
                    Btn("ADVANCED \u25B6") { onDetailMore() },
                ) else listOf(
                    Btn("SUMMARY \u25B2") { onDetailLess() },
                )
            }
            DetailLevel.ADVANCED -> listOf(
                Btn("SUMMARY \u25B2") { onDetailBack() },
                Btn("\u25C0 LESS") { onDetailLess() },
            )
        }

        if (buttons.isEmpty()) return

        // Segmented control container — outlined rounded rect
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
                // Vertical divider between segments
                control.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (1 * dp).toInt(),
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    )
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
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.attr.selectableItemBackground.let { attr ->
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(attr, outValue, true)
                    outValue.resourceId
                })
                setOnClickListener { btn.action() }
            })
        }

        container.addView(control)
    }
}
