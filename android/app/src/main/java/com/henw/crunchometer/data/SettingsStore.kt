package com.henw.crunchometer.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** One machine running the Crunch-o-meter server. */
@Serializable
data class Server(
    val id: String = newId(),
    /** Optional; blank means "use the hostname the server reports". */
    val name: String = "",
    val url: String = "",
    val token: String = "",
) {
    val isConfigured: Boolean get() = url.isNotBlank() && token.isNotBlank()

    /** The name if set, else the server's own hostname, else the host part of the URL. */
    fun displayName(hostname: String? = null): String =
        name.trim().ifBlank { hostname.orEmpty() }.ifBlank { urlHost() }

    private fun urlHost(): String {
        val withScheme = if ("://" in url) url.trim() else "https://${url.trim()}"
        // MagicDNS names are long; the first label is the machine name.
        return withScheme.toHttpUrlOrNull()?.host?.substringBefore('.') ?: url.trim()
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Which background alerts are on. All off until the user turns them on. */
data class AlertSettings(
    val offline: Boolean = false,
    val outOfWork: Boolean = false,
    val taskErrors: Boolean = false,
    val tooHot: Boolean = false,
    /** CPU or GPU temperature that counts as too hot, °C. */
    val maxTempC: Int = 85,
) {
    val anyOn: Boolean get() = offline || outOfWork || taskErrors || tooHot
}

data class AppSettings(
    val servers: List<Server> = emptyList(),
    val selectedId: String? = null,
    val refreshSeconds: Int = 5,
    val alerts: AlertSettings = AlertSettings(),
) {
    val isConfigured: Boolean get() = servers.any { it.isConfigured }

    /** The machine the Tasks/Gridcoin/System tabs show. Falls back to the first one. */
    val selected: Server? get() = serverFor(selectedId)

    /** [id]'s server, or the first one if [id] is unset or was deleted. Widgets use this too. */
    fun serverFor(id: String?): Server? = servers.firstOrNull { it.id == id } ?: servers.firstOrNull()
}

data class DonationState(
    val launches: Int = 0,
    val lastPromptMs: Long = 0,
    val silenced: Boolean = false,
)

/**
 * Plain app-private SharedPreferences. Other apps can't read it, and it is excluded
 * from backups (res/xml/data_extraction_rules.xml), so the tokens stay on this phone.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val legacyUrl = prefs.getString(KEY_LEGACY_URL, null)
        val servers = decodeServers(
            prefs.getString(KEY_SERVERS, null),
            legacyUrl,
            prefs.getString(KEY_LEGACY_TOKEN, null),
        )
        val settings = AppSettings(
            servers = servers,
            selectedId = prefs.getString(KEY_SELECTED, null),
            refreshSeconds = prefs.getInt(KEY_REFRESH, 5),
            alerts = AlertSettings(
                offline = prefs.getBoolean(KEY_ALERT_OFFLINE, false),
                outOfWork = prefs.getBoolean(KEY_ALERT_NO_WORK, false),
                taskErrors = prefs.getBoolean(KEY_ALERT_ERRORS, false),
                tooHot = prefs.getBoolean(KEY_ALERT_HOT, false),
                maxTempC = prefs.getInt(KEY_ALERT_MAX_TEMP, 85),
            ),
        )
        // Versions before multi-machine support kept one server_url/token pair. Move it
        // into the list once, and drop the old keys so there's one copy of the token.
        if (legacyUrl != null) save(settings)
        return settings
    }

    fun save(s: AppSettings) = prefs.edit {
        putString(KEY_SERVERS, encodeServers(s.servers))
        if (s.selectedId != null) putString(KEY_SELECTED, s.selectedId) else remove(KEY_SELECTED)
        putInt(KEY_REFRESH, s.refreshSeconds)
        putBoolean(KEY_ALERT_OFFLINE, s.alerts.offline)
        putBoolean(KEY_ALERT_NO_WORK, s.alerts.outOfWork)
        putBoolean(KEY_ALERT_ERRORS, s.alerts.taskErrors)
        putBoolean(KEY_ALERT_HOT, s.alerts.tooHot)
        putInt(KEY_ALERT_MAX_TEMP, s.alerts.maxTempC)
        remove(KEY_LEGACY_URL)
        remove(KEY_LEGACY_TOKEN)
    }

    fun donationState() = DonationState(
        launches = prefs.getInt(KEY_LAUNCHES, 0),
        lastPromptMs = prefs.getLong(KEY_LAST_PROMPT, 0),
        silenced = prefs.getBoolean(KEY_SILENCED, false),
    )

    /** Counts one app launch and returns the updated state. */
    fun recordLaunch(): DonationState {
        val next = donationState().let { it.copy(launches = it.launches + 1) }
        prefs.edit { putInt(KEY_LAUNCHES, next.launches) }
        return next
    }

    fun recordPrompt(nowMs: Long) = prefs.edit { putLong(KEY_LAST_PROMPT, nowMs) }

    fun silenceDonations() = prefs.edit { putBoolean(KEY_SILENCED, true) }

    companion object {
        private const val KEY_SERVERS = "servers"
        private const val KEY_SELECTED = "selected_server"
        private const val KEY_LEGACY_URL = "server_url"
        private const val KEY_LEGACY_TOKEN = "token"
        private const val KEY_REFRESH = "refresh_seconds"
        private const val KEY_ALERT_OFFLINE = "alert_offline"
        private const val KEY_ALERT_NO_WORK = "alert_out_of_work"
        private const val KEY_ALERT_ERRORS = "alert_task_errors"
        private const val KEY_ALERT_HOT = "alert_too_hot"
        private const val KEY_ALERT_MAX_TEMP = "alert_max_temp_c"
        private const val KEY_LAUNCHES = "donation_launches"
        private const val KEY_LAST_PROMPT = "donation_last_prompt_ms"
        private const val KEY_SILENCED = "donation_silenced"

        private val listSerializer = ListSerializer(Server.serializer())

        internal fun encodeServers(servers: List<Server>): String =
            ApiClient.json.encodeToString(listSerializer, servers.map { it.copy(url = it.url.trim(), token = it.token.trim()) })

        /** The stored list, or the pre-1.1 single server turned into a one-entry list. */
        internal fun decodeServers(json: String?, legacyUrl: String?, legacyToken: String?): List<Server> {
            if (json != null) {
                runCatching { return ApiClient.json.decodeFromString(listSerializer, json) }
            }
            return if (!legacyUrl.isNullOrBlank()) listOf(Server(url = legacyUrl, token = legacyToken.orEmpty()))
            else emptyList()
        }
    }
}
