package com.openwatt.droid.model.energy.graph

/**
 * Parses a flat element map (the synthetic `energy` device's subtree) into an
 * [EnergyModel]. Ported from openwatt-web/js/models/energy-model.js
 * (`parseEnergyModel`).
 *
 * Input comes from [com.openwatt.droid.model.energy.EnergySource]'s mirror
 * snapshot of `device:energy.**`, reshaped to the classic `/api/get` form: a
 * map of dotted path -> entry, where each entry is either `{value, unit, age}`
 * or a bare value. Per-phase arrays are collapsed to the phase-0 aggregate;
 * quantity objects `{q,u}` collapse to their scalar `q`; JSON null stays null
 * ("unknown").
 */
object EnergyModelParser {

    fun parse(raw: Map<String, Any?>?): EnergyModel {
        val model = EnergyModel()
        if (raw == null) return model

        val busBags = HashMap<String, HashMap<String, Any?>>()
        val terminalBags = HashMap<String, HashMap<String, Any?>>()
        val branchBags = HashMap<String, HashMap<String, Any?>>()
        val storeBags = HashMap<String, HashMap<String, Any?>>()
        val storeContribBags = HashMap<String, HashMap<String, Any?>>()
        val prodBags = HashMap<String, HashMap<String, Any?>>()        // "owner group" -> fields
        val prodContribBags = HashMap<String, HashMap<String, Any?>>()
        val islandBags = HashMap<String, HashMap<String, Any?>>()
        val policyBags = HashMap<String, HashMap<String, Any?>>()
        val allocBags = HashMap<String, HashMap<String, Any?>>()

        for ((rawPath, entry) in raw) {
            val path = if (rawPath.startsWith("energy.")) rawPath.substring("energy.".length) else rawPath
            val seg = path.split(".")
            val value = valOf(entry)

            when {
                seg[0] == "circuit" -> {
                    if (seg.size == 2) {                       // circuit.<envelope-field>
                        when (seg[1]) {
                            "generation" -> model.generation = value
                            "schema_version" -> model.schemaVersion = value
                        }
                        continue
                    }
                    val section = seg[1]
                    val field = seg.last()                      // fields never contain a dot
                    val id = seg.subList(2, seg.size - 1).joinToString(".")
                    when (section) {
                        "bus" -> bag(busBags, id)[field] = value
                        "terminal" -> bag(terminalBags, id)[field] = value
                        "branch" -> bag(branchBags, id)[field] = value
                        "battery_store" -> bag(storeBags, id)[field] = value
                        "production" -> {
                            // circuit.production.<owner>.<group...>.<field>
                            if (seg.size < 5) continue
                            val owner = seg[2]
                            val group = seg.subList(3, seg.size - 1).joinToString(".")
                            bag(prodBags, "$owner $group")[field] = value
                        }
                        "battery_store_contribution" -> bag(storeContribBags, seg[2])[field] = value
                        "production_contribution" -> bag(prodContribBags, seg[2])[field] = value
                    }
                }
                seg[0] == "islands" && seg.size >= 3 ->
                    bag(islandBags, seg[1])[seg.subList(2, seg.size).joinToString(".")] = value
                seg[0] == "policy" && seg.size >= 3 ->
                    bag(policyBags, seg[1])[seg.subList(2, seg.size).joinToString(".")] = value
                seg[0] == "allocation" && seg.size >= 3 ->
                    bag(allocBags, seg[1])[seg.subList(2, seg.size).joinToString(".")] = value
                // control_path.* / topology.* are not consumed here.
            }
        }

        val gen = model.generation
        fun fresh(g: Any?): Boolean = gen == null || g == null || g == gen

        // Buses
        for ((id, f) in busBags) {
            if (!fresh(f["generation"])) continue
            model.buses[id] = Bus(
                id = f["id"].asStr() ?: id,
                name = f["id"].asStr() ?: id,                  // buses have no separate name
                generation = f["generation"],
                coverage = f["coverage"].asStr() ?: "unknown",
                accountedPower = f["accounted_power"].asDouble(),
                residualPower = f["residual_power"].asDouble(),
                unaccountedLoad = f["unaccounted_load_power"].asDouble(),
                unaccountedSource = f["unaccounted_source_power"].asDouble(),
                darkPowerBound = f["dark_power_bound"].asDouble(),
                ports = f["terminal_count"].asInt(),
                meteredPorts = f["metered_count"].asInt(),
                darkPorts = f["dark_count"].asInt(),
                anomaly = truthy(f["anomaly"]),
                containsGrid = truthy(f["contains_grid"]),
                explicitRoot = truthy(f["explicit_root"]),
                island = f["island"].asStr(),
                depth = f["depth"].asDouble(),
                meter = meterFrom(f),
            )
        }

        // Terminals
        for ((id, f) in terminalBags) {
            if (!fresh(f["generation"])) continue
            model.terminals[id] = Terminal(
                id = f["id"].asStr() ?: id,
                generation = f["generation"],
                owner = f["owner"].asStr() ?: "",
                ownerKind = f["owner_kind"].asStr() ?: "",
                ownerDevice = f["owner_device"].asStr() ?: "",
                port = f["port"].asStr() ?: id,
                portPath = f["port"].asStr() ?: id,
                label = f["label"].asStr() ?: "",
                bus = f["circuit"].asStr(),
                portRole = f["role"].asStr(),
                flow = f["domain"].asStr() ?: "unknown",
                orientation = f["orientation"].asDouble(),
                root = truthy(f["root"]),
                meter = meterFrom(f),
            )
        }

        // Branches
        for ((id, f) in branchBags) {
            if (!fresh(f["generation"])) continue
            val conducting = f["conducting"]
            model.branches.add(
                Branch(
                    id = f["id"].asStr() ?: id,
                    generation = f["generation"],
                    owner = f["owner"].asStr() ?: "",
                    label = f["label"].asStr() ?: "",
                    kind = f["kind"].asStr() ?: "link",
                    parent = f["parent"].asStr(),
                    child = f["child"].asStr(),
                    capacity = f["capacity"].asDouble() ?: 0.0,
                    conducting = if (conducting != null) truthy(conducting) else true,
                )
            )
        }

        // Battery stores
        for ((id, f) in storeBags) {
            if (!fresh(f["generation"])) continue
            val circuit = f["circuit"].asStr() ?: id
            model.batteryStores[circuit] = BatteryStore(
                circuit = circuit,
                soc = f["soc"].asDouble(),
                soh = f["soh"].asDouble(),
                remainCapacity = f["remain_capacity"].asDouble(),
                fullCapacity = f["full_capacity"].asDouble(),
                maxChargeCurrent = f["max_charge_current"].asDouble(),
                maxDischargeCurrent = f["max_discharge_current"].asDouble(),
                maxChargePower = f["max_charge_power"].asDouble(),
                maxDischargePower = f["max_discharge_power"].asDouble(),
                memberCount = f["member_count"].asInt(),
                viewCount = f["view_count"].asInt(),
                socAnomaly = truthy(f["soc_anomaly"]),
            )
        }
        for ((_, f) in storeContribBags) {
            if (!fresh(f["generation"])) continue
            model.batteryStoreContributions.add(
                StoreContribution(
                    circuit = f["circuit"].asStr(),
                    owner = f["owner"].asStr() ?: "",
                    port = f["port"].asStr() ?: "",
                    component = f["component"].asStr() ?: "",
                    kind = f["kind"].asStr() ?: "",
                )
            )
        }

        // Production groups
        for ((key, f) in prodBags) {
            if (!fresh(f["generation"])) continue
            val parts = key.split(" ", limit = 2)
            model.productions[key] = Production(
                owner = parts[0],
                group = parts.getOrElse(1) { "" },
                aggregatePower = f["aggregate_power"].asDouble(),
                memberPower = f["member_power"].asDouble(),
                aggregateCount = f["aggregate_count"].asInt(),
                memberCount = f["member_count"].asInt(),
                calculated = truthy(f["calculated"]),
                mismatch = truthy(f["mismatch"]),
                meter = meterFrom(f),
            )
        }
        for ((_, f) in prodContribBags) {
            if (!fresh(f["generation"])) continue
            model.productionContributions.add(
                ProductionContribution(
                    owner = f["owner"].asStr() ?: "",
                    group = f["group"].asStr() ?: "",
                    port = f["port"].asStr() ?: "",
                    component = f["component"].asStr() ?: "",
                    circuit = f["circuit"].asStr(),
                    kind = f["kind"].asStr() ?: "",
                    meter = meterFrom(f),
                )
            )
        }

        // Appliances: group terminals that have an owner.
        for (t in model.terminals.values) {
            if (t.owner.isEmpty()) continue
            val app = model.appliances.getOrPut(t.owner) { Appliance(t.owner) }
            app.ports.add(t)
        }
        for (app in model.appliances.values) {
            app.type = app.ports.firstOrNull { it.ownerKind.isNotEmpty() }?.ownerKind
                ?: applianceTypeFromTerminals(app.ports)
            app.name = app.ports.firstOrNull { it.label.isNotEmpty() }?.label ?: humanize(app.id)
        }

        // Islands (dashboard aggregates)
        for ((id, f) in islandBags) {
            val membersStr = f["members"] as? String
            val members = membersStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            model.islands[id] = Island(
                id = id,
                mode = f["mode"].asStr() ?: "unknown",
                members = members,
                fields = f,
            )
        }

        // Planner policies + allocations (joined by policy id in the Energy Manager).
        for ((id, f) in policyBags) model.policies[id] = PlannerRecord(id, f)
        for ((id, f) in allocBags) model.allocations[id] = PlannerRecord(id, f)

        return model
    }

