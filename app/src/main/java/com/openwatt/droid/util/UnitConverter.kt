package com.openwatt.droid.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Unit converter with SI prefix support.
 * Ported from openwatt-web/js/utils/unit-converter.js
 */
object UnitConverter {

    // SI prefixes and their multipliers
    private val SI_PREFIXES = linkedMapOf(
        "Y" to 1e24, "Z" to 1e21, "E" to 1e18, "P" to 1e15,
        "T" to 1e12, "G" to 1e9, "M" to 1e6, "k" to 1e3,
        "h" to 1e2, "da" to 1e1, "" to 1.0,
        "d" to 1e-1, "c" to 1e-2, "m" to 1e-3,
        "μ" to 1e-6, "u" to 1e-6, "n" to 1e-9, "p" to 1e-12,
        "f" to 1e-15, "a" to 1e-18, "z" to 1e-21, "y" to 1e-24,
    )

    // Base unit conversions
    private val BASE_CONVERSIONS = mapOf(
        "J" to mapOf("Wh" to 1.0 / 3600),
        "Wh" to mapOf("J" to 3600.0),
        "s" to mapOf("h" to 1.0 / 3600, "m" to 1.0 / 60),
        "h" to mapOf("s" to 3600.0, "m" to 60.0),
    )

    // Known SI base units
    private val SI_UNITS = setOf(
        "W", "Wh", "V", "A", "Hz", "Ω", "ohm", "F", "H", "S", "C", "J", "N",
        "Pa", "g", "m", "s", "K", "L", "l", "B", "b", "var", "varh", "VA", "VAh", "Ah",
    )

    // Non-SI units
    private val NON_SI_UNITS = setOf("°C", "°F", "%", "rpm", "ppm", "dB", "dBm")

    data class ParsedUnit(
        val scaleFactor: Double,
        val prefix: String,
        val baseUnit: String,
        val prefixMultiplier: Double,
    )

    data class FormatResult(
        val value: Double,
        val unit: String,
        val formatted: String,
    )

    /**
     * Parse a unit string into components.
     * e.g. "kW" -> prefix="k", baseUnit="W", "0.1W" -> scaleFactor=0.1, baseUnit="W"
     */
    fun parseUnit(unitStr: String?): ParsedUnit {
        if (unitStr.isNullOrBlank()) {
            return ParsedUnit(1.0, "", "", 1.0)
        }

        val trimmed = unitStr.trim()

        if (trimmed in NON_SI_UNITS) {
            return ParsedUnit(1.0, "", trimmed, 1.0)
        }

        // Extract leading numeric scale factor
        val scaleMatch = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+))""").find(trimmed)
        var scaleFactor = 1.0
        var remainder = trimmed

        if (scaleMatch != null) {
            scaleFactor = scaleMatch.groupValues[1].toDouble()
            remainder = trimmed.substring(scaleMatch.range.last + 1)
        }

        var prefix = ""
        var baseUnit = remainder
        var prefixMultiplier = 1.0

        // Try to find SI prefix + base unit (longer prefixes first)
        val sortedPrefixes = SI_PREFIXES.keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }

        for (p in sortedPrefixes) {
            if (remainder.startsWith(p)) {
                val potentialBase = remainder.substring(p.length)
                if (potentialBase in SI_UNITS) {
                    prefix = p
                    baseUnit = potentialBase
                    prefixMultiplier = SI_PREFIXES[p] ?: 1.0
                    break
                }
            }
        }

        // If no prefix found, check if the whole thing is a base unit
        if (prefix.isEmpty() && remainder in SI_UNITS) {
            baseUnit = remainder
            prefixMultiplier = 1.0
        }

        return ParsedUnit(scaleFactor, prefix, baseUnit, prefixMultiplier)
    }

    /** Get the total multiplier for a unit string */
    fun getUnitMultiplier(unitStr: String?): Double {
        val parsed = parseUnit(unitStr)
        return parsed.scaleFactor * parsed.prefixMultiplier
    }

    /** Convert a value from one unit to another */
    fun convertValue(value: Double, fromUnit: String?, toUnit: String?): Double {
        if (value.isNaN()) return value

        val fromParsed = parseUnit(fromUnit)
        val toParsed = parseUnit(toUnit)

        var baseConversion = 1.0

        if (fromParsed.baseUnit != toParsed.baseUnit) {
            val conversion = BASE_CONVERSIONS[fromParsed.baseUnit]?.get(toParsed.baseUnit)
            if (conversion != null) {
                baseConversion = conversion
            } else {
                return value // Incompatible units
            }
        }

        val fromMultiplier = fromParsed.scaleFactor * fromParsed.prefixMultiplier
        val toMultiplier = toParsed.scaleFactor * toParsed.prefixMultiplier
        return value * fromMultiplier * baseConversion / toMultiplier
    }

    /** Convert a value from sample unit to base unit */
    fun toBaseUnit(value: Double, sampleUnit: String?): Double {
        if (value.isNaN()) return value
        return value * getUnitMultiplier(sampleUnit)
    }

    /** Get the base unit from a unit string */
    fun getBaseUnit(unitStr: String?): String {
        return parseUnit(unitStr).baseUnit
    }

    /**
     * Format a value with automatic SI prefix selection.
     * Only rescales if value >= 1000 or <= 0.001.
     */
    fun formatWithPrefix(
        value: Double,
        baseUnit: String,
        precision: Int = 4,
        rescaleThreshold: Double = 1e3,
    ): FormatResult {
        if (value.isNaN()) {
            return FormatResult(value, baseUnit, "-- $baseUnit")
        }

        val absValue = abs(value)
        val needsUpscale = absValue >= rescaleThreshold
        val needsDownscale = absValue > 0 && absValue <= 1.0 / rescaleThreshold

        if (!needsUpscale && !needsDownscale) {
            return FormatResult(value, baseUnit, "${formatNumber(value, precision)} $baseUnit")
        }

        val prefixOrder = if (needsUpscale) {
            listOf("T", "G", "M", "k")
        } else {
            listOf("m", "μ", "n", "p")
        }

        for (prefix in prefixOrder) {
            val multiplier = SI_PREFIXES[prefix] ?: continue
            val converted = absValue / multiplier

            if (converted >= 1) {
                val finalValue = value / multiplier
                val unit = "$prefix$baseUnit"
                return FormatResult(finalValue, unit, "${formatNumber(finalValue, precision)} $unit")
            }
        }

        return FormatResult(value, baseUnit, "${formatNumber(value, precision)} $baseUnit")
    }

    /** Format a number with significant digits, trimming trailing zeros */
    private fun formatNumber(value: Double, precision: Int): String {
        val formatted = "%.${precision}g".format(value)
        // Strip trailing zeros after decimal point: "1.50" → "1.5", "1.00" → "1"
        if ('.' in formatted) {
            return formatted.trimEnd('0').trimEnd('.')
        }
        return formatted
    }

    /**
     * Resolve a backend quantity object {q, u} into scalar + unit.
     * If value is a Map with "q" key, extracts the scalar and unit.
     */
    fun resolveQuantity(value: Any?, preferredUnit: String?): Pair<Any?, String?> {
        if (value is Map<*, *> && value.containsKey("q")) {
            val scalar = value["q"]
            val sourceUnit = value["u"] as? String ?: ""

            if (!preferredUnit.isNullOrEmpty() && sourceUnit.isNotEmpty() && scalar is Number) {
                val converted = convertValue(scalar.toDouble(), sourceUnit, preferredUnit)
                return converted to preferredUnit
            }
            return scalar to (sourceUnit.ifEmpty { preferredUnit })
        }
        return value to preferredUnit
    }
}
