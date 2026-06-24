package com.openwatt.droid.model.energy.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the circuit.* element-model port (parser + forest reconstruction)
 * against a small synthetic topology:
 *
 *   grid ──main_feed(breaker)──> main ──(solar inverter)──> solar_dc
 *
 * Exercises: envelope generation, stale-record dropping, array/quantity value
 * normalisation, feeding-terminal throughput, appliance grouping + anchor
 * selection, reaching a DC bus through its appliance, islands, and planner records.
 */
class EnergyGraphTest {

    private fun fixture(): Map<String, Any?> = linkedMapOf(
        // envelope
        "energy.circuit.generation" to 5.0,
        "energy.circuit.schema_version" to 1.0,

        // grid bus (root): synthetic balance power 1000 W
        "energy.circuit.bus.grid.id" to "grid",
        "energy.circuit.bus.grid.generation" to 5.0,
        "energy.circuit.bus.grid.contains_grid" to true,
        "energy.circuit.bus.grid.coverage" to "measured",
        "energy.circuit.bus.grid.depth" to 0.0,
        "energy.circuit.bus.grid.power" to 1000.0,

        // main bus (depth 1) — its own balance is ~0; throughput comes from the feed
        "energy.circuit.bus.main.id" to "main",
        "energy.circuit.bus.main.generation" to 5.0,
        "energy.circuit.bus.main.depth" to 1.0,
        "energy.circuit.bus.main.power" to 0.0,

        // solar DC bus (reachable only through the inverter; depth -1 = unreachable)
        "energy.circuit.bus.solar_dc.id" to "solar_dc",
        "energy.circuit.bus.solar_dc.generation" to 5.0,
        "energy.circuit.bus.solar_dc.depth" to (-1.0),

        // a STALE bus (generation 4 != 5) — must be dropped
        "energy.circuit.bus.old.id" to "old",
        "energy.circuit.bus.old.generation" to 4.0,
        "energy.circuit.bus.old.power" to 9999.0,

        // physical feed branch grid -> main (breaker, 63 A)
        "energy.circuit.branch.main_feed.id" to "main_feed",
        "energy.circuit.branch.main_feed.generation" to 5.0,
        "energy.circuit.branch.main_feed.owner" to "",
        "energy.circuit.branch.main_feed.kind" to "breaker",
        "energy.circuit.branch.main_feed.parent" to "grid",
        "energy.circuit.branch.main_feed.child" to "main",
        "energy.circuit.branch.main_feed.capacity" to 63.0,
        "energy.circuit.branch.main_feed.conducting" to true,

        // feeding terminal for `main` — power as a 3-phase array (collapses to [0]),
        // voltage as a {q,u} quantity (collapses to q), provenance on power.
        "energy.circuit.terminal.main_feed.child.id" to "main_feed.child",
        "energy.circuit.terminal.main_feed.child.generation" to 5.0,
        "energy.circuit.terminal.main_feed.child.owner" to "",
        "energy.circuit.terminal.main_feed.child.circuit" to "main",
        "energy.circuit.terminal.main_feed.child.power" to listOf(900.0, 300.0, 300.0, 300.0),
        "energy.circuit.terminal.main_feed.child.power_source" to "measured",
        "energy.circuit.terminal.main_feed.child.voltage" to mapOf("q" to 230.0, "u" to "V"),

        // inverter AC terminal on `main` (anchor: depth 1) — entry wrapped as {value,..}
        "energy.circuit.terminal.inv1_ac.id" to "inv1_ac",
        "energy.circuit.terminal.inv1_ac.generation" to 5.0,
        "energy.circuit.terminal.inv1_ac.owner" to "inverter1",
        "energy.circuit.terminal.inv1_ac.owner_kind" to "inverter",
        "energy.circuit.terminal.inv1_ac.label" to "Solar Inverter",
        "energy.circuit.terminal.inv1_ac.circuit" to "main",
        "energy.circuit.terminal.inv1_ac.role" to "connection",
        "energy.circuit.terminal.inv1_ac.power" to mapOf("value" to (-500.0), "unit" to "W", "age" to 0.1),

        // inverter DC terminal on `solar_dc` (depth Infinity -> not the anchor)
        "energy.circuit.terminal.inv1_dc.id" to "inv1_dc",
        "energy.circuit.terminal.inv1_dc.generation" to 5.0,
        "energy.circuit.terminal.inv1_dc.owner" to "inverter1",
        "energy.circuit.terminal.inv1_dc.circuit" to "solar_dc",
        "energy.circuit.terminal.inv1_dc.role" to "pv",
        "energy.circuit.terminal.inv1_dc.power" to (-520.0),

        // island aggregate
        "energy.islands.home.mode" to "on_grid",
        "energy.islands.home.members" to "grid, main",
        "energy.islands.home.account.grid.power" to 1000.0,

        // planner records (joined by id "p1")
        "energy.policy.p1.satisfied" to false,
        "energy.policy.p1.tier" to 2.0,
        "energy.allocation.p1.target" to "battery",
        "energy.allocation.p1.commanded" to true,
    )

