package com.henw.crunchometer.data

import android.content.Context
import androidx.core.content.edit

data class AppSettings(
    val serverUrl: String = "",
    val token: String = "",
    val refreshSeconds: Int = 5,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

data class DonationState(
    val launches: Int = 0,
    val lastPromptMs: Long = 0,
    val silenced: Boolean = false,
)

/**
 * Plain app-private SharedPreferences. Other apps can't read it, and it is excluded
 * from backups (res/xml/data_extraction_rules.xml), so the token stays on this phone.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = AppSettings(
        serverUrl = prefs.getString(KEY_URL, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
        refreshSeconds = prefs.getInt(KEY_REFRESH, 5),
    )

    fun save(s: AppSettings) = prefs.edit {
        putString(KEY_URL, s.serverUrl.trim())
        putString(KEY_TOKEN, s.token.trim())
        putInt(KEY_REFRESH, s.refreshSeconds)
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

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_REFRESH = "refresh_seconds"
        const val KEY_LAUNCHES = "donation_launches"
        const val KEY_LAST_PROMPT = "donation_last_prompt_ms"
        const val KEY_SILENCED = "donation_silenced"
    }
}
