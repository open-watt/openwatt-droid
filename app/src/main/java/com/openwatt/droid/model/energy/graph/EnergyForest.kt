package com.openwatt.droid.model.energy.graph

/**
 * Forest reconstruction: reshape the parsed [EnergyModel] graph into a render
 * forest for the flow diagram. Ported from openwatt-web/js/models/energy-model.js
 * (`buildForest` / `computeRoots` / `chooseAnchor`).
 *
 * Render roots are grid-anchored (the `grid` bus, explicit roots, or — only when
 * neither exists — the shallowest buses). Buses reachable only through an
 * appliance (e.g. an inverter's dc_bus) are intentionally NOT roots; they are
 * reached through their appliance during the walk, which keeps anchor selection
 * (closest-to-grid) correct.
 */

private const val RESIDUAL_EPSILON_W = 0.5

/** Anchor-terminal preference when circuit depth ties (lower = more upstream). */
private val ROLE_PRIORITY = mapOf("grid" to 0, "connection" to 1, "parent" to 2, "backup" to 3)

/** A node in the render forest. Mutable: children/power/isRoot are filled during the walk. */
class ForestNode(
    val kind: String,        // "bus" | "appliance" | "port" | "residual"
    val key: String,
    val id: String,
    val name: String,
) {
    var meter: Meter? = null
    var power: Double? = null
    var edgePower: Double? = null
    var edgeSource: String? = null
    var edgeCurrent: Double? = null
    var edgeCapacity: Double? = null
    var edgeKind: String? = null
    var open: Boolean = false
    var maxCurrent: Double? = null
    var circuitType: String = "general"
    var islandMode: String? = null
    var isRoot: Boolean = false
    var bus: Bus? = null
    var batteryStore: BatteryStore? = null
    var appliance: Appliance? = null
    var applianceType: String? = null
    var ports: List<Terminal>? = null
    var anchorPort: Terminal? = null
    var production: List<Production> = emptyList()
    var port: Terminal? = null
    val children: MutableList<ForestNode> = mutableListOf()
}

data class Forest(val roots: List<ForestNode>, val index: Map<String, ForestNode>)

fun buildForest(model: EnergyModel): Forest = ForestBuilder(model).build()

private data class EdgeHint(
    val edgePower: Double? = null,
    val edgeSource: String? = null,
    val edgeCurrent: Double? = null,
    val edgeCapacity: Double? = null,
    val edgeKind: String? = null,
    val open: Boolean = false,
    val maxCurrent: Double? = null,
    val circuitType: String? = null,
)

private class ForestBuilder(model: EnergyModel) {
    private val buses = model.buses
    private val terminals = model.terminals
    private val appliances = model.appliances
    private val batteryStores = model.batteryStores

    // Bus -> island mode (on_grid / off_grid), resolved from island members.
    private val islandModeByBus = HashMap<String, String>()
    // Physical (non-appliance) branches form the bus tree.
    private val physical: List<Branch>
    private val childBranches = HashMap<String, MutableList<Branch>>()
    private val feedByBus = HashMap<String, Meter>()
    private val termsByBus = HashMap<String, MutableList<Pair<String, Terminal>>>()
    private val storeContribByCircuit = HashMap<String?, MutableList<StoreContribution>>()
    private val productionByOwner = HashMap<String, MutableList<Production>>()
    private val rootIds: Set<String>

    private val visitedBus = HashSet<String>()
    private val visitedApp = HashSet<String>()
    private val index = HashMap<String, ForestNode>()

    init {
        for (isl in model.islands.values) for (m in isl.members) islandModeByBus[m] = isl.mode

        // Reconciled battery store contributors, grouped by battery circuit.
        for (c in model.batteryStoreContributions) {
            storeContribByCircuit.getOrPut(c.circuit) { mutableListOf() }.add(c)
        }

        // Production groups for an appliance, each with its per-member (MPPT) contributions.
        for (p in model.productions.values) {
            val members = model.productionContributions.filter {
                it.owner == p.owner && it.group == p.group && it.kind == "member"
            }
            productionByOwner.getOrPut(p.owner) { mutableListOf() }.add(p.copy(members = members))
        }

        // Non-conducting physical branches (e.g. an open ATS) are kept so the
        // circuit still shows, drawn as an open edge.
        physical = model.branches.filter { it.owner.isEmpty() && it.parent != null && it.child != null }

        for (b in physical) {
            val parent = b.parent!!
            if (!buses.containsKey(parent)) continue
            childBranches.getOrPut(parent) { mutableListOf() }.add(b)
        }

        // Feeding terminal per bus: branches carry no meter, so the throughput into
        // a bus comes from the physical (owner-less) terminal on that bus belonging
        // to its feeding branch. Terminal ids are usually "<branch>.<side>".
        val linkTerminals = terminals.values.filter { it.owner.isEmpty() }
        for (b in physical) {
            val childId = b.child!!
            val t = linkTerminals.firstOrNull {
                it.bus == childId && (it.id == b.id || it.id.startsWith("${b.id}."))
            } ?: continue
            val prev = feedByBus[childId]
            if (prev == null || (prev.power == null && t.meter.power != null)) {
                feedByBus[childId] = t.meter
            }
        }

        // Appliance terminals grouped by the bus they attach to.
        for (app in appliances.values) {
            for (t in app.ports) {
                val bus = t.bus ?: continue
                termsByBus.getOrPut(bus) { mutableListOf() }.add(app.id to t)
            }
        }

        rootIds = computeRoots(buses)
        for (app in appliances.values) app.anchorPort = chooseAnchor(app.ports)
    }

