package com.henw.crunchometer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.henw.crunchometer.data.Section
import com.henw.crunchometer.ui.DonationDialog
import com.henw.crunchometer.ui.MonitorViewModel
import com.henw.crunchometer.ui.UiState
import com.henw.crunchometer.ui.components.ErrorBanner
import com.henw.crunchometer.ui.components.formatDuration
import com.henw.crunchometer.ui.screens.GridcoinScreen
import com.henw.crunchometer.ui.screens.MachinesScreen
import com.henw.crunchometer.ui.screens.SettingsScreen
import com.henw.crunchometer.ui.screens.SystemScreen
import com.henw.crunchometer.ui.screens.TasksScreen
import com.henw.crunchometer.ui.theme.CrunchOMeterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CrunchOMeterTheme { MonitorApp() } }
    }
}

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    Machines("Machines", R.drawable.ic_machines),
    Tasks("Tasks", R.drawable.ic_tasks),
    Gridcoin("Gridcoin", R.drawable.ic_gridcoin),
    System("System", R.drawable.ic_system),
    Settings("Settings", R.drawable.ic_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitorApp(vm: MonitorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tabName by rememberSaveable {
        mutableStateOf(if (state.settings.isConfigured) Tab.Tasks.name else Tab.Settings.name)
    }
    // The Machines tab is only worth its space with two or more machines.
    val multi = state.settings.servers.size > 1
    val tabs = Tab.entries.filter { it != Tab.Machines || multi }
    val tab = tabs.firstOrNull { it.name == tabName } ?: Tab.Tasks

    // Poll only while the app is on screen.
    LifecycleResumeEffect(Unit) {
        vm.startPolling()
        onPauseOrDispose { vm.stopPolling() }
    }

    if (state.showDonationDialog) {
        DonationDialog(
            silenced = state.donations.silenced,
            onDismiss = vm::dismissDonationDialog,
            onCode = vm::submitDevCode,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tab.label)
                        if (tab == Tab.Machines) {
                            Subtitle("${state.settings.servers.size} machines")
                        } else {
                            MachineSwitcher(state, multi, onSelect = vm::selectServer)
                        }
                    }
                },
                actions = {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::refreshNow) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tabName = t.name },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        // One line even with five tabs on a narrow phone.
                        label = { Text(t.label, maxLines = 1, softWrap = false) },
                    )
                }
            }
        },
    ) { inner ->
        val padding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = inner.calculateTopPadding() + 8.dp,
            bottom = inner.calculateBottomPadding() + 16.dp,
        )
        Column {
            // Connection-level errors (bad token, Tailscale down) show on every data tab.
            // The Machines tab has each machine's error on its own card instead.
            val err = state.error.takeIf { tab != Tab.Settings && tab != Tab.Machines }
            val pad = Modifier.padding(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding() + 8.dp)
            if (err != null) ErrorBanner(err, pad)
            val contentPadding = if (err != null)
                PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = inner.calculateBottomPadding() + 16.dp)
            else padding

            val s = state.summary
            when (tab) {
                Tab.Machines -> MachinesScreen(
                    servers = state.settings.servers,
                    machine = state::machine,
                    nameOf = state::nameOf,
                    selectedId = state.selected?.id,
                    onOpen = { vm.selectServer(it); tabName = Tab.Tasks.name },
                    contentPadding = contentPadding,
                )
                Tab.Tasks -> TasksScreen(s?.boinc ?: Section(), contentPadding)
                Tab.Gridcoin -> GridcoinScreen(s?.gridcoin ?: Section(), s?.boinc ?: Section(), contentPadding)
                Tab.System -> SystemScreen(s?.system ?: Section(), s?.lact ?: Section(), contentPadding)
                Tab.Settings -> SettingsScreen(
                    settings = state.settings,
                    nameOf = state::nameOf,
                    onSaveServer = vm::saveServer,
                    onDeleteServer = vm::deleteServer,
                    onRefreshChange = vm::setRefreshSeconds,
                    onTest = vm::testConnection,
                    donations = state.donations,
                    onShowDonation = vm::openDonationDialog,
                    onDevCode = vm::submitDevCode,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

@Composable
private fun Subtitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The selected machine's name and data age; with two or more machines, a tap opens a list to switch. */
@Composable
private fun MachineSwitcher(state: UiState, multi: Boolean, onSelect: (String) -> Unit) {
    val server = state.selected ?: return
    var open by remember { mutableStateOf(false) }
    val name = state.nameOf(server)
    val age = state.lastSuccessMs?.let { (System.currentTimeMillis() - it) / 1000.0 }
    val text = if (age != null && age > 30) "$name · updated ${formatDuration(age)} ago" else name
    if (!multi) {
        // One machine: show the hostname once it's known, as before.
        if (state.summary != null) Subtitle(text)
        return
    }
    Box {
        Row(Modifier.clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Subtitle(text)
            Icon(
                painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = "Switch machine",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.settings.servers.forEach { s ->
                val m = state.machine(s.id)
                DropdownMenuItem(
                    text = { Text(state.nameOf(s)) },
                    trailingIcon = {
                        Text(
                            when {
                                m.error != null -> "offline"
                                s.id == server.id -> "selected"
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (m.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { open = false; onSelect(s.id) },
                )
            }
        }
    }
}
