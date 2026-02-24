package com.openwatt.droid.model

import com.google.gson.JsonElement

data class CliResponse(
    val output: String,
    val exitCode: Int = 0,
    val error: String? = null,
    val result: JsonElement? = null,
)
