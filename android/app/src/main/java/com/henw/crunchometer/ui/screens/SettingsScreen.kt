package com.henw.crunchometer.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.henw.crunchometer.alerts.Notifier
import com.henw.crunchometer.data.AlertSettings
import com.henw.crunchometer.data.AppSettings
import com.henw.crunchometer.data.DonationState
import com.henw.crunchometer.data.Donations
import com.henw.crunchometer.data.Server
import com.henw.crunchometer.ui.components.SectionCard
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    nameOf: (Server) -> String,
    onSaveServer: (Server) -> Unit,
    onDeleteServer: (String) -> Unit,
    onRefreshChange: (Int) -> Unit,
    onAlertsChange: (AlertSettings) -> Unit,
    onTest: suspend (Server) -> String,
    donations: DonationState,
    onShowDonation: () -> Unit,
    onDevCode: (String) -> Boolean,
    contentPadding: PaddingValues,
) {
    // The machine being added or edited. With none set up yet, start straight on a new one.
    var editingId by rememberSaveable { mutableStateOf(if (settings.servers.isEmpty()) Server.newId() else null) }
    var refresh by rememberSaveable(settings.refreshSeconds) { mutableStateOf(settings.refreshSeconds.toFloat()) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val id = editingId
        if (id != null) {
            val existing = settings.servers.firstOrNull { it.id == id }
            ServerEditor(
                initial = existing ?: Server(id = id),
                isNew = existing == null,
                // The very first machine can't be cancelled: there'd be nothing to show.
                canCancel = settings.servers.isNotEmpty(),
                onTest = onTest,
                onSave = { onSaveServer(it); editingId = null },
                onDelete = { onDeleteServer(id); editingId = null },
                onCancel = { editingId = null },
            )
        } else {
            SectionCard(title = "Machines") {
                settings.servers.forEach { server ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editingId = server.id }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(nameOf(server), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                server.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("Edit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                FilledTonalButton(onClick = { editingId = Server.newId() }) { Text("Add machine") }
            }
        }

        SectionCard(title = "Refresh every ${refresh.roundToInt()} s") {
            Slider(
                value = refresh,
                onValueChange = { refresh = it },
                onValueChangeFinished = { onRefreshChange(refresh.roundToInt()) },
                valueRange = 2f..60f,
            )
            Text(
                "Only refreshes while the app is open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AlertsCard(settings.alerts, onAlertsChange)

        SupportCard(donations, onShowDonation, onDevCode)
    }
}

@Composable
private fun AlertsCard(alerts: AlertSettings, onChange: (AlertSettings) -> Unit) {
    val context = LocalContext.current
    // Re-read on resume: the user may have just allowed notifications in Android's settings.
    var allowed by remember { mutableStateOf(Notifier.canNotify(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = Notifier.canNotify(context)
        onPauseOrDispose { }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = Notifier.canNotify(context)
    }
    var maxTemp by rememberSaveable(alerts.maxTempC) { mutableFloatStateOf(alerts.maxTempC.toFloat()) }

    fun set(new: AlertSettings) {
        // Android 13+ needs permission; ask when the first alert goes on.
        if (new.anyOn && !alerts.anyOn && !allowed && Build.VERSION.SDK_INT >= 33) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onChange(new)
    }

    SectionCard(title = "Notifications") {
        SwitchRow("Machine goes offline", "And when it's back", alerts.offline) { set(alerts.copy(offline = it)) }
        SwitchRow("Out of work", "No tasks running", alerts.outOfWork) { set(alerts.copy(outOfWork = it)) }
        SwitchRow("Task errors", "A task finished with an error", alerts.taskErrors) { set(alerts.copy(taskErrors = it)) }
        SwitchRow("Too hot", "CPU or GPU at ${maxTemp.roundToInt()} °C or more", alerts.tooHot) {
            set(alerts.copy(tooHot = it))
        }
        if (alerts.tooHot) {
            Slider(
                value = maxTemp,
                onValueChange = { maxTemp = it },
                onValueChangeFinished = { onChange(alerts.copy(maxTempC = maxTemp.roundToInt())) },
                valueRange = 60f..100f,
            )
        }
        Text(
            "Checked about every 15 minutes, also while the app is closed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (alerts.anyOn && !allowed) {
            Text(
                "Notifications are turned off for Crunch-o-meter, so these won't show.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            FilledTonalButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }) { Text("Open notification settings") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ServerEditor(
    initial: Server,
    isNew: Boolean,
    canCancel: Boolean,
    onTest: suspend (Server) -> String,
    onSave: (Server) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var url by rememberSaveable(initial.id) { mutableStateOf(initial.url) }
    var token by rememberSaveable(initial.id) { mutableStateOf(initial.token) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val candidate = initial.copy(name = name.trim(), url = url.trim(), token = token.trim())

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${initial.displayName()}?") },
            text = { Text("Its address and token are removed from this phone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    SectionCard(title = if (isNew) "Add machine" else "Edit machine") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            supportingText = { Text("Leave empty to use the machine's hostname") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://myhost.tail1234.ts.net") },
            supportingText = { Text("Your tailscale serve address, or http://<host>.<tailnet>.ts.net:8765") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API token") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                enabled = candidate.isConfigured && !testing,
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = onTest(candidate)
                        testing = false
                    }
                },
            ) { Text(if (testing) "Testing…" else "Test connection") }
            Button(enabled = candidate.isConfigured, onClick = { onSave(candidate) }) { Text("Save") }
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canCancel) TextButton(onClick = onCancel) { Text("Cancel") }
            if (!isNew) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SupportCard(
    donations: DonationState,
    onShowDonation: () -> Unit,
    onDevCode: (String) -> Boolean,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    SectionCard(title = "Support") {
        val hasAddresses = Donations.ADDRESSES.isNotEmpty()
        Text(
            when {
                donations.silenced -> "Donation reminders are off. Thanks for using Crunch-o-meter."
                hasAddresses -> "Crunch-o-meter asks for a donation every few days. A dev code turns that off: " +
                    "find it in the source, or donate and email the transaction hash to ${Donations.CODE_EMAIL}."
                else -> "Donation addresses aren't set up yet."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (hasAddresses) {
            FilledTonalButton(onClick = onShowDonation) { Text("Donation addresses") }
        }
        if (!donations.silenced && hasAddresses) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it; wrong = false },
                label = { Text("Dev code") },
                singleLine = true,
                isError = wrong,
                supportingText = if (wrong) ({ Text("That's not it.") }) else null,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                trailingIcon = {
                    TextButton(enabled = code.isNotBlank(), onClick = { wrong = !onDevCode(code) }) { Text("Apply") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
