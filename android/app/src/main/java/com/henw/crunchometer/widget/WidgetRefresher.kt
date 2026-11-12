package com.henw.crunchometer.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.henw.crunchometer.data.ApiClient
import com.henw.crunchometer.data.ApiException
import com.henw.crunchometer.data.SettingsStore
import com.henw.crunchometer.data.Summary
import java.util.concurrent.TimeUnit

object WidgetRefresher {
    private const val PERIODIC = "widget-refresh"
    private const val NOW = "widget-refresh-now"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Every 15 minutes, the shortest period Android allows for background work. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build(),
        )
        refreshSoon(context)
    }

    fun refreshSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setConstraints(constraints).build(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
    }

    /** Fetches from the server and pushes the result to every widget. */
    suspend fun fetchAndUpdate(context: Context) {
        val settings = SettingsStore(context).load()
        if (!settings.isConfigured) {
            push(context) { it.copy(error = "Set up the app first") }
            return
        }
        try {
            val summary = ApiClient().fetchSummary(settings.serverUrl, settings.token)
            push(context, summary)
        } catch (e: ApiException) {
            // Keep the last numbers; just flag that they're stale.
            push(context) { it.copy(error = e.message ?: "Couldn't reach the server") }
        }
    }

    /** Called by the app after its own refresh so the widget doesn't wait for the next 15-minute run. */
    suspend fun push(context: Context, summary: Summary) {
        val fresh = summary.toSnapshot()
        push(context) { fresh }
    }

    private suspend fun push(context: Context, change: (WidgetSnapshot) -> WidgetSnapshot) {
        val manager = GlanceAppWidgetManager(context)
        val widget = CrunchWidget()
        for (id in manager.getGlanceIds(CrunchWidget::class.java)) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs: Preferences ->
                val next = change(prefs.widgetSnapshot() ?: WidgetSnapshot())
                prefs.toMutablePreferences().apply { this[WidgetKeys.SNAPSHOT] = next.encode() }
            }
            widget.update(context, id)
        }
    }

    suspend fun hasWidgets(context: Context): Boolean =
        GlanceAppWidgetManager(context).getGlanceIds(CrunchWidget::class.java).isNotEmpty()
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetRefresher.fetchAndUpdate(applicationContext)
        return Result.success()
    }
}
