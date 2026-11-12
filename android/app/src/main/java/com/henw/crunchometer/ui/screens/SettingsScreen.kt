package com.henw.crunchometer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.henw.crunchometer.data.AppSettings
import com.henw.crunchometer.data.DonationState
import com.henw.crunchometer.data.Donations
import com.henw.crunchometer.ui.components.SectionCard
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    current: AppSettings,
    onSave: (AppSettings) -> Unit,
    onTest: suspend (AppSettings) -> String,
    donations: DonationState,
    onShowDonation: () -> Unit,
    onDevCode: (String) -> Boolean,
    contentPadding: PaddingValues,
) {
    var url by rememberSaveable(current) { mutableStateOf(current.serverUrl) }
    var token by rememberSaveable(current) { mutableStateOf(current.token) }
    var refresh by rememberSaveable(current) { mutableStateOf(current.refreshSeconds.toFloat()) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val candidate = AppSettings(url.trim(), token.trim(), refresh.roundToInt())

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Server") {
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
        }

        SectionCard(title = "Refresh every ${refresh.roundToInt()} s") {
            Slider(
                value = refresh,
                onValueChange = { refresh = it },
                valueRange = 2f..60f,
            )
            Text(
                "Only refreshes while the app is open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
            Button(
                enabled = candidate.isConfigured,
                onClick = { onSave(candidate); testResult = "Saved" },
            ) { Text("Save") }
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        SupportCard(donations, onShowDonation, onDevCode)
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
