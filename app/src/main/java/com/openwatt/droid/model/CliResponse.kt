package com.openwatt.droid.model

data class CliResponse(
    val output: String,
    val exitCode: Int = 0,
    val error: String? = null
)
