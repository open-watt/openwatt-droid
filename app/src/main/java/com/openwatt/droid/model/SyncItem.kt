package com.openwatt.droid.model

/**
 * An item tracked by the sync WebSocket, identified by its session handle.
 *
 * Handles are allocated by the backend per WebSocket attach: small, dense,
 * even integers introduced by `add_name{h, name, type}`. They carry no
 * persistent identity — a reconnect resets the handle space, so anything
 * that must survive an attach (navigation args, re-subscription) uses
 * (type, name) instead.
 *
 * [setProps] tracks which properties have been explicitly set (via set, or
 * included in the initial props of a bind). Properties not in [setProps] are
 * at their local default. This distinction powers per-field "clear to
 * default" UI later.
 */
data class SyncItem(
    val handle: Long,
    val type: String,
    val properties: Map<String, Any?> = emptyMap(),
    val setProps: Set<String> = emptySet(),
    val state: String? = null,
) {
    val name: String? get() = properties["name"] as? String

    operator fun get(key: String): Any? = properties[key]

    val flags: Int get() = (properties["flags"] as? Number)?.toInt() ?: 0

    /** Disabled bit (bit 2 of ObjectFlags) */
    val isDisabled: Boolean get() = (flags and (1 shl 2)) != 0

    val comment: String? get() = properties["comment"] as? String
}
