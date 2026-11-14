package com.henw.crunchometer.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.henw.crunchometer.MainActivity
import com.henw.crunchometer.R
import com.henw.crunchometer.data.ApiClient
import com.henw.crunchometer.data.AppSettings
import com.henw.crunchometer.data.Summary
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Runs the alert rules after a background check and posts what they produce. */
object Notifier {
    private val stateSerializer = MapSerializer(String.serializer(), MachineAlertState.serializer())

    /** [results] is keyed by Server.id; machines missing from it weren't checked this time. */
    fun check(context: Context, settings: AppSettings, results: Map<String, Result<Summary>>) {
        if (!settings.alerts.anyOn) return
        val prefs = context.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
        val old = prefs.getString("machines", null)
            ?.let { runCatching { ApiClient.json.decodeFromString(stateSerializer, it) }.getOrNull() }
            .orEmpty()
        val next = mutableMapOf<String, MachineAlertState>()
        for (server in settings.servers) {
            val prev = old[server.id] ?: MachineAlertState()
            val result = results[server.id]
            if (result == null) {
                next[server.id] = prev
                continue
            }
            val name = server.displayName(result.getOrNull()?.system?.data?.hostname)
            val (state, alerts) = AlertRules.evaluate(name, prev, result, settings.alerts)
            next[server.id] = state  // deleted machines drop out here
            alerts.forEach { post(context, server.id, it) }
        }
        prefs.edit { putString("machines", ApiClient.json.encodeToString(stateSerializer, next)) }
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun post(context: Context, serverId: String, alert: Alert) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, alert.kind.channelId)
            .setSmallIcon(R.drawable.ic_machines)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (alert.clear) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // One slot per machine and kind: "back online" replaces "offline", a new error batch the last one.
        try {
            NotificationManagerCompat.from(context).notify("$serverId/${alert.kind}", 0, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and here.
        }
    }

    private val AlertKind.channelId get() = "alert_" + name.lowercase()

    private val AlertKind.channelName
        get() = when (this) {
            AlertKind.Offline -> "Machine offline"
            AlertKind.OutOfWork -> "Out of work"
            AlertKind.TaskErrors -> "Task errors"
            AlertKind.TooHot -> "Temperature"
        }

    /** One channel per kind, so each can be silenced or tuned in Android's own settings. */
    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        AlertKind.entries.forEach {
            nm.createNotificationChannel(NotificationChannel(it.channelId, it.channelName, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
