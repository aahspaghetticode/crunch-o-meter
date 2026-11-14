package com.henw.crunchometer

import com.henw.crunchometer.data.AppSettings
import com.henw.crunchometer.data.Server
import com.henw.crunchometer.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun migratesSingleServerFromOldVersions() {
        val servers = SettingsStore.decodeServers(null, "https://box.tail1234.ts.net", "tok")
        val only = servers.single()
        assertEquals("https://box.tail1234.ts.net", only.url)
        assertEquals("tok", only.token)
        assertTrue(only.id.isNotBlank())
    }

    @Test
    fun nothingToMigrate() {
        assertTrue(SettingsStore.decodeServers(null, null, null).isEmpty())
        assertTrue(SettingsStore.decodeServers(null, "", "tok").isEmpty())
    }

    @Test
    fun listRoundTripsAndWinsOverLegacyKeys() {
        val list = listOf(Server(id = "a", name = "Rig", url = " https://a.ts.net ", token = "t1 "), Server(id = "b", url = "b", token = "t2"))
        val decoded = SettingsStore.decodeServers(SettingsStore.encodeServers(list), "https://old", "old")
        assertEquals(listOf("a", "b"), decoded.map { it.id })
        assertEquals("https://a.ts.net", decoded[0].url)  // trimmed on save
        assertEquals("t1", decoded[0].token)
    }

    @Test
    fun corruptListFallsBackToLegacy() {
        assertEquals("https://old", SettingsStore.decodeServers("{nope", "https://old", "t").single().url)
    }

    @Test
    fun selectionFallsBackToFirst() {
        val s = AppSettings(listOf(Server(id = "a"), Server(id = "b")), selectedId = "gone")
        assertEquals("a", s.selected!!.id)
        assertEquals("b", s.serverFor("b")!!.id)
        assertEquals("a", s.serverFor(null)!!.id)
        assertNull(AppSettings().selected)
        assertFalse(AppSettings(listOf(Server(url = "x"))).isConfigured)
    }

    @Test
    fun displayName() {
        val s = Server(url = "https://cruncher.tail1234.ts.net")
        assertEquals("cruncher", s.displayName())
        assertEquals("cruncher-host", s.displayName("cruncher-host"))
        assertEquals("Garage", s.copy(name = " Garage ").displayName("cruncher-host"))
        assertEquals("box", Server(url = "box.tail1234.ts.net:8765").displayName())
    }
}
