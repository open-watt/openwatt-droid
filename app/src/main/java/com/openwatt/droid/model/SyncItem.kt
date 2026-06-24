package com.openwatt.droid.model

/**
 * An item tracked by the sync WebSocket, identified by CID.
 *
 * CID layout: top 6 bits = collection_id, bottom 26 bits = slot.
 *
 * [setProps] tracks which properties have been explicitly set (via set, or
 * included in the initial props of a bind). Properties not in [setProps] are
 * at their local default. This distinction powers per-field "clear to
 * default" UI later.
 */
data class SyncItem(
    val cid: Int,
    val type: String,
    val properties: Map<String, Any?> = emptyMap(),
    val setProps: Set<String> = emptySet(),
    val state: String? = null,
) {
    val name: String? get() = properties["name"] as? String

    val collectionId: Int get() = (cid ushr 26) and 0x3F

    operator fun get(key: String): Any? = properties[key]

    val flags: Int get() = (properties["flags"] as? Number)?.toInt() ?: 0

    /** Disabled bit (bit 2 of ObjectFlags) */
    val isDisabled: Boolean get() = (flags and (1 shl 2)) != 0

    val comment: String? get() = properties["comment"] as? String
}
