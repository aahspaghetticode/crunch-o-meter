package com.henw.crunchometer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.henw.crunchometer.data.Section
import com.henw.crunchometer.ui.DonationDialog
import com.henw.crunchometer.ui.MonitorViewModel
import com.henw.crunchometer.ui.components.ErrorBanner
import com.henw.crunchometer.ui.components.formatDuration
import com.henw.crunchometer.ui.screens.GridcoinScreen
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
    Tasks("Tasks", R.drawable.ic_tasks),
    Gridcoin("Gridcoin", R.drawable.ic_gridcoin),
    System("System", R.drawable.ic_system),
    Settings("Settings", R.drawable.ic_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitorApp(vm: MonitorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable {
        mutableIntStateOf(if (state.settings.isConfigured) Tab.Tasks.ordinal else Tab.Settings.ordinal)
    }
    val tab = Tab.entries[tabIndex]

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
                        val host = state.summary?.system?.data?.hostname
                        val age = state.lastSuccessMs?.let { (System.currentTimeMillis() - it) / 1000.0 }
                        if (host != null) {
                            Text(
                                if (age != null && age > 30) "$host · updated ${formatDuration(age)} ago" else host,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tabIndex = t.ordinal },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(t.label) },
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
            val err = state.error
            val pad = Modifier.padding(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding() + 8.dp)
            if (err != null && tab != Tab.Settings) ErrorBanner(err, pad)
            val contentPadding = if (err != null && tab != Tab.Settings)
                PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = inner.calculateBottomPadding() + 16.dp)
            else padding

            val s = state.summary
            when (tab) {
                Tab.Tasks -> TasksScreen(s?.boinc ?: Section(), contentPadding)
                Tab.Gridcoin -> GridcoinScreen(s?.gridcoin ?: Section(), s?.boinc ?: Section(), contentPadding)
                Tab.System -> SystemScreen(s?.system ?: Section(), s?.lact ?: Section(), contentPadding)
                Tab.Settings -> SettingsScreen(
                    current = state.settings,
                    onSave = vm::saveSettings,
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
