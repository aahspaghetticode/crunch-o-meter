package com.henw.crunchometer.ui

/** Totals across every machine, for the top of the Machines tab. Only online machines count. */
data class FleetTotals(
    val machines: Int = 0,
    val online: Int = 0,
    val running: Int = 0,
    val queued: Int = 0,
    val errors: Int = 0,
    val hostRac: Double = 0.0,
    /** Null when no machine has an earning rate yet. */
    val grcPerDay: Double? = null,
    /** GPU board power only; the server doesn't read CPU or wall power. Null when no GPU reports it. */
    val gpuPowerW: Double? = null,
    /** Hottest CPU or GPU reading in the fleet. */
    val hottest: Hottest? = null,
    val offline: List<String> = emptyList(),
    /** Online, BOINC answering, but nothing running. */
    val idle: List<String> = emptyList(),
    val withErrors: List<String> = emptyList(),
) {
    data class Hottest(val machine: String, val part: String, val tempC: Double)

    val needsAttention: Boolean get() = offline.isNotEmpty() || idle.isNotEmpty() || withErrors.isNotEmpty()

    companion object {
        fun of(machines: List<Pair<String, MachineState>>): FleetTotals {
            var t = FleetTotals(machines = machines.size)
            val offline = mutableListOf<String>()
            val idle = mutableListOf<String>()
            val withErrors = mutableListOf<String>()
            // Earnings come from a wallet. Two machines pointing at the same wallet (same CPID)
            // would report the same rate, so count each CPID once.
            val earningsSeen = mutableSetOf<String>()
            var grc: Double? = null
            var power: Double? = null
            var hottest: Hottest? = null

            for ((name, m) in machines) {
                val s = m.summary
                if (m.error != null) { offline += name; continue }
                if (s == null) continue  // still connecting
                t = t.copy(online = t.online + 1)

                s.boinc.data?.takeIf { s.boinc.ok }?.let { b ->
                    val c = b.counts
                    t = t.copy(
                        running = t.running + c.running,
                        queued = t.queued + c.waiting + c.transferring + c.suspended,
                        errors = t.errors + c.errors,
                        hostRac = t.hostRac + b.projects.sumOf { it.hostRac },
                    )
                    if (c.running == 0) idle += name
                    if (c.errors > 0) withErrors += name
                }

                s.gridcoin.data?.let { g ->
                    val rate = g.earnings?.perDay ?: return@let
                    val key = g.cpid ?: "machine:$name"
                    if (earningsSeen.add(key)) grc = (grc ?: 0.0) + rate
                }

                s.system.data?.let { sys ->
                    sys.gpus.mapNotNull { it.powerW }.takeIf { it.isNotEmpty() }?.let { power = (power ?: 0.0) + it.sum() }
                    val parts = buildList {
                        sys.cpu.tempC?.let { add("CPU" to it) }
                        sys.gpus.forEach { g ->
                            g.tempC?.let { add((if (sys.gpus.size > 1) "GPU ${g.index}" else "GPU") to it) }
                        }
                    }
                    parts.maxByOrNull { it.second }?.let { (part, temp) ->
                        if (hottest == null || temp > hottest!!.tempC) hottest = Hottest(name, part, temp)
                    }
                }
            }
            return t.copy(
                grcPerDay = grc,
                gpuPowerW = power,
                hottest = hottest,
                offline = offline,
                idle = idle,
                withErrors = withErrors,
            )
        }
    }
}
