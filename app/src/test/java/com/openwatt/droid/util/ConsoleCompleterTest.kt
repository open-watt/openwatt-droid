package com.openwatt.droid.util

import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.PropertySchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleCompleterTest {

    private fun prop(
        type: String,
        access: String = "rw",
        flags: String? = null,
    ) = PropertySchema(type = listOf(type), access = access, default = null, category = null, flags = flags)

    private val modbusServer = CollectionSchema(
        path = "/interface/modbus",
        properties = mapOf(
            "name" to prop("str"),
            "port" to prop("uint"),
            "enabled" to prop("bool"),
            "mode" to prop("enum_ModbusMode"),
            "stream" to prop("#Stream"),
            "rx-rate" to prop("num", access = "r"),
            "secret" to prop("str", flags = "H"),
        ),
        collection = true,
        abstract = false,
        collectionId = 3,
        typeName = "ModbusServer",
    )

    private val stream = CollectionSchema(
        path = "/stream",
        properties = mapOf("name" to prop("str")),
        collection = true,
        abstract = false,
        collectionId = 4,
        typeName = "Stream",
    )

    private val completer = ConsoleCompleter(object : ConsoleCompleter.DataSource {
        override fun schemas() = listOf(modbusServer, stream)

        override suspend fun listItems(scopePath: String): List<String> = when (scopePath) {
            "/interface/modbus" -> listOf("plant-meter", "inverter")
            "/stream" -> listOf("tcp-1", "serial-a")
            else -> emptyList()
        }

        override suspend fun enumMembers(enumName: String): List<String> = when (enumName) {
            "ModbusMode" -> listOf("rtu", "tcp", "ascii")
            else -> emptyList()
        }
    })

    private fun suggest(line: String) = runBlocking { completer.suggest(line) }

    private fun apply(line: String, s: ConsoleCompleter.Suggestion): String =
        line.replaceRange(s.start, s.end, s.replacement)

    @Test
    fun `empty line offers top-level scopes`() {
        val labels = suggest("").map { it.label }
        assertTrue(labels.contains("interface/"))
        assertTrue(labels.contains("stream/"))
    }

    @Test
    fun `partial scope segment completes and keeps walking`() {
        val s = suggest("/inter").single { it.label == "interface/" }
        assertEquals("/interface/", apply("/inter", s))
    }

    @Test
    fun `collection scope offers commands`() {
        val labels = suggest("/interface/modbus/").map { it.label }
        ConsoleCompleter.COLLECTION_COMMANDS.forEach { assertTrue(it in labels) }
    }

    @Test
    fun `command completion appends a space`() {
        val s = suggest("/interface/modbus/pr").single { it.label == "print" }
        assertEquals("/interface/modbus/print ", apply("/interface/modbus/pr", s))
    }

    @Test
    fun `item commands complete item names first`() {
        val line = "/interface/modbus/set pl"
        val s = suggest(line).single { it.label == "plant-meter" }
        assertEquals("/interface/modbus/set plant-meter ", apply(line, s))
    }

    @Test
    fun `set offers writable keys after the item, not read-only or hidden ones`() {
        val labels = suggest("/interface/modbus/set plant-meter ").map { it.label }
        assertTrue("port=" in labels)
        assertTrue(labels.none { it.startsWith("rx-rate") })   // read-only
        assertTrue(labels.none { it.startsWith("secret") })    // hidden
    }

    @Test
    fun `add offers keys immediately`() {
        val labels = suggest("/interface/modbus/add na").map { it.label }
        assertEquals(listOf("name="), labels)
    }

    @Test
    fun `bool value completes`() {
        val line = "/interface/modbus/add enabled=t"
        val s = suggest(line).single()
        assertEquals("/interface/modbus/add enabled=true ", apply(line, s))
    }

    @Test
    fun `enum value completes from backend members`() {
        val labels = suggest("/interface/modbus/add mode=").map { it.label }
        assertEquals(listOf("rtu", "tcp", "ascii"), labels)
    }

    @Test
    fun `reference value completes with target collection item names`() {
        val labels = suggest("/interface/modbus/add stream=").map { it.label }
        assertEquals(listOf("tcp-1", "serial-a"), labels)
    }

    @Test
    fun `unknown scope yields nothing`() {
        assertTrue(suggest("/nonsense/th").isEmpty())
        assertTrue(suggest("/nonsense/cmd arg").isEmpty())
    }

    @Test
    fun `complete segment still offers the trailing slash`() {
        val line = "/interface/modbus"
        val s = suggest(line).single { it.label == "modbus/" }
        assertEquals("/interface/modbus/", apply(line, s))
    }
}