    // depth -1 / null means "not reachable from grid" — treat as far, not closest.
    private fun depthOf(busId: String?): Double {
        val d = buses[busId]?.depth
        return if (d == null || d < 0) Double.POSITIVE_INFINITY else d
    }

    private fun storeFor(circuit: String?): BatteryStore? {
        val s = (circuit?.let { batteryStores[it] }) ?: return null
        return s.copy(contributors = storeContribByCircuit[circuit] ?: emptyList())
    }

    fun build(): Forest {
        val roots = mutableListOf<ForestNode>()
        val ordered = rootIds.sortedWith(
            compareByDescending<String> { buses[it]?.id == "grid" }
                .thenByDescending { buses[it]?.containsGrid == true }
                .thenBy { depthOf(it) }
        )
        for (id in ordered) if (id !in visitedBus) roots.add(busNode(id))

        // Orphaned components (e.g. a backup circuit currently fed by an open ATS):
        // start from buses that aren't the downstream child of any physical branch,
        // so a feed chain isn't split into separate roots by iteration order.
        val fedChild = HashSet<String>()
        for (b in physical) {
            val parent = b.parent!!
            if (buses.containsKey(parent)) fedChild.add(b.child!!)
        }
        for (id in buses.keys.toList()) if (id !in visitedBus && id !in fedChild) roots.add(busNode(id))
        for (id in buses.keys.toList()) if (id !in visitedBus) roots.add(busNode(id))   // remaining cycles
        for (app in appliances.values) if (app.id !in visitedApp) roots.add(applianceNode(app.id))

        for (r in roots) r.isRoot = true
        return Forest(roots, index)
    }

    private fun busNode(busId: String, edge: EdgeHint = EdgeHint()): ForestNode {
        visitedBus.add(busId)
        val bus = buses[busId]
        val feed = feedByBus[busId]
        val balance = bus?.meter ?: Meter()
        // Prefer the feeding terminal's throughput over the synthetic bus balance.
        val meter = if (feed != null && feed.power != null) feed else balance
        val node = ForestNode(kind = "bus", key = "bus:$busId", id = busId, name = bus?.name ?: busId)
        node.meter = meter
        node.power = feed?.power ?: balance.power ?: bus?.accountedPower
        node.edgePower = edge.edgePower
        node.edgeSource = edge.edgeSource
        node.edgeCurrent = edge.edgeCurrent
        node.edgeCapacity = edge.edgeCapacity
        node.edgeKind = edge.edgeKind
        node.open = edge.open
        node.maxCurrent = edge.maxCurrent
        node.circuitType = edge.circuitType ?: "general"
        node.islandMode = islandModeByBus[busId]
        node.bus = bus
        node.batteryStore = storeFor(busId)
        index[node.key] = node

        for (b in childBranches[busId] ?: emptyList()) {
            val childId = b.child!!
            if (childId in visitedBus) continue
            val child = buses[childId]
            val fm = feedByBus[childId]
            val ep = fm?.power ?: child?.meter?.power ?: child?.accountedPower
            val cap = if (b.capacity > 0) b.capacity else null
            node.children.add(
                busNode(
                    childId,
                    EdgeHint(
                        edgePower = ep, edgeSource = fm?.sources?.get("power"), edgeCurrent = fm?.current,
                        edgeCapacity = cap, edgeKind = b.kind, open = !b.conducting, maxCurrent = cap,
                    ),
                )
            )
        }

        for ((owner, term) in termsByBus[busId] ?: emptyList()) {
            val app = appliances[owner]
            if (app == null || owner in visitedApp || app.anchorPort != term) continue
            node.children.add(applianceNode(owner))
        }

        // Root/grid bus has no feeding terminal; approximate from outgoing edges.
        // A childless leaf (e.g. a battery dc_bus) falls back to its incoming edge.
        if (node.power == null) {
            val outs = node.children.mapNotNull { it.edgePower }
            if (outs.isNotEmpty()) node.power = outs.sum()
            else if (edge.edgePower != null) node.power = edge.edgePower
        }

        // Synthetic unaccounted row (backend noise-gates these).
        val ul = bus?.unaccountedLoad
        val us = bus?.unaccountedSource
        if ((ul != null && ul > RESIDUAL_EPSILON_W) || (us != null && us > RESIDUAL_EPSILON_W)) {
            val power = bus?.residualPower ?: ((ul ?: 0.0) - (us ?: 0.0))
            val key = "residual:$busId"
            val rnode = ForestNode(kind = "residual", key = key, id = busId, name = "unaccounted")
            rnode.meter = Meter(power = power)
            rnode.power = power
            rnode.edgePower = power
            rnode.circuitType = "general"
            index[key] = rnode
            node.children.add(rnode)
        }
        return node
    }

