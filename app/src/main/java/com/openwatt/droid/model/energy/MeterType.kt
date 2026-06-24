package com.openwatt.droid.model.energy

/**
 * Meter type, used for power-state interpretation and meter icons.
 * Maps backend values: "dc", "single_phase", "split_phase", "three_phase", "delta".
 *
 * Note: the circuit.* element model collapses per-phase arrays to a scalar, so in
 * practice nodes resolve to DC (battery/solar) or SINGLE_PHASE; THREE_PHASE is
 * retained for completeness / future per-phase data.
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