    private fun ForestNode.child(id: String): ForestNode? = children.firstOrNull { it.id == id }

    @Test
    fun parsesEnvelopeAndDropsStaleRecords() {
        val m = EnergyModelParser.parse(fixture())
        assertEquals(5.0, m.generation)
        assertEquals(1.0, m.schemaVersion)

        // grid/main/solar_dc kept; stale `old` dropped
        assertEquals(setOf("grid", "main", "solar_dc"), m.buses.keys)
        assertNull("stale-generation bus must be dropped", m.buses["old"])

        assertTrue(m.buses["grid"]!!.containsGrid)
        assertEquals(0.0, m.buses["grid"]!!.depth)
        assertEquals("measured", m.buses["grid"]!!.coverage)
    }

    @Test
    fun normalisesArraysAndQuantities() {
        val m = EnergyModelParser.parse(fixture())
        val feed = m.terminals["main_feed.child"]!!
        assertEquals(900.0, feed.meter.power)              // 3-phase array -> phase-0
        assertEquals(230.0, feed.meter.voltage)            // {q,u} -> q
        assertEquals("measured", feed.meter.sources["power"])

        val ac = m.terminals["inv1_ac"]!!
        assertEquals(-500.0, ac.meter.power)               // {value,..} entry unwrapped
    }

    @Test
    fun groupsAppliancesAndChoosesUpstreamAnchor() {
        val m = EnergyModelParser.parse(fixture())
        val inv = m.appliances["inverter1"]!!
        assertEquals("inverter", inv.type)                 // from owner_kind
        assertEquals("Solar Inverter", inv.name)           // from terminal label
        assertEquals(2, inv.ports.size)
        // The anchor terminal is chosen during forest reconstruction (as in the
        // web model), so it is null until buildForest runs.
        assertNull(inv.anchorPort)

        buildForest(m)
        // anchor is the AC terminal (bus depth 1) not the DC one (depth -1/Infinity)
        assertEquals("inv1_ac", inv.anchorPort!!.id)
    }

    @Test
    fun parsesIslandsAndPlanner() {
        val m = EnergyModelParser.parse(fixture())
        val island = m.primaryIsland()
        assertNotNull(island)
        assertEquals("home", island!!.id)
        assertEquals("on_grid", island.mode)
        assertEquals(listOf("grid", "main"), island.members)
        assertEquals(1000.0, island["account.grid.power"])

        assertEquals(false, m.policies["p1"]!!["satisfied"])
        assertEquals("battery", m.allocations["p1"]!!["target"])
    }

    @Test
    fun buildsGridAnchoredForest() {
        val forest = buildForest(EnergyModelParser.parse(fixture()))

        // single grid-anchored root
        assertEquals(1, forest.roots.size)
        val grid = forest.roots[0]
        assertEquals("grid", grid.id)
        assertTrue(grid.isRoot)
        assertEquals("bus", grid.kind)
        assertEquals(1000.0, grid.power)                   // own balance (no feed)
        assertEquals("on_grid", grid.islandMode)           // resolved via island members

        // grid -> main (via breaker branch), throughput from the feeding terminal
        val main = grid.child("main")!!
        assertEquals("bus", main.kind)
        assertEquals(900.0, main.power)                    // feed power, not the ~0 balance
        assertEquals(63.0, main.edgeCapacity)              // breaker rating on the edge
        assertEquals("breaker", main.edgeKind)

        // main -> inverter (appliance, anchored at the AC terminal)
        val inv = main.child("inverter1")!!
        assertEquals("appliance", inv.kind)
        assertEquals("inverter", inv.applianceType)
        assertEquals(-500.0, inv.power)

        // inverter -> solar_dc (DC bus reached through its appliance, power from incoming edge)
        val dc = inv.child("solar_dc")!!
        assertEquals("bus", dc.kind)
        assertEquals(-520.0, dc.power)                     // fallback to the incoming edge power
        assertFalse(dc.isRoot)

        // index covers every node by key
        assertNotNull(forest.index["bus:grid"])
        assertNotNull(forest.index["appliance:inverter1"])
        assertNotNull(forest.index["bus:solar_dc"])
    }
}
