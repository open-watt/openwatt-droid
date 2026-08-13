package com.openwatt.droid.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SyslogParseTest {

    @Test
    fun `rfc5424 line parses severity, tag and message`() {
        val raw = "<11>1 2026-08-14T02:33:12Z gateway modbus 412 - - read timeout on unit 3"
        val e = SyncClient.parseSyslog(raw, 1, 0)
        assertEquals("error", e.severity)
        assertEquals(3, e.severityCode)
        assertEquals("modbus", e.tag)
        assertEquals("read timeout on unit 3", e.message)
    }

    @Test
    fun `structured data is stripped from the message`() {
        val raw = "<14>1 - host app - - [meta seq=\"42\"] link up"
        val e = SyncClient.parseSyslog(raw, 1, 0)
        assertEquals("info", e.severity)
        assertEquals("link up", e.message)
    }

    @Test
    fun `trace ships as debug plus a trace SD element`() {
        val raw = "<15>1 - host sync - - [trace] fine-grained detail"
        val e = SyncClient.parseSyslog(raw, 1, 0)
        assertEquals("trace", e.severity)
        assertEquals(8, e.severityCode)
    }

    @Test
    fun `unparseable line falls back to raw`() {
        val raw = "not a syslog line at all"
        val e = SyncClient.parseSyslog(raw, 1, 0)
        assertEquals("unknown", e.severity)
        assertEquals(raw, e.message)
    }
}
