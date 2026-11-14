package com.henw.crunchometer.alerts

import com.henw.crunchometer.data.AlertSettings
import com.henw.crunchometer.data.Summary
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

enum class AlertKind { Offline, OutOfWork, TaskErrors, TooHot }

/** One notification to post. [clear] means "this condition is over": post it, replacing the old one. */
data class Alert(val kind: AlertKind, val title: String, val text: String, val clear: Boolean = false)

/**
 * What the last background check saw on one machine, so each problem notifies once
 * when it starts (and, for offline, once when it ends) instead of on every check.
 */
@Serializable
data class MachineAlertState(
    val failures: Int = 0,
    val offline: Boolean = false,
    val outOfWork: Boolean = false,
    val hot: Boolean = false,
    /** Names of failed tasks already seen; null until the first successful check. */
    val seenErrors: Set<String>? = null,
)

object AlertRules {
    /** Failed checks in a row before a machine counts as offline: one blip on a phone's network isn't enough. */
    const val FAILURES_FOR_OFFLINE = 2

    /** The temperature has to drop this far below the limit before "too hot" can fire again. */
    const val HOT_HYSTERESIS_C = 5

    private val FAILED = setOf("error", "upload failed")  // not "aborted": usually done on purpose

    /**
     * Compares one check's [result] against [prev]. State is tracked for every alert type even
     * when it's off, so turning one on later doesn't fire for something that started long ago.
     */
    fun evaluate(
        name: String,
        prev: MachineAlertState,
        result: Result<Summary>,
        on: AlertSettings,
    ): Pair<MachineAlertState, List<Alert>> {
        val alerts = mutableListOf<Alert>()
        val summary = result.getOrElse { err ->
            val failures = prev.failures + 1
            val nowOffline = failures >= FAILURES_FOR_OFFLINE
            if (nowOffline && !prev.offline && on.offline) {
                alerts += Alert(AlertKind.Offline, "$name is offline", err.message ?: "Couldn't reach the server")
            }
            // Offline: nothing else can be judged, so the other states stay as they were.
            return prev.copy(failures = failures, offline = prev.offline || nowOffline) to alerts
        }

        if (prev.offline && on.offline) {
            alerts += Alert(AlertKind.Offline, "$name is back online", "The server is answering again.", clear = true)
        }

        // Out of work: no task running. A BOINC client that isn't answering counts too.
        val boinc = summary.boinc
        val counts = boinc.data?.counts
        val running = if (boinc.ok) counts?.running ?: 0 else 0
        val outOfWork = running == 0
        if (outOfWork && !prev.outOfWork && on.outOfWork) {
            val queued = counts?.let { it.waiting + it.suspended + it.transferring } ?: 0
            alerts += Alert(
                AlertKind.OutOfWork,
                "$name has no tasks running",
                when {
                    !boinc.ok -> "BOINC isn't answering: ${boinc.error ?: "unknown error"}"
                    queued > 0 -> "$queued queued, but none running. Is computing suspended?"
                    else -> "Nothing queued either. The projects may be out of work."
                },
            )
        }

        // Task errors: only ones that weren't there at the last check.
        val failed = boinc.data?.completed.orEmpty().filter { it.status in FAILED }
        val seen = prev.seenErrors
        val newFailures = if (seen == null || !boinc.ok) emptyList() else failed.filter { it.name !in seen }
        if (newFailures.isNotEmpty() && on.taskErrors) {
            val n = newFailures.size
            alerts += Alert(
                AlertKind.TaskErrors,
                if (n == 1) "A task failed on $name" else "$n tasks failed on $name",
                newFailures.joinToString(", ") { it.project.ifBlank { it.name } }.take(200),
            )
        }
        // Keep the old set while BOINC isn't answering, so its errors aren't "new" when it's back.
        val seenNext = if (boinc.ok) failed.map { it.name }.toSet() else seen

        // Too hot: CPU or any GPU's main sensor (not AMD's hotspot, which normally runs ~20 °C higher).
        val sys = summary.system.data
        val gpus = sys?.gpus.orEmpty()
        val temps = buildList {
            sys?.cpu?.tempC?.let { add("CPU" to it) }
            gpus.forEach { g -> g.tempC?.let { add((if (gpus.size > 1) "GPU ${g.index}" else "GPU") to it) } }
        }
        val hottest = temps.maxByOrNull { it.second }
        val hot = when {
            hottest == null -> prev.hot
            hottest.second >= on.maxTempC -> true
            hottest.second < on.maxTempC - HOT_HYSTERESIS_C -> false
            else -> prev.hot
        }
        if (hot && !prev.hot && on.tooHot && hottest != null) {
            alerts += Alert(
                AlertKind.TooHot,
                "$name is running hot",
                "${hottest.first} at ${hottest.second.roundToInt()} °C (limit ${on.maxTempC} °C)",
            )
        }

        return MachineAlertState(
            failures = 0,
            offline = false,
            outOfWork = outOfWork,
            hot = hot,
            seenErrors = seenNext,
        ) to alerts
    }
}
