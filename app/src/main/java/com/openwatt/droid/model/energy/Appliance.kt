package com.openwatt.droid.model.energy

/**
 * An energy appliance (inverter, EVSE, smart switch, etc.).
 */
data class Appliance(
    val id: String,
    val name: String?,
    val type: String,
    val enabled: Boolean,
    val meterData: MeterData?,
    val inverter: InverterData?,
    val evse: EvseData?,
    val car: CarData?,
)

/**
 * Inverter-specific data with MPPTs (solar strings and battery units).
 */
data class InverterData(
    val ratedPower: Double?,
    val mppt: List<Mppt>,
)

/**
 * An MPPT channel (solar string or battery unit) within an inverter.
 */
data class Mppt(
    val id: String,
    val template: String?,
    val soc: Double?,
    val meterData: MeterData?,
) {
    val isBattery: Boolean get() = template == "Battery"
    val isSolar: Boolean get() = template != "Battery"
}

/**
 * EVSE (Electric Vehicle Supply Equipment) specific data.
 */
data class EvseData(
    val connectedCar: String?,
)

/**
 * Electric vehicle specific data.
 */
data class CarData(
    val vin: String?,
    val evse: String?,
)
