package com.henw.crunchometer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.henw.crunchometer.data.AlertSettings
import com.henw.crunchometer.data.ApiClient
import com.henw.crunchometer.data.ApiException
import com.henw.crunchometer.data.AppSettings
import com.henw.crunchometer.data.DonationState
import com.henw.crunchometer.data.Donations
import com.henw.crunchometer.data.Server
import com.henw.crunchometer.data.SettingsStore
import com.henw.crunchometer.data.Summary
import com.henw.crunchometer.widget.WidgetRefresher

/** What we know about one machine. */
data class MachineState(
    val summary: Summary? = null,
    val error: String? = null,
    val loading: Boolean = false,
    /** Phone clock time of the last successful fetch, ms. */
    val lastSuccessMs: Long? = null,
)

data class UiState(
    val settings: AppSettings = AppSettings(),
    /** Keyed by Server.id. */
    val machines: Map<String, MachineState> = emptyMap(),
    val donations: DonationState = DonationState(),
    val showDonationDialog: Boolean = false,
) {
    val selected: Server? get() = settings.selected
    private val current: MachineState get() = selected?.let { machines[it.id] } ?: MachineState()

    // The selected machine, for the Tasks/Gridcoin/System tabs.
    val summary: Summary? get() = current.summary
    val error: String? get() = if (!settings.isConfigured) "Add a server in Settings." else current.error
    val loading: Boolean get() = current.loading
    val lastSuccessMs: Long? get() = current.lastSuccessMs

    fun machine(id: String): MachineState = machines[id] ?: MachineState()

    /** The name to show for [server]: its own name, or the hostname it reports. */
    fun nameOf(server: Server): String = server.displayName(machine(server.id).summary?.system?.data?.hostname)
}

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

    /** Polls every machine in parallel, so one that's down doesn't hold up the rest. */
    private suspend fun refreshOnce() = coroutineScope {
        _state.value.settings.servers.filter { it.isConfigured }
            .map { async { refreshServer(it) } }
            .awaitAll()
    }

    private suspend fun refreshServer(server: Server) {
        updateMachine(server.id) { it.copy(loading = true) }
        try {
            val summary = api.fetchSummary(server.url, server.token)
            updateMachine(server.id) {
                it.copy(summary = summary, error = null, loading = false, lastSuccessMs = System.currentTimeMillis())
            }
            maybeAskForDonation()
            pushToWidget(server.id, summary)
        } catch (e: ApiException) {
            // Keep the last summary on screen; just show the error banner.
            updateMachine(server.id) { it.copy(error = e.message, loading = false) }
        }
    }

    private fun updateMachine(id: String, change: (MachineState) -> MachineState) = _state.update {
        // A machine deleted mid-fetch stays deleted.
        if (it.settings.servers.none { s -> s.id == id }) it
        else it.copy(machines = it.machines + (id to change(it.machine(id))))
    }

    private val lastWidgetPushMs = mutableMapOf<String, Long>()

    /** Keeps home-screen widgets current while the app is open, without hammering them. */
    private suspend fun pushToWidget(serverId: String, summary: Summary) {
        val now = System.currentTimeMillis()
        if (now - (lastWidgetPushMs[serverId] ?: 0L) < 60_000) return
        lastWidgetPushMs[serverId] = now
        val context = getApplication<Application>()
        if (WidgetRefresher.hasWidgets(context)) WidgetRefresher.push(context, serverId, summary)
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

    /** Which machine the Tasks/Gridcoin/System tabs show. */
    fun selectServer(id: String) = changeSettings { it.copy(selectedId = id) }

    /** Adds [server], or replaces the one with the same id. */
    fun saveServer(server: Server) {
        changeSettings { s ->
            val servers = if (s.servers.any { it.id == server.id }) s.servers.map { if (it.id == server.id) server else it }
            else s.servers + server
            s.copy(servers = servers)
        }
        // Its old numbers may be from a different URL; start that machine fresh.
        _state.update { it.copy(machines = it.machines - server.id) }
        lastWidgetPushMs.remove(server.id)
        restartPolling()
        WidgetRefresher.refreshSoon(getApplication())
    }

    fun deleteServer(id: String) {
        changeSettings { s ->
            s.copy(servers = s.servers.filterNot { it.id == id }, selectedId = s.selectedId.takeIf { it != id })
        }
        _state.update { it.copy(machines = it.machines - id) }
        lastWidgetPushMs.remove(id)
        // Widgets that showed it fall back to the first machine.
        WidgetRefresher.refreshSoon(getApplication())
    }

    fun setAlerts(alerts: AlertSettings) {
        changeSettings { it.copy(alerts = alerts) }
        // Start the background check if this is the first alert on, stop it if nothing needs it now.
        viewModelScope.launch { WidgetRefresher.sync(getApplication()) }
    }

    fun setRefreshSeconds(seconds: Int) {
        changeSettings { it.copy(refreshSeconds = seconds) }
        restartPolling()
    }

    private fun changeSettings(change: (AppSettings) -> AppSettings) {
        _state.update { it.copy(settings = change(it.settings)) }
        store.save(_state.value.settings)
    }

    private fun restartPolling() {
        if (pollJob == null) return  // paused; onResume starts it again
        stopPolling()
        startPolling()
    }

    /** One-off check for the Settings screen, without saving. */
    suspend fun testConnection(candidate: Server): String = try {
        val s = api.fetchSummary(candidate.url, candidate.token)
        val host = s.system.data?.hostname?.takeIf { it.isNotBlank() }
        "Connected" + (host?.let { " to $it" } ?: "") + ". Server version ${s.version}."
    } catch (e: ApiException) {
        e.message ?: "Couldn't connect."
    }
}
