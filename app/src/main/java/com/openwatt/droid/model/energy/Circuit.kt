package com.openwatt.droid.model.energy

/**
 * A circuit in the energy hierarchy (electrical panel/bus).
 * Circuits form a tree: main panel → sub-panels, with appliances attached at each level.
 */
data class Circuit(
    val id: String,
    val name: String?,
    val type: String?,
    val meterData: MeterData?,
    val maxCurrent: Int?,
    val appliances: List<String>,
    val subCircuits: Map<String, Circuit>,
)
