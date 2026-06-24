package com.openwatt.droid.model.energy.graph

/**
 * Energy data model (circuit.* schema v1).
 *
 * The backend publishes the electrical graph as a flat element subtree on the
 * synthetic `energy` device. [EnergyModelParser] parses that flat map into the
 * typed records below; [buildForest] then reshapes the graph into a
 * render-friendly forest for the flow diagram.
 *
 * Ported from openwatt-web/js/models/energy-model.js. Key conventions carried
 * over from the web reference:
 *  - Missing numeric values arrive as JSON null and are kept as `null` meaning
 *    "unknown" — never coerced to 0.
 *  - Meter units: power W, current A, voltage V, import/export kWh, apparent VA,
 *    reactive var, pf 1, frequency Hz.
 *  - Records carry a per-record `generation`; records whose generation does not
 *    match `circuit.generation` are stale and dropped (no tombstones).
 *  - Branches carry NO meter; power lives on terminals. A bus's own meter is a
 *    synthetic balance, so for display we prefer the feeding terminal's power.
 */

/** Meter fields shared by bus balances, terminals and production aggregates. */
internal val METER_FIELDS = listOf(
    "power", "current", "voltage", "import", "export",
    "apparent", "reactive", "pf", "frequency",
)

/**
 * A meter: the scalar electrical fields plus per-field provenance ("*_source").
 * Per-phase arrays are collapsed to the phase-0 aggregate during parsing, so
 * every field here is a single scalar (matching the web model).
 */
data class Meter(
    val power: Double? = null,
    val current: Double? = null,
    val voltage: Double? = null,
    val import: Double? = null,
    val export: Double? = null,
    val apparent: Double? = null,
    val reactive: Double? = null,
    val pf: Double? = null,
    val frequency: Double? = null,
    /** field name (e.g. "power") -> provenance (e.g. "measured", "inferred-sum"). */
    val sources: Map<String, String> = emptyMap(),
)

/** An electrical circuit region (synthetic balance). */
data class Bus(
    val id: String,
    val name: String,
    val generation: Any? = null,
    val coverage: String = "unknown",
    val accountedPower: Double? = null,
    val residualPower: Double? = null,
    val unaccountedLoad: Double? = null,
    val unaccountedSource: Double? = null,
    val darkPowerBound: Double? = null,
    val ports: Int? = null,            // terminal_count
    val meteredPorts: Int? = null,     // metered_count
    val darkPorts: Int? = null,        // dark_count
    val anomaly: Boolean = false,
    val containsGrid: Boolean = false,
    val explicitRoot: Boolean = false,
    val island: String? = null,
    val depth: Double? = null,         // distance from grid; null/-1 = unreachable
    val meter: Meter = Meter(),
)

/** A device/appliance port attached to a bus. Power lives here, not on branches. */
data class Terminal(
    val id: String,
    val generation: Any? = null,
    val owner: String = "",            // appliance owner id; "" = a physical link terminal
    val ownerKind: String = "",        // appliance archetype hint
    val ownerDevice: String = "",
    val port: String,
    val portPath: String,
    val label: String = "",
    val bus: String? = null,           // the bus this terminal binds to (circuit)
    val portRole: String? = null,      // grid/connection/parent/backup/battery/pv/car/outlet
    val flow: String = "unknown",      // domain
    val orientation: Double? = null,
    val root: Boolean = false,
    val meter: Meter = Meter(),
)

/** An inline link between buses (breaker/meter/feed). Carries no meter. */
data class Branch(
    val id: String,
    val generation: Any? = null,
    val owner: String = "",
    val label: String = "",
    val kind: String = "link",
    val parent: String? = null,
    val child: String? = null,
    val capacity: Double = 0.0,        // amps (breaker rating)
    val conducting: Boolean = true,    // false = open breaker/ATS
)

/** A reconciled battery, keyed by its battery bus (circuit). */
data class BatteryStore(
    val circuit: String,
    val soc: Double? = null,
    val soh: Double? = null,
    val remainCapacity: Double? = null,
    val fullCapacity: Double? = null,
    val maxChargeCurrent: Double? = null,
    val maxDischargeCurrent: Double? = null,
    val maxChargePower: Double? = null,
    val maxDischargePower: Double? = null,
    val memberCount: Int? = null,
    val viewCount: Int? = null,
    val socAnomaly: Boolean = false,
    /** Populated by [buildForest], not the parser. */
    val contributors: List<StoreContribution> = emptyList(),
)

