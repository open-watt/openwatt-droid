package com.openwatt.droid.ui.energy

import com.openwatt.droid.model.Archetypes
import com.openwatt.droid.model.energy.MeterData
import com.openwatt.droid.model.energy.MeterType
import com.openwatt.droid.model.energy.PhaseValue
import com.openwatt.droid.model.energy.PowerState
import com.openwatt.droid.util.UnitConverter
import kotlin.math.abs

/**
 * Energy-specific formatting utilities.
 * Ported from openwatt-web js/ui/energy/formatters.js and js/ui/energy/meters.js.
 */
object EnergyFormatters {

    private const val POWER_DEAD_BAND = 10.0 // Watts — prevents state flickering near zero

    /**
     * Get the display power state based on context.
     * Matches the web app's getPowerState() logic.
     */
    fun getPowerState(
        power: Double,
        meterType: MeterType,
        circuitType: String?,
        nodeType: String,
    ): PowerState {
        val isPositive = power > POWER_DEAD_BAND
        val isNegative = power < -POWER_DEAD_BAND

        if (meterType == MeterType.DC) {
            return when (circuitType?.lowercase()) {
                "battery" -> when {
                    isPositive -> PowerState("DISCHARGING", "consuming", "\u2191")
                    isNegative -> PowerState("CHARGING", "producing", "\u2193")
                    else -> PowerState("STANDBY", "idle", "")
                }
                "solar" -> when {
                    isPositive -> PowerState("PRODUCING", "producing", "\u2191")
                    else -> PowerState("STANDBY", "idle", "")
                }
                else -> when {
                    isPositive -> PowerState("CONSUMING", "consuming", "\u2193")
                    isNegative -> PowerState("PRODUCING", "producing", "\u2191")
                    else -> PowerState("STANDBY", "idle", "")
                }
            }
        }

        // AC circuits and appliances
        return if (nodeType == "appliance") {
            when {
                isPositive -> PowerState("CONSUMING", "consuming", "\u2193")
                isNegative -> PowerState("PRODUCING", "producing", "\u2191")
                else -> PowerState("STANDBY", "idle", "")
            }
        } else {
            when {
                isPositive -> PowerState("IMPORTING", "consuming", "\u2193")
                isNegative -> PowerState("EXPORTING", "producing", "\u2191")
                else -> PowerState("STANDBY", "idle", "")
            }
        }
    }

    /** Format power value with SI prefix. Returns e.g. "2.34 kW" */
    fun formatPower(watts: Double): String {
        return UnitConverter.formatWithPrefix(abs(watts), "W").formatted
    }

    /**
     * Format energy value from Joules.
     * Converts J -> Wh then formats with SI prefix. Returns e.g. "12.3 kWh"
     */
    fun formatEnergy(joules: Double): String {
        val wh = UnitConverter.convertValue(joules, "J", "Wh")
        return UnitConverter.formatWithPrefix(wh, "Wh").formatted
    }

    /** Format a value with a specific unit (e.g. voltage, current). */
    fun formatValue(value: Double, unit: String, decimals: Int = 1): String {
        return "${"%.${decimals}f".format(value)} $unit"
    }

    /** Format power factor with phase angle. */
    fun formatPowerFactor(pf: Double): String {
        val angle = Math.toDegrees(Math.acos(minOf(1.0, abs(pf))))
        return "${"%.2f".format(pf)} (${"%.0f".format(angle)}\u00B0)"
    }

    /** Get icon for meter type. */
    fun getMeterIcon(meterType: MeterType, circuitType: String?): String {
        return when (meterType) {
            MeterType.DC -> "\u238F"          // ⎏ DC symbol
            MeterType.THREE_PHASE -> {
                if (circuitType?.lowercase() == "delta") "\u25B3" else "\u224B"  // △ or ≋
            }
            MeterType.SINGLE_PHASE -> "\u223F" // ∿ Sine wave
        }
    }

    /** Get icon for an appliance type. */
    fun getApplianceIcon(type: String): String {
        return Archetypes.resolve(type).icon
    }

    /** Get flow direction arrow. */
    fun getFlowArrow(power: Double): String {
        return when {
            power > POWER_DEAD_BAND -> "\u2193"   // ↓
            power < -POWER_DEAD_BAND -> "\u2191"  // ↑
            else -> "\u00B7"                       // ·
        }
    }

    /** Get flow state class name (for color selection). */
    fun getFlowClass(power: Double): String {
        return when {
            power > POWER_DEAD_BAND -> "consuming"
            power < -POWER_DEAD_BAND -> "producing"
            else -> "idle"
        }
    }

    /** Aggregate meter data from multiple sources (e.g. multiple MPPTs). */
    fun aggregateMeterData(meters: List<MeterData?>): MeterData {
        var totalPower = 0.0
        var voltageSum = 0.0
        var voltageCount = 0
        var totalCurrent = 0.0
        var totalImport = 0.0
        var totalExport = 0.0

        for (m in meters) {
            if (m == null) continue
            m.power?.scalar?.let { totalPower += it }
            m.voltage?.scalar?.let { voltageSum += it; voltageCount++ }
            m.current?.scalar?.let { totalCurrent += it }
            m.import?.scalar?.let { totalImport += it }
            m.export?.scalar?.let { totalExport += it }
        }

        return MeterData(
            power = PhaseValue.Scalar(totalPower),
            voltage = if (voltageCount > 0) PhaseValue.Scalar(voltageSum / voltageCount) else null,
            current = if (totalCurrent != 0.0) PhaseValue.Scalar(totalCurrent) else null,
            import = if (totalImport != 0.0) PhaseValue.Scalar(totalImport) else null,
            export = if (totalExport != 0.0) PhaseValue.Scalar(totalExport) else null,
        )
    }
}
