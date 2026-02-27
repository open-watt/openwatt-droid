package com.openwatt.droid.model.energy

/**
 * Aggregated energy flow state for the home screen cross diagram.
 * Computed from circuits + appliances by the ViewModel.
 */
data class EnergyFlowState(
    val gridPower: Double,
    val solarPower: Double?,
    val batteryPower: Double?,
    val batterySoc: Double?,
    val homePower: Double,
    val evs: List<EvFlowState>?,
    val gasPower: Double?,
)

/**
 * Individual EV state for the cross diagram.
 * Users need to see each car independently for charge-state awareness.
 */
data class EvFlowState(
    val name: String,
    val power: Double,
    val soc: Double?,
)
