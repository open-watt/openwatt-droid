package com.openwatt.droid.model

import com.openwatt.droid.util.UnitConverter

/**
 * A summary metric displayed on a collapsed device card.
 */
data class SummaryMetric(
    val label: String,
    val path: String,
    val format: String,
)

/**
 * Archetype configuration defining how a device type is displayed.
 */
data class DeviceArchetype(
    val id: String,
    val label: String,
    val icon: String,
    val summary: List<SummaryMetric>,
    val stateSource: String?,
    val stateMap: Map<String, String>,
)

/**
 * Value formatter types for device metrics.
 */
object ValueFormatter {

    fun format(value: Any?, formatType: String, unit: String?): String {
        if (value == null) return "--"

        return when (formatType) {
            "power" -> formatPower(value, unit)
            "power-signed" -> formatPowerSigned(value, unit)
            "energy" -> formatEnergy(value, unit)
            "voltage" -> formatWithUnit(value, unit, "V")
            "current" -> formatWithUnit(value, unit, "A")
            "percent" -> formatPercent(value, unit)
            "temperature" -> formatTemperature(value, unit)
            "frequency" -> formatWithUnit(value, unit, "Hz")
            "boolean" -> formatBoolean(value)
            "contact" -> formatContact(value)
            "evse-state" -> formatEvseState(value)
            else -> formatDefault(value, unit)
        }
    }

    private fun formatPower(value: Any?, sampleUnit: String?): String {
        val num = toDouble(value) ?: return "--"
        val valInW = UnitConverter.convertValue(num, sampleUnit ?: "W", "W")
        return UnitConverter.formatWithPrefix(valInW, "W").formatted
    }

    private fun formatPowerSigned(value: Any?, sampleUnit: String?): String {
        val num = toDouble(value) ?: return "--"
        val valInW = UnitConverter.convertValue(num, sampleUnit ?: "W", "W")
        val formatted = UnitConverter.formatWithPrefix(kotlin.math.abs(valInW), "W").formatted
        return when {
            valInW < -10 -> "\u2193 $formatted"  // ↓ Exporting/discharging
            valInW > 10 -> "\u2191 $formatted"    // ↑ Importing/charging
            else -> formatted
        }
    }

    private fun formatEnergy(value: Any?, sampleUnit: String?): String {
        val num = toDouble(value) ?: return "--"
        val valInWh = UnitConverter.convertValue(num, sampleUnit ?: "Wh", "Wh")
        return UnitConverter.formatWithPrefix(valInWh, "Wh").formatted
    }

    private fun formatWithUnit(value: Any?, sampleUnit: String?, defaultUnit: String): String {
        val num = toDouble(value) ?: return "--"
        val baseValue = UnitConverter.toBaseUnit(num, sampleUnit)
        val baseUnit = UnitConverter.getBaseUnit(sampleUnit).ifEmpty { defaultUnit }
        return UnitConverter.formatWithPrefix(baseValue, baseUnit).formatted
    }

    private fun formatPercent(value: Any?, sampleUnit: String?): String {
        val num = toDouble(value) ?: return "--"
        val baseValue = UnitConverter.toBaseUnit(num, sampleUnit)
        return "${kotlin.math.round(baseValue).toInt()}%"
    }

    private fun formatTemperature(value: Any?, sampleUnit: String?): String {
        val num = toDouble(value) ?: return "--"
        val baseValue = UnitConverter.toBaseUnit(num, sampleUnit)
        val baseUnit = UnitConverter.getBaseUnit(sampleUnit).ifEmpty { "\u00B0C" }
        return "${"%.1f".format(baseValue)} $baseUnit"
    }

    private fun formatBoolean(value: Any?): String {
        return when {
            value == true || value == "true" -> "On"
            value == false || value == "false" -> "Off"
            else -> "--"
        }
    }

    private fun formatContact(value: Any?): String {
        return when {
            value == true || value == "true" -> "Open"
            value == false || value == "false" -> "Closed"
            else -> "--"
        }
    }

    private fun formatEvseState(value: Any?): String {
        val states = mapOf(
            "A" to "Standby", "B" to "Connected", "C" to "Charging",
            "D" to "Ventilation", "E" to "Error", "F" to "Error",
        )
        return states[value?.toString()] ?: value?.toString() ?: "--"
    }

    private fun formatDefault(value: Any?, sampleUnit: String?): String {
        val num = toDouble(value)
        if (num != null) {
            val baseValue = UnitConverter.toBaseUnit(num, sampleUnit)
            val baseUnit = UnitConverter.getBaseUnit(sampleUnit)
            if (baseUnit.isNotEmpty()) {
                return UnitConverter.formatWithPrefix(baseValue, baseUnit).formatted
            }
            return "%.4g".format(baseValue)
        }
        return value.toString()
    }

    private fun toDouble(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}

/**
 * Built-in device archetypes.
 */
object Archetypes {

    val INVERTER = DeviceArchetype(
        id = "inverter",
        label = "Inverter",
        icon = "\u26A1",
        summary = listOf(
            SummaryMetric("Solar", "solar.meter.power", "power"),
            SummaryMetric("Battery", "battery.soc", "percent"),
            SummaryMetric("Grid", "meter.power", "power-signed"),
            SummaryMetric("Load", "load.power", "power"),
        ),
        stateSource = "inverter.state",
        stateMap = mapOf(
            "standby" to "idle", "grid_tied" to "ok", "on_grid" to "ok",
            "off_grid" to "warn", "fault" to "error", "error" to "error",
        ),
    )

