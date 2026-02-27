package com.openwatt.droid.model.energy

/**
 * Meter type enumeration.
 * Maps to API values: "dc", "single_phase", "three_phase", "delta", "split_phase"
 */
enum class MeterType {
    DC, SINGLE_PHASE, THREE_PHASE;

    companion object {
        fun parse(typeStr: String?): MeterType {
            return when (typeStr?.lowercase()) {
                "dc" -> DC
                "three_phase", "delta" -> THREE_PHASE
                "single_phase", "split_phase" -> SINGLE_PHASE
                else -> SINGLE_PHASE
            }
        }
    }
}

/**
 * A value that may be scalar or per-phase [sum, L1, L2, L3].
 * The API returns scalars for single-phase/DC and arrays for three-phase.
 */
sealed class PhaseValue {
    abstract val scalar: Double

    data class Scalar(val value: Double) : PhaseValue() {
        override val scalar: Double get() = value
    }

    data class ThreePhase(
        val sum: Double,
        val l1: Double,
        val l2: Double,
        val l3: Double,
    ) : PhaseValue() {
        override val scalar: Double get() = sum
        val phases: List<Double> get() = listOf(l1, l2, l3)
    }
}

/**
 * Meter data from the energy API.
 * All fields are nullable — not all meters report all metrics.
 * Energy values (import/export) are cumulative in Joules.
 */
data class MeterData(
    val power: PhaseValue? = null,
    val voltage: PhaseValue? = null,
    val current: PhaseValue? = null,
    val pf: PhaseValue? = null,
    val frequency: Double? = null,
    val apparent: PhaseValue? = null,
    val reactive: PhaseValue? = null,
    val import: PhaseValue? = null,
    val export: PhaseValue? = null,
    val type: String? = null,
) {
    /** Get total power as a simple double, 0.0 if null. */
    val powerWatts: Double get() = power?.scalar ?: 0.0

    /** Detect meter type from data structure. */
    fun inferMeterType(): MeterType {
        if (type != null) return MeterType.parse(type)
        if (power is PhaseValue.ThreePhase || voltage is PhaseValue.ThreePhase) return MeterType.THREE_PHASE
        return MeterType.SINGLE_PHASE
    }

    /** Whether detail-level data (voltage/current) is available */
    fun hasDetailData(meterType: MeterType): Boolean {
        return voltage != null || current != null
    }

    /** Whether advanced-level data (frequency/PF/apparent/reactive) is available */
    fun hasAdvancedData(meterType: MeterType): Boolean {
        if (meterType == MeterType.DC) return false
        return frequency != null || pf != null || apparent != null || reactive != null
    }
}