data class StoreContribution(
    val circuit: String? = null,
    val owner: String = "",
    val port: String = "",
    val component: String = "",
    val kind: String = "",
)

/** A reconciled PV/solar aggregate, keyed "owner group". */
data class Production(
    val owner: String,
    val group: String,
    val aggregatePower: Double? = null,
    val memberPower: Double? = null,
    val aggregateCount: Int? = null,
    val memberCount: Int? = null,
    val calculated: Boolean = false,
    val mismatch: Boolean = false,
    val meter: Meter = Meter(),
    /** Per-MPPT members, populated by [buildForest]. */
    val members: List<ProductionContribution> = emptyList(),
)

data class ProductionContribution(
    val owner: String = "",
    val group: String = "",
    val port: String = "",
    val component: String = "",
    val circuit: String? = null,
    val kind: String = "",
    val meter: Meter = Meter(),
)

/** A dashboard aggregate island (islands.<id>.*). */
data class Island(
    val id: String,
    val mode: String,                  // on_grid / off_grid / unknown
    val members: List<String>,
    val fields: Map<String, Any?>,
) {
    /** Read a flat dotted sub-key, e.g. "account.grid.power". */
    operator fun get(key: String): Any? = fields[key]
}

/** A planner policy or allocation record, joined by id in the Energy Manager. */
data class PlannerRecord(
    val id: String,
    val fields: Map<String, Any?>,
) {
    operator fun get(key: String): Any? = fields[key]
}

/** A grouped appliance: the terminals that share an `owner`. */
class Appliance(val id: String) {
    var type: String = ""
    var name: String = ""
    val ports: MutableList<Terminal> = mutableListOf()
    /** Upstream/anchor terminal; chosen by [buildForest]. */
    var anchorPort: Terminal? = null
}

/** The parsed energy graph. */
class EnergyModel {
    var generation: Any? = null
    var schemaVersion: Any? = null
    val buses: LinkedHashMap<String, Bus> = LinkedHashMap()
    val branches: MutableList<Branch> = mutableListOf()
    val terminals: LinkedHashMap<String, Terminal> = LinkedHashMap()
    val appliances: LinkedHashMap<String, Appliance> = LinkedHashMap()
    val batteryStores: LinkedHashMap<String, BatteryStore> = LinkedHashMap()
    val batteryStoreContributions: MutableList<StoreContribution> = mutableListOf()
    val productions: LinkedHashMap<String, Production> = LinkedHashMap()
    val productionContributions: MutableList<ProductionContribution> = mutableListOf()
    val islands: LinkedHashMap<String, Island> = LinkedHashMap()
    val policies: LinkedHashMap<String, PlannerRecord> = LinkedHashMap()
    val allocations: LinkedHashMap<String, PlannerRecord> = LinkedHashMap()

    val hasTopology: Boolean get() = buses.isNotEmpty() || appliances.isNotEmpty()

    /**
     * Preferred island for whole-home dashboards: the grid-connected one. If no
     * on_grid island is published, returns null so callers fall back to
     * circuit-derived figures rather than an unrepresentative off-grid island.
     */
    fun primaryIsland(): Island? = islands.values.firstOrNull { it.mode == "on_grid" }

    /** Representative reconciled battery SOC (%), or null. */
    fun batterySoc(): Double? {
        var best: Double? = null
        var bestCap = -1.0
        for (s in batteryStores.values) {
            val soc = s.soc ?: continue
            val cap = s.fullCapacity ?: 0.0
            if (cap > bestCap) {
                best = soc
                bestCap = cap
            }
        }
        return best
    }
}

// ── shared helpers (used by both the parser and the forest builder) ──────────

internal fun humanize(id: String): String =
    id.replace(Regex("[_-]+"), " ").replace(Regex("\\b\\w")) { it.value.uppercase() }

internal fun circuitTypeOfRole(role: String?): String = when (role) {
    "battery" -> "battery"
    "pv" -> "solar"
    else -> "general"
}

/** Fallback appliance archetype from terminal roles, when owner_kind is absent. */
internal fun applianceTypeFromTerminals(terminals: List<Terminal>): String {
    val roles = terminals.mapTo(HashSet()) { it.portRole }
    return when {
        "car" in roles -> "evse"
        "battery" in roles || "pv" in roles -> "inverter"
        "outlet" in roles -> "smart-switch"
        else -> "appliance"
    }
}
