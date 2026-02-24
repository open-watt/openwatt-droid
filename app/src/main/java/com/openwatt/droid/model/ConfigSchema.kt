package com.openwatt.droid.model

/**
 * Schema for a backend collection (e.g., Modbus interfaces, Devices, Streams).
 * Loaded from GET /api/schema.
 */
data class CollectionSchema(
    val path: String,
    val properties: Map<String, PropertySchema>,
)

/**
 * Schema for a single property within a collection.
 */
data class PropertySchema(
    val type: List<String>,
    val access: String,
    val default: Any?,
    val category: String?,
    val flags: String?,
) {
    val primaryType: String get() = type.firstOrNull() ?: "str"
    val isReadOnly: Boolean get() = access == "r"
    val isWriteOnly: Boolean get() = access == "w"
    val isHidden: Boolean get() = flags?.contains('H') == true
}

/**
 * An item from a collection's print command.
 * The name field is extracted; all other properties are in [properties].
 */
data class CollectionItem(
    val name: String,
    val properties: Map<String, Any?>,
) {
    /** Get a property value by key */
    operator fun get(key: String): Any? = if (key == "name") name else properties[key]

    /** Get the flags numeric value */
    val flags: Int get() = (properties["flags"] as? Number)?.toInt() ?: 0

    /** Whether this item is disabled (bit 2 of ObjectFlags) */
    val isDisabled: Boolean get() = (flags and (1 shl 2)) != 0

    /** Get the comment, if any */
    val comment: String? get() = properties["comment"] as? String
}

/**
 * Object flags bitfield definition — matches backend enum ObjectFlags : ubyte.
 */
data class ObjectFlag(
    val bit: Int,
    val letter: Char,
    val name: String,
    val icon: String = letter.toString(),
)

/** All 8 backend flags in bit order (used for list display, CLI serialization) */
val OBJECT_FLAGS = listOf(
    ObjectFlag(0, 'D', "Dynamic"),
    ObjectFlag(1, 'T', "Temporary"),
    ObjectFlag(2, 'X', "Disabled"),
    ObjectFlag(3, 'I', "Invalid"),
    ObjectFlag(4, 'R', "Running"),
    ObjectFlag(5, 'S', "Slave"),
    ObjectFlag(6, 'L', "Link present"),
    ObjectFlag(7, 'H', "Hardware"),
)

/** Curated subset for the editor bottom bar, in logical order:
 *  status → connectivity → origin/lifecycle */
val EDITOR_FLAGS = listOf(
    OBJECT_FLAGS[2], // X  Disabled   ⊘
    OBJECT_FLAGS[4], // R  Running    ▶
    OBJECT_FLAGS[3], // I  Invalid    ⚠  (why it's not running)
    OBJECT_FLAGS[6], // L  Link       ↔
    OBJECT_FLAGS[5], // S  Slave      ⛓
    OBJECT_FLAGS[0], // D  Dynamic    ⚡
    OBJECT_FLAGS[1], // T  Temporary  ⧖
)

/** Format a flags integer as a string of flag letters */
fun formatFlags(value: Int): String {
    if (value == 0) return ""
    return OBJECT_FLAGS.filter { value and (1 shl it.bit) != 0 }
        .map { it.letter }
        .joinToString("")
}

/** Format a flags integer with full flag names */
fun formatFlagsLong(value: Int): String {
    if (value == 0) return ""
    return OBJECT_FLAGS.filter { value and (1 shl it.bit) != 0 }
        .joinToString(", ") { it.name }
}