    val ENERGY_METER = DeviceArchetype(
        id = "energy-meter",
        label = "Energy Meter",
        icon = "\uD83D\uDCC8",
        summary = listOf(
            SummaryMetric("Power", "meter.power", "power"),
            SummaryMetric("Voltage", "meter.voltage", "voltage"),
            SummaryMetric("Current", "meter.current", "current"),
            SummaryMetric("Today", "meter.import", "energy"),
        ),
        stateSource = null,
        stateMap = emptyMap(),
    )

    val BATTERY = DeviceArchetype(
        id = "battery",
        label = "Battery",
        icon = "\uD83D\uDD0B",
        summary = listOf(
            SummaryMetric("SoC", "battery.soc", "percent"),
            SummaryMetric("Power", "battery.meter.power", "power-signed"),
            SummaryMetric("Health", "battery.soh", "percent"),
            SummaryMetric("Temp", "battery.temp", "temperature"),
        ),
        stateSource = "battery.mode",
        stateMap = mapOf(
            "charging" to "ok", "discharging" to "ok",
            "standby" to "idle", "idle" to "idle", "fault" to "error",
        ),
    )

    val EVSE = DeviceArchetype(
        id = "evse",
        label = "EV Charger",
        icon = "\u26FD",
        summary = listOf(
            SummaryMetric("State", "evse.state", "evse-state"),
            SummaryMetric("Power", "meter.power", "power"),
            SummaryMetric("Session", "evse.session_energy", "energy"),
            SummaryMetric("Current", "evse.charge_control.actual_current", "current"),
        ),
        stateSource = "evse.state",
        stateMap = mapOf(
            "A" to "idle", "B" to "warn", "C" to "ok",
            "D" to "error", "E" to "error", "F" to "error",
        ),
    )

    val SMART_SWITCH = DeviceArchetype(
        id = "smart-switch",
        label = "Smart Switch",
        icon = "\uD83D\uDD0C",
        summary = listOf(
            SummaryMetric("Power", "meter.power", "power"),
            SummaryMetric("Today", "meter.import", "energy"),
        ),
        stateSource = null,
        stateMap = emptyMap(),
    )

    val CONTACT_SENSOR = DeviceArchetype(
        id = "contact-sensor",
        label = "Contact Sensor",
        icon = "\uD83D\uDEAA",
        summary = listOf(
            SummaryMetric("Status", "sensor.open", "contact"),
            SummaryMetric("Alarm", "sensor.alarm", "boolean"),
        ),
        stateSource = "sensor.alarm",
        stateMap = mapOf("true" to "error", "false" to "ok"),
    )

    val HVAC = DeviceArchetype(
        id = "hvac",
        label = "HVAC",
        icon = "\u2744\uFE0F",
        summary = listOf(
            SummaryMetric("Mode", "hvac.mode", "default"),
            SummaryMetric("Setpoint", "hvac.setpoint", "temperature"),
            SummaryMetric("Temp", "hvac.temperature", "temperature"),
            SummaryMetric("Power", "meter.power", "power"),
        ),
        stateSource = "hvac.mode",
        stateMap = mapOf(
            "off" to "idle", "cooling" to "ok", "heating" to "ok",
            "auto" to "ok", "fan" to "ok", "fault" to "error",
        ),
    )

    val CAR = DeviceArchetype(
        id = "car",
        label = "Electric Vehicle",
        icon = "\uD83D\uDE97",
        summary = listOf(
            SummaryMetric("SoC", "car.soc", "percent"),
            SummaryMetric("Power", "meter.power", "power"),
        ),
        stateSource = null,
        stateMap = emptyMap(),
    )

    val WATER_HEATER = DeviceArchetype(
        id = "water-heater",
        label = "Water Heater",
        icon = "\uD83D\uDEBF",
        summary = listOf(
            SummaryMetric("Power", "meter.power", "power"),
            SummaryMetric("Temp", "water-heater.temperature", "temperature"),
        ),
        stateSource = null,
        stateMap = emptyMap(),
    )

    val DEFAULT = DeviceArchetype(
        id = "unknown",
        label = "Device",
        icon = "\uD83D\uDCE6",
        summary = emptyList(),
        stateSource = null,
        stateMap = emptyMap(),
    )

    private val ALL = mapOf(
        "inverter" to INVERTER,
        "energy-meter" to ENERGY_METER,
        "battery" to BATTERY,
        "evse" to EVSE,
        "smart-switch" to SMART_SWITCH,
        "contact-sensor" to CONTACT_SENSOR,
        "hvac" to HVAC,
        "car" to CAR,
        "water-heater" to WATER_HEATER,
    )

    /** Resolve the archetype for a given device type string */
    fun resolve(type: String?): DeviceArchetype {
        return ALL[type] ?: DEFAULT
    }

    /** Switch type icons */
    val SWITCH_TYPE_ICONS = mapOf(
        "light" to "\uD83D\uDCA1",
        "outlet" to "\uD83D\uDD0C",
        "power" to "\uD83D\uDD0C",
        "fan" to "\uD83C\uDF00",
        "heater" to "\uD83D\uDD25",
        "pump" to "\uD83D\uDCA7",
    )
}
