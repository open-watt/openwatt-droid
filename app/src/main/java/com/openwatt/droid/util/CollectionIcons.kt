package com.openwatt.droid.util

/**
 * Icon lookup for config collection types.
 *
 * Ported from the web app (`openwatt-web/js/ui/router.js getCollectionIcon`).
 * Rules are substring-matched against the collection/group name **in order**,
 * so more specific keys must precede generic ones (e.g. `pppoe` before `ppp`).
 *
 * Most rules resolve to a plain emoji. A few collection families that the web
 * app draws as coloured letter-badges (Zigbee, BLE) can't be expressed as a
 * single emoji, so they resolve to a [BADGE_PREFIX] sentinel of the form
 * `badge:<letter>:<#hexColor>` that the adapter renders as a coloured chip.
 */
object CollectionIcons {

    const val BADGE_PREFIX = "badge:"

    /** Red "Z" chip for Zigbee collections. */
    private const val BADGE_ZIGBEE = "${BADGE_PREFIX}Z:#C31E3C"

    /** Blue Bluetooth-rune (ᛒ) chip for BLE collections. */
    private const val BADGE_BLE = "${BADGE_PREFIX}ᛒ:#0082FC" // ᛒ

    /** Fallback for collection leaves (a document). */
    private const val LEAF_FALLBACK = "📄" // 📄

    /** Fallback for group folders. */
    private const val GROUP_FALLBACK = "📁" // 📁

    /**
     * Ordered substring rules — first match wins. Order is significant and
     * mirrors the web app exactly.
     */
    private val RULES: List<Pair<String, String>> = listOf(
        "ip-address" to "🏠",         // 🏠
        "certificate" to "📜",        // 📜
        "secret" to "🔑",             // 🔑
        "pppoe" to "📞",              // 📞
        "loopback" to "🔁",           // 🔁
        "bridge" to "🌉",             // 🌉
        "vlan" to "🏷️",         // 🏷️
        "ether" to "🔗",              // 🔗
        "tesla" to "🚗",              // 🚗
        "goodwe" to "☀️",             // ☀️
        "aa55" to "☀️",               // ☀️
        "esphome" to "🏠",            // 🏠
        "pcap" to "🎣",               // 🎣
        "console" to "💻",            // 💻
        "telnet" to "⌨️",             // ⌨️
        "serial" to "📟",             // 📟
        "cron" to "⏱️",               // ⏱️
        "dns" to "📖",                // 📖
        "zb-" to BADGE_ZIGBEE,
        "interface" to "🔌",          // 🔌
        "modbus" to "🏭",             // 🏭
        "mqtt" to "📨",               // 📨
        "http" to "🌐",               // 🌐
        "zigbee" to BADGE_ZIGBEE,
        "wifi" to "📶",               // 📶
        "wlan" to "📶",               // 📶
        "ble" to BADGE_BLE,
        "can" to "🚗",                // 🚗
        "device" to "📱",             // 📱
        "stream" to "💧",             // 💧
        "route" to "🔀",              // 🔀
        "schedule" to "⏰",                 // ⏰
        "automation" to "🤖",         // 🤖
        "energy" to "⚡",                   // ⚡
        "sampler" to "📊",            // 📊
        "logger" to "📝",             // 📝
        "alert" to "🔔",              // 🔔
        "ppp" to "📞",                // 📞
        "websocket" to "🔄",          // 🔄
        "tls" to "🔒",                // 🔒
        "tcp" to "🤝",                // 🤝
        "udp" to "🏹",                // 🏹
        "api" to "🛠️",          // 🛠️
        "sync" to "🔄",               // 🔄
        "ws-" to "🔄",                // 🔄
    )

    private fun match(name: String): String? {
        val lower = name.lowercase()
        for ((key, icon) in RULES) {
            if (lower.contains(key)) return icon
        }
        return null
    }

    /** Icon for a collection leaf; falls back to a document. */
    fun forCollection(name: String): String = match(name) ?: LEAF_FALLBACK

    /** Icon for a group folder; falls back to a folder. */
    fun forGroup(name: String): String = match(name) ?: GROUP_FALLBACK
}
