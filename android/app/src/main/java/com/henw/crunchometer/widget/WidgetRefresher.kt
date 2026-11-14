package com.henw.crunchometer.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
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
import com.henw.crunchometer.data.AppSettings
import com.henw.crunchometer.data.Server
import com.henw.crunchometer.data.SettingsStore
import com.henw.crunchometer.data.Summary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /** Fetches each machine that some widget shows, once, and pushes the results to those widgets. */
    suspend fun fetchAndUpdate(context: Context) {
        val settings = SettingsStore(context).load()
        val manager = GlanceAppWidgetManager(context)
        val wanted = manager.getGlanceIds(CrunchWidget::class.java).mapNotNull { id ->
            val prefs: Preferences = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
            settings.serverFor(prefs[WidgetKeys.SERVER_ID])
        }.distinctBy { it.id }.filter { it.isConfigured }

        val api = ApiClient()
        val results: Map<String, Result<Summary>> = coroutineScope {
            wanted.map { server ->
                async {
                    server.id to try {
                        Result.success(api.fetchSummary(server.url, server.token))
                    } catch (e: ApiException) {
                        Result.failure(e)
                    }
                }
            }.awaitAll().toMap()
        }

        update(context, settings) { server, old ->
            val result = server?.let { results[it.id] }
            when {
                server == null || !server.isConfigured -> old.copy(error = "Set up the app first")
                result == null -> null
                // Keep the last numbers; just flag that they're stale.
                else -> result.fold(
                    onSuccess = { it.toSnapshot(machine = machineLabel(settings, server, it)) },
                    onFailure = { old.copy(error = it.message ?: "Couldn't reach the server") },
                )
            }
        }
    }

    /** Called by the app after its own refresh so widgets don't wait for the next 15-minute run. */
    suspend fun push(context: Context, serverId: String, summary: Summary) {
        val settings = SettingsStore(context).load()
        update(context, settings) { server, _ ->
            if (server?.id == serverId) summary.toSnapshot(machine = machineLabel(settings, server, summary)) else null
        }
    }

    /** Names the machine on the widget only when there's more than one to tell apart. */
    private fun machineLabel(settings: AppSettings, server: Server, summary: Summary): String? =
        if (settings.servers.size > 1) server.displayName(summary.system.data?.hostname) else null

    /** Runs [change] for each widget with the machine it shows; null leaves that widget alone. */
    private suspend fun update(
        context: Context,
        settings: AppSettings,
        change: (Server?, WidgetSnapshot) -> WidgetSnapshot?,
    ) {
        val manager = GlanceAppWidgetManager(context)
        val widget = CrunchWidget()
        for (id in manager.getGlanceIds(CrunchWidget::class.java)) {
            var changed = false
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs: Preferences ->
                val server = settings.serverFor(prefs[WidgetKeys.SERVER_ID])
                val next = change(server, prefs.widgetSnapshot() ?: WidgetSnapshot())
                if (next == null) prefs
                else {
                    changed = true
                    prefs.toMutablePreferences().apply { this[WidgetKeys.SNAPSHOT] = next.encode() }
                }
            }
            if (changed) widget.update(context, id)
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
