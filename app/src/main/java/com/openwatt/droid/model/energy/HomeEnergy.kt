package com.openwatt.droid.model.energy

/**
 * UI models for the Home screen's energy sections, derived from the circuit.*
 * element model (the primary island account + planner records). Mirrors the web
 * home scorecard.js / manager.js.
 */

/** Today's energy totals (kWh), from the grid island's `account.*.today.*`. */
data class ScorecardData(
    val hasIsland: Boolean,
    val generatedKwh: Double? = null,     // account.solar.today.energy
    val consumedKwh: Double? = null,      // sources in - stored/exported out
    val selfUsePct: Double? = null,       // (solar - gridExport) / solar
    val gridNetKwh: Double? = null,       // import - export (signed: + = net import)
    val battChargedKwh: Double? = null,
    val battDischargedKwh: Double? = null,
)

/** Planner status + live control decisions, from islands.budget.* + policy/allocation. */
data class ManagerData(
    val hasContent: Boolean,
    val mode: String = "—",               // On-grid / Off-grid / —
    val chips: List<Chip> = emptyList(),
    val decisions: List<Decision> = emptyList(),
) {
    data class Chip(val label: String, val value: String)

    data class Decision(
        val target: String,
        val reason: String,
        val commanded: String?,           // "on"/"off"/value, or null
        val satisfied: Boolean?,          // null = unknown; drives the status dot
        val tier: Int?,
    )
}
