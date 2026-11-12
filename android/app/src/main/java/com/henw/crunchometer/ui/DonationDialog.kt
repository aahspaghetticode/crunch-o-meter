package com.henw.crunchometer.ui

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.henw.crunchometer.data.Donations

/**
 * The occasional "consider donating" dialog. Also opened by hand from Settings.
 * [onCode] returns true when the dev code was right (the caller then closes it).
 */
@Composable
fun DonationDialog(
    silenced: Boolean,
    onDismiss: () -> Unit,
    onCode: (String) -> Boolean,
) {
    var enteringCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var wrongCode by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crunch-o-meter is free") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("No ads, no tracking. If it's earned a spot on your phone, a donation keeps it that way.")
                Donations.ADDRESSES.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider()
                    AddressRow(a.label, a.address)
                }
                if (!silenced) {
                    Text(
                        "Want this gone for good? The code that turns it off is hidden in the source. " +
                            "Or donate, email the transaction hash to ${Donations.CODE_EMAIL}, and you'll get the code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (enteringCode) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; wrongCode = false },
                        label = { Text("Dev code") },
                        singleLine = true,
                        isError = wrongCode,
                        supportingText = if (wrongCode) ({ Text("That's not it.") }) else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { wrongCode = !onCode(code) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            when {
                silenced -> TextButton(onClick = onDismiss) { Text("Close") }
                enteringCode -> TextButton(onClick = { wrongCode = !onCode(code) }) { Text("Apply") }
                else -> TextButton(onClick = { enteringCode = true }) { Text("Enter code") }
            }
        },
        dismissButton = if (silenced) null else ({ TextButton(onClick = onDismiss) { Text("Not now") } }),
    )
}

@Composable
private fun AddressRow(label: String, address: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        TextButton(onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, address)))
                // Android 13+ shows its own "copied" confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) copied = true
            }
        }) { Text(if (copied) "Copied" else "Copy") }
    }
}
