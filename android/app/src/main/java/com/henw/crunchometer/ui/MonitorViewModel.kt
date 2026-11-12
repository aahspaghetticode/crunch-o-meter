package com.henw.crunchometer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.henw.crunchometer.data.ApiClient
import com.henw.crunchometer.data.ApiException
import com.henw.crunchometer.data.AppSettings
import com.henw.crunchometer.data.DonationState
import com.henw.crunchometer.data.Donations
import com.henw.crunchometer.data.SettingsStore
import com.henw.crunchometer.data.Summary
import com.henw.crunchometer.widget.WidgetRefresher

data class UiState(
    val settings: AppSettings = AppSettings(),
    val summary: Summary? = null,
    val error: String? = null,
    val loading: Boolean = false,
    /** Phone clock time of the last successful fetch, ms. */
    val lastSuccessMs: Long? = null,
    val donations: DonationState = DonationState(),
    val showDonationDialog: Boolean = false,
)

class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val api = ApiClient()
    private val store = SettingsStore(app)

    private val _state = MutableStateFlow(
        UiState(settings = store.load(), donations = store.recordLaunch())
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var donationCheckedThisSession = false

    /** Called when the screen is resumed; stops when paused so it never polls in the background. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                delay(_state.value.settings.refreshSeconds.coerceAtLeast(2) * 1000L)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshNow() {
        viewModelScope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        val s = _state.value.settings
        if (!s.isConfigured) {
            _state.update { it.copy(error = "Add the server URL and token in Settings.", loading = false) }
            return
        }
        _state.update { it.copy(loading = true) }
        try {
            val summary = api.fetchSummary(s.serverUrl, s.token)
            _state.update {
                it.copy(summary = summary, error = null, loading = false, lastSuccessMs = System.currentTimeMillis())
            }
            maybeAskForDonation()
            pushToWidget(summary)
        } catch (e: ApiException) {
            // Keep the last summary on screen; just show the error banner.
            _state.update { it.copy(error = e.message, loading = false) }
        }
    }

    private var lastWidgetPushMs = 0L

    /** Keeps home-screen widgets current while the app is open, without hammering them. */
    private suspend fun pushToWidget(summary: Summary) {
        val now = System.currentTimeMillis()
        if (now - lastWidgetPushMs < 60_000) return
        lastWidgetPushMs = now
        val context = getApplication<Application>()
        if (WidgetRefresher.hasWidgets(context)) WidgetRefresher.push(context, summary)
    }

    /**
     * Only after the app has actually worked this session, so the reminder never
     * lands on top of a broken setup.
     */
    private fun maybeAskForDonation() {
        if (donationCheckedThisSession) return
        donationCheckedThisSession = true
        val d = _state.value.donations
        val now = System.currentTimeMillis()
        val due = Donations.ADDRESSES.isNotEmpty() &&
            !d.silenced &&
            d.launches >= Donations.MIN_LAUNCHES &&
            now - d.lastPromptMs >= Donations.INTERVAL_MS
        if (due) {
            store.recordPrompt(now)
            _state.update { it.copy(showDonationDialog = true, donations = d.copy(lastPromptMs = now)) }
        }
    }

    /** Opened by hand from Settings; doesn't touch the reminder schedule. */
    fun openDonationDialog() = _state.update { it.copy(showDonationDialog = true) }

    fun dismissDonationDialog() = _state.update { it.copy(showDonationDialog = false) }

    /** True if the code was right and reminders are now off. */
    fun submitDevCode(input: String): Boolean {
        if (!Donations.isDevCode(input)) return false
        store.silenceDonations()
        _state.update { it.copy(donations = it.donations.copy(silenced = true), showDonationDialog = false) }
        return true
    }

    fun saveSettings(new: AppSettings) {
        store.save(new)
        _state.update { it.copy(settings = new, summary = null, error = null, lastSuccessMs = null) }
        stopPolling()
        startPolling()
    }

    /** One-off check for the Settings screen, without saving. */
    suspend fun testConnection(candidate: AppSettings): String = try {
        val s = api.fetchSummary(candidate.serverUrl, candidate.token)
        "Connected. Server version ${s.version}."
    } catch (e: ApiException) {
        e.message ?: "Couldn't connect."
    }
}
