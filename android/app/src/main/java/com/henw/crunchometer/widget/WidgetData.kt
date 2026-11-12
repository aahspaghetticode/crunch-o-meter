package com.henw.crunchometer.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.henw.crunchometer.data.ApiClient
import com.henw.crunchometer.data.Summary
import kotlinx.serialization.Serializable

/** The few numbers the widget shows, cut down from the full API summary. */
@Serializable
data class WidgetSnapshot(
    val updatedAtMs: Long = 0,
    val error: String? = null,
    val running: Int = 0,
    val queued: Int = 0,
    val topProject: String? = null,
    val topProgress: Double? = null,
    val magnitude: Double? = null,
    val magnitudeIsEstimate: Boolean = false,
    val cpuPercent: Double? = null,
    val gpuPercent: Double? = null,
    val gpuTempC: Double? = null,
)

fun Summary.toSnapshot(nowMs: Long = System.currentTimeMillis()): WidgetSnapshot {
    val b = boinc.data
    val top = b?.active?.firstOrNull { it.status == "running" }
    val gpu = system.data?.gpus?.firstOrNull()
    val grc = gridcoin.data
    return WidgetSnapshot(
        updatedAtMs = nowMs,
        running = b?.counts?.running ?: 0,
        queued = b?.counts?.let { it.waiting + it.transferring + it.suspended } ?: 0,
        topProject = top?.project,
        topProgress = top?.progressPercent,
        magnitude = grc?.magnitude,
        magnitudeIsEstimate = grc?.source == "estimate",
        cpuPercent = system.data?.cpu?.usagePercent,
        gpuPercent = gpu?.utilPercent,
        gpuTempC = gpu?.tempC,
    )
}

enum class TextTone { System, Light, Dark }

/** Per-widget settings, chosen in WidgetConfigActivity. */
data class WidgetConfig(
    val showTasks: Boolean = true,
    val showMagnitude: Boolean = true,
    val showCpu: Boolean = true,
    val showGpu: Boolean = true,
    /** Background opacity 0..100. 0 = fully transparent. */
    val opacity: Int = 0,
    val tone: TextTone = TextTone.System,
)

/** Keys in each widget's Glance state (a Preferences DataStore per widget). */
object WidgetKeys {
    val SNAPSHOT = stringPreferencesKey("snapshot")
    val SHOW_TASKS = booleanPreferencesKey("show_tasks")
    val SHOW_MAGNITUDE = booleanPreferencesKey("show_magnitude")
    val SHOW_CPU = booleanPreferencesKey("show_cpu")
    val SHOW_GPU = booleanPreferencesKey("show_gpu")
    val OPACITY = intPreferencesKey("opacity")
    val TONE = stringPreferencesKey("tone")
}

fun Preferences.widgetConfig(): WidgetConfig {
    val d = WidgetConfig()
    return WidgetConfig(
        showTasks = this[WidgetKeys.SHOW_TASKS] ?: d.showTasks,
        showMagnitude = this[WidgetKeys.SHOW_MAGNITUDE] ?: d.showMagnitude,
        showCpu = this[WidgetKeys.SHOW_CPU] ?: d.showCpu,
        showGpu = this[WidgetKeys.SHOW_GPU] ?: d.showGpu,
        opacity = (this[WidgetKeys.OPACITY] ?: d.opacity).coerceIn(0, 100),
        tone = this[WidgetKeys.TONE]?.let { name -> TextTone.entries.firstOrNull { it.name == name } } ?: d.tone,
    )
}

fun Preferences.widgetSnapshot(): WidgetSnapshot? =
    this[WidgetKeys.SNAPSHOT]?.let {
        runCatching { ApiClient.json.decodeFromString(WidgetSnapshot.serializer(), it) }.getOrNull()
    }

fun WidgetSnapshot.encode(): String = ApiClient.json.encodeToString(WidgetSnapshot.serializer(), this)