    private fun applianceNode(appId: String): ForestNode {
        visitedApp.add(appId)
        val app = appliances[appId]!!
        val anchor = app.anchorPort
        val am = anchor?.meter ?: Meter()
        val node = ForestNode(kind = "appliance", key = "appliance:$appId", id = appId, name = app.name)
        node.applianceType = app.type
        node.meter = am
        node.power = am.power
        // The edge bus->appliance is the anchor terminal's meter.
        node.edgePower = am.power
        node.edgeSource = am.sources["power"]
        node.edgeCurrent = am.current
        node.edgeKind = "appliance"
        node.open = false
        node.circuitType = "general"
        node.appliance = app
        node.ports = app.ports
        node.anchorPort = anchor
        node.production = productionByOwner[appId] ?: emptyList()
        node.batteryStore = storeFor(app.ports.firstOrNull { it.portRole == "battery" }?.bus)
        index[node.key] = node

        for (t in app.ports) {
            if (t === anchor) continue
            val tm = t.meter
            val ct = circuitTypeOfRole(t.portRole)
            val tBus = t.bus
            if (tBus != null && buses.containsKey(tBus)) {
                if (tBus !in visitedBus) {
                    node.children.add(
                        busNode(
                            tBus,
                            EdgeHint(
                                edgePower = tm.power, edgeSource = tm.sources["power"], edgeCurrent = tm.current,
                                edgeKind = "appliance", circuitType = ct,
                            ),
                        )
                    )
                }
            } else {
                val pid = "$appId.${t.portPath}"
                val key = "port:$pid"
                val label = (t.portRole?.takeIf { it.isNotEmpty() })
                    ?: t.port.takeIf { it.isNotEmpty() } ?: "port"
                val pnode = ForestNode(kind = "port", key = key, id = pid, name = humanize(label))
                pnode.meter = tm
                pnode.power = tm.power
                pnode.edgePower = tm.power
                pnode.edgeSource = tm.sources["power"]
                pnode.edgeCurrent = tm.current
                pnode.edgeKind = "port"
                pnode.open = false
                pnode.circuitType = ct
                pnode.port = t
                index[key] = pnode
                node.children.add(pnode)
            }
        }
        return node
    }

    /**
     * Pick the appliance's upstream/anchor terminal: the one on the circuit closest
     * to a render root (by bus depth). Ties break by role (grid, connection,
     * parent, …), then the first terminal.
     */
    private fun chooseAnchor(ports: List<Terminal>): Terminal? {
        if (ports.isEmpty()) return null
        var best: Terminal? = null
        var bestDepth = Double.POSITIVE_INFINITY
        var bestRole = Int.MAX_VALUE
        for (t in ports) {
            val d = depthOf(t.bus)
            val r = (t.portRole?.let { ROLE_PRIORITY[it] }) ?: 99
            if (d < bestDepth || (d == bestDepth && r < bestRole)) {
                best = t
                bestDepth = d
                bestRole = r
            }
        }
        return best ?: ports[0]
    }
}

private fun computeRoots(buses: Map<String, Bus>): Set<String> {
    val roots = LinkedHashSet<String>()
    for (b in buses.values) if (b.id == "grid" || b.containsGrid || b.explicitRoot) roots.add(b.id)
    if (roots.isNotEmpty()) return roots

    var min = Double.POSITIVE_INFINITY
    for (b in buses.values) {
        val d = b.depth
        if (d != null && d >= 0 && d < min) min = d
    }
    for (b in buses.values) if (min != Double.POSITIVE_INFINITY && b.depth == min) roots.add(b.id)
    if (roots.isEmpty() && buses.isNotEmpty()) roots.add(buses.keys.first())
    return roots
}