    // ── value normalisation ──────────────────────────────────────────────────

    private fun bag(map: HashMap<String, HashMap<String, Any?>>, key: String): HashMap<String, Any?> =
        map.getOrPut(key) { HashMap() }

    /** Pull the scalar out of a /api/get entry, normalising "unknown" cases. */
    private fun valOf(entry: Any?): Any? {
        val v = if (entry is Map<*, *> && entry.containsKey("value")) entry["value"] else entry
        return norm(v)
    }

    private fun norm(v: Any?): Any? = when {
        v == null -> null
        v is List<*> -> if (v.isNotEmpty()) norm(v[0]) else null   // phase-0 aggregate
        v is Map<*, *> -> if (v.containsKey("q")) v["q"] else v    // quantity {q,u} -> q
        v is Number && v.toDouble().isNaN() -> null
        else -> v
    }

    /** Build a meter (scalar fields + "*_source" provenance) from a fields bag. */
    private fun meterFrom(f: Map<String, Any?>): Meter {
        val sources = HashMap<String, String>()
        for (field in METER_FIELDS) {
            f["${field}_source"].asStr()?.let { sources[field] = it }
        }
        return Meter(
            power = f["power"].asDouble(),
            current = f["current"].asDouble(),
            voltage = f["voltage"].asDouble(),
            import = f["import"].asDouble(),
            export = f["export"].asDouble(),
            apparent = f["apparent"].asDouble(),
            reactive = f["reactive"].asDouble(),
            pf = f["pf"].asDouble(),
            frequency = f["frequency"].asDouble(),
            sources = sources,
        )
    }

    private fun Any?.asDouble(): Double? = when (this) {
        is Number -> toDouble().let { if (it.isNaN()) null else it }
        else -> null
    }

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toDouble().let { if (it.isNaN()) null else it.toInt() }
        else -> null
    }

    private fun Any?.asStr(): String? = when (this) {
        null -> null
        is String -> this
        else -> toString()
    }

    /** JS-style truthiness, used for the boolean flag fields. */
    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
        is String -> v.isNotEmpty()
        else -> true
    }
}
