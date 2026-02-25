package com.openwatt.droid.model

/**
 * Flattened switch representation for the home screen grid.
 * Combines device + component + element data into a simple UI model.
 */
data class HomeSwitch(
    val deviceId: String,
    val deviceName: String,
    val switchId: String,
    val switchPath: String,
    val displayName: String,
    val switchType: String?,
    val icon: String,
    val isOn: Boolean,
    val power: Double?,
    val powerFormatted: String?,
)
