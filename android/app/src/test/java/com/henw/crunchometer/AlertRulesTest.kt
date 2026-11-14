package com.henw.crunchometer

import com.henw.crunchometer.alerts.AlertKind
import com.henw.crunchometer.alerts.AlertRules
import com.henw.crunchometer.alerts.MachineAlertState
import com.henw.crunchometer.data.AlertSettings
import com.henw.crunchometer.data.ApiClient
import com.henw.crunchometer.data.ApiException
import com.henw.crunchometer.data.CompletedTask
import com.henw.crunchometer.data.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest {
    private val allOn = AlertSettings(offline = true, outOfWork = true, taskErrors = true, tooHot = true, maxTempC = 85)

    /** The server's sample response, with the temperatures pinned (the CPU one is the test machine's own). */
    private fun sample(cpuC: Double = 60.0, gpuC: Double = 61.0): Summary {
        val text = javaClass.classLoader!!.getResource("sample_summary.json")!!.readText()
        val s = ApiClient.json.decodeFromString(Summary.serializer(), text)
        val sys = s.system.data!!
        return s.copy(system = s.system.copy(data = sys.copy(
            cpu = sys.cpu.copy(tempC = cpuC),
            gpus = sys.gpus.map { it.copy(tempC = gpuC) },
        )))
    }

    private fun run(prev: MachineAlertState, s: Summary, on: AlertSettings = allOn) =
        AlertRules.evaluate("rig", prev, Result.success(s), on)

    private fun fail(prev: MachineAlertState, on: AlertSettings = allOn) =
        AlertRules.evaluate("rig", prev, Result.failure(ApiException("Can't resolve host")), on)

    /** A machine after one quiet check: nothing wrong, errors seen. */
    private fun settled() = run(MachineAlertState(), sample()).first

    @Test
    fun firstCheckOnlyRecordsOldErrors() {
        val (state, alerts) = run(MachineAlertState(), sample())
        assertTrue(alerts.isEmpty())
        assertEquals(setOf("MCM1_bad_4"), state.seenErrors)
    }

    @Test
    fun offlineNeedsTwoFailuresAndNotifiesOnce() {
        val (one, a1) = fail(settled())
        assertTrue(a1.isEmpty())
        val (two, a2) = fail(one)
        assertEquals(listOf(AlertKind.Offline), a2.map { it.kind })
        assertEquals("rig is offline", a2.single().title)
        val (three, a3) = fail(two)
        assertTrue(a3.isEmpty())
        val (_, back) = run(three, sample())
        assertTrue(back.single().clear)
        assertEquals("rig is back online", back.single().title)
    }

    @Test
    fun oneBlipResetsTheCount() {
        val (blip, _) = fail(settled())
        val (ok, _) = run(blip, sample())
        assertEquals(0, ok.failures)
        assertTrue(fail(ok).second.isEmpty())
    }

    @Test
    fun outOfWorkOnceUntilTasksRunAgain() {
        val s = sample()
        val idle = s.copy(boinc = s.boinc.copy(data = s.boinc.data!!.let { it.copy(counts = it.counts.copy(running = 0)) }))
        val (st, alerts) = run(settled(), idle)
        assertEquals("rig has no tasks running", alerts.single().title)
        assertTrue(alerts.single().text.startsWith("1 queued"))
        assertTrue(run(st, idle).second.isEmpty())
        val (busy, _) = run(st, s)
        assertEquals(1, run(busy, idle).second.size)
    }

    @Test
    fun boincDownCountsAsOutOfWork() {
        val s = sample()
        val down = s.copy(boinc = s.boinc.copy(ok = false, error = "connection refused"))
        val alert = run(settled(), down).second.single()
        assertEquals(AlertKind.OutOfWork, alert.kind)
        assertTrue("connection refused" in alert.text)
    }

    @Test
    fun onlyNewTaskErrorsNotify() {
        val s = sample()
        val data = s.boinc.data!!
        val more = s.copy(boinc = s.boinc.copy(data = data.copy(completed = data.completed +
            CompletedTask(name = "new_bad", project = "Einstein@Home", status = "error") +
            CompletedTask(name = "aborted_one", project = "X", status = "aborted"))))
        val (st, alerts) = run(settled(), more)
        assertEquals("A task failed on rig", alerts.single().title)
        assertEquals("Einstein@Home", alerts.single().text)
        assertTrue(run(st, more).second.isEmpty())
    }

    @Test
    fun tooHotWithHysteresis() {
        val (hot, a1) = run(settled(), sample(gpuC = 90.0))
        assertEquals("GPU at 90 °C (limit 85 °C)", a1.single().text)
        assertTrue(run(hot, sample(gpuC = 82.0)).second.isEmpty())   // still within 5 °C: no re-arm
        val (still, _) = run(hot, sample(gpuC = 82.0))
        assertTrue(run(still, sample(gpuC = 88.0)).second.isEmpty())
        val (cool, _) = run(still, sample(gpuC = 70.0))
        assertEquals(1, run(cool, sample(cpuC = 86.0)).second.size)
    }

    @Test
    fun offAlertsStillTrackState() {
        // Turning an alert on later mustn't fire for a problem that was already there.
        val off = AlertSettings()
        val (hot, alerts) = run(settled(), sample(gpuC = 95.0), off)
        assertTrue(alerts.isEmpty())
        assertTrue(run(hot, sample(gpuC = 95.0)).second.isEmpty())
    }
}
