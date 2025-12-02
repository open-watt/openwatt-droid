package com.openwatt.droid.model

data class Server(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = 80,
    val useHttps: Boolean = false
) {
    val baseUrl: String
        get() = "${if (useHttps) "https" else "http"}://$hostname:$port"
}
