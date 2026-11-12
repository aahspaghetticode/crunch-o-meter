package com.henw.crunchometer

import com.henw.crunchometer.data.ApiClient
import com.henw.crunchometer.data.Donations
import com.henw.crunchometer.data.Summary
import com.henw.crunchometer.ui.components.formatBytes
import com.henw.crunchometer.ui.components.formatDuration
import com.henw.crunchometer.widget.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sample_summary.json is written by the server's test suite
 * (server/tests/test_api.py::test_write_sample_for_android), so this test breaks
 * if the server's JSON and the Kotlin models drift apart.
 */
class ModelsTest {
    private fun sample(): Summary {
        val text = javaClass.classLoader!!.getResource("sample_summary.json")!!.readText()
        return ApiClient.json.decodeFromString(Summary.serializer(), text)
    }

    @Test
    fun decodesServerResponse() {
        val s = sample()
        assertTrue(s.boinc.ok && s.gridcoin.ok && s.system.ok && s.lact.ok)

        val boinc = requireData(s.boinc.data)
        val running = boinc.active.first()
        assertEquals("running", running.status)
        assertEquals(42.315, running.progressPercent, 0.001)
        assertEquals(3, boinc.completed.size)
        assertEquals(1, boinc.counts.errors)

        val grc = requireData(s.gridcoin.data)
        assertEquals("wallet", grc.source)
        assertEquals(187.43, grc.magnitude!!, 0.001)

        val lact = requireData(s.lact.data)
        assertEquals("NVIDIA GeForce RTX 4090", lact.gpus.single().name)
        assertEquals("power_cap", lact.gpus.single().settings.first().key)

        assertTrue(requireData(s.system.data).cpu.perCore.isNotEmpty())
    }

    @Test
    fun widgetSnapshot() {
        val w = sample().toSnapshot(nowMs = 1L)
        assertEquals(1, w.running)
        assertEquals(1, w.queued)
        assertEquals("Einstein@Home", w.topProject)
        assertEquals(42.315, w.topProgress!!, 0.001)
        assertEquals(187.43, w.magnitude!!, 0.001)
        assertFalse(w.magnitudeIsEstimate)
    }

    @Test
    fun toleratesMissingAndExtraFields() {
        val s = ApiClient.json.decodeFromString(
            Summary.serializer(),
            """{"version":"9","new_field":1,"boinc":{"ok":true,"data":{"active":[{"name":"x","progress_percent":null}]}}}""",
        )
        assertEquals(0.0, s.boinc.data!!.active.single().progressPercent, 0.0)
        assertFalse(s.lact.ok)
    }

    @Test
    fun devCode() {
        assertTrue(Donations.isDevCode("stfu-bro"))
        assertTrue(Donations.isDevCode("  STFU-bro "))
        assertFalse(Donations.isDevCode("stfu bro"))
    }

    @Test
    fun formatting() {
        assertEquals("—", formatDuration(null))
        assertEquals("45s", formatDuration(45.0))
        assertEquals("21m 40s", formatDuration(1300.2))
        assertEquals("2h 5m", formatDuration(7500.0))
        assertEquals("1d 3h", formatDuration(97_200.0))
        assertEquals("1.5 GiB", formatBytes(1_610_612_736))
    }

    private fun <T : Any> requireData(value: T?): T {
        assertNotNull("section data missing", value)
        return value!!
    }
}
