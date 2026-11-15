package com.henw.crunchometer

import com.henw.crunchometer.data.BoincData
import com.henw.crunchometer.data.Counts
import com.henw.crunchometer.data.Cpu
import com.henw.crunchometer.data.Earnings
import com.henw.crunchometer.data.Gpu
import com.henw.crunchometer.data.GridcoinData
import com.henw.crunchometer.data.Project
import com.henw.crunchometer.data.Section
import com.henw.crunchometer.data.Summary
import com.henw.crunchometer.data.SystemData
import com.henw.crunchometer.ui.FleetTotals
import com.henw.crunchometer.ui.MachineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetTotalsTest {
    private fun machine(
        running: Int = 2,
        waiting: Int = 3,
        errors: Int = 0,
        rac: Double = 1000.0,
        cpid: String? = null,
        grcPerDay: Double? = null,
        cpuC: Double? = 60.0,
        gpus: List<Gpu> = emptyList(),
    ) = MachineState(
        summary = Summary(
            boinc = Section(ok = true, data = BoincData(
                projects = listOf(Project(name = "a", hostRac = rac)),
                counts = Counts(running = running, waiting = waiting, errors = errors),
            )),
            gridcoin = Section(ok = true, data = GridcoinData(
                cpid = cpid,
                earnings = grcPerDay?.let { Earnings(perDay = it) },
            )),
            system = Section(ok = true, data = SystemData(cpu = Cpu(tempC = cpuC), gpus = gpus)),
        ),
    )

    @Test
    fun sumsOnlineMachines() {
        val f = FleetTotals.of(listOf(
            "a" to machine(running = 2, waiting = 1, rac = 1000.0, gpus = listOf(Gpu(powerW = 150.0, tempC = 70.0))),
            "b" to machine(running = 4, waiting = 0, rac = 500.0, gpus = listOf(Gpu(powerW = 200.0, tempC = 65.0))),
        ))
        assertEquals(2, f.machines)
        assertEquals(2, f.online)
        assertEquals(6, f.running)
        assertEquals(1, f.queued)
        assertEquals(1500.0, f.hostRac, 0.0)
        assertEquals(350.0, f.gpuPowerW!!, 0.0)
        assertEquals(FleetTotals.Hottest("a", "GPU", 70.0), f.hottest)
        assertFalse(f.needsAttention)
    }

    @Test
    fun offlineMachinesDontCountEvenWithStaleData() {
        val stale = machine(running = 10, rac = 9999.0).copy(error = "timeout")
        val f = FleetTotals.of(listOf("a" to machine(running = 1), "b" to stale, "c" to MachineState()))
        assertEquals(3, f.machines)
        assertEquals(1, f.online)
        assertEquals(1, f.running)
        assertEquals(listOf("b"), f.offline)
        assertTrue(f.needsAttention)
    }

    @Test
    fun earningsCountEachWalletOnce() {
        val f = FleetTotals.of(listOf(
            "a" to machine(cpid = "abc", grcPerDay = 2.0),
            "b" to machine(cpid = "abc", grcPerDay = 2.0),
            "c" to machine(cpid = "def", grcPerDay = 1.5),
            "d" to machine(),
        ))
        assertEquals(3.5, f.grcPerDay!!, 1e-9)
    }

    @Test
    fun noEarningsOrPowerIsNull() {
        val f = FleetTotals.of(listOf("a" to machine(cpuC = null)))
        assertNull(f.grcPerDay)
        assertNull(f.gpuPowerW)
        assertNull(f.hottest)
    }

    @Test
    fun flagsIdleAndErroringMachines() {
        val f = FleetTotals.of(listOf(
            "a" to machine(running = 0),
            "b" to machine(errors = 3),
            "c" to machine(),
        ))
        assertEquals(listOf("a"), f.idle)
        assertEquals(listOf("b"), f.withErrors)
        assertEquals(3, f.errors)
    }

    @Test
    fun multiGpuPartNames() {
        val f = FleetTotals.of(listOf(
            "a" to machine(cpuC = 50.0, gpus = listOf(Gpu(index = 0, tempC = 60.0), Gpu(index = 1, tempC = 80.0))),
        ))
        assertEquals(FleetTotals.Hottest("a", "GPU 1", 80.0), f.hottest)
    }
}
