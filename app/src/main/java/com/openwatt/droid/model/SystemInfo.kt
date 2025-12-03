package com.openwatt.droid.model

data class SystemInfo(
    val status: String = "Unknown",
    val uptime: String = "0s",
    val isHealthy: Boolean = true,
    val rawOutput: String = ""
)
