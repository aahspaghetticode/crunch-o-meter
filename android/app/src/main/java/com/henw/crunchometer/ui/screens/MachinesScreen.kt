package com.henw.crunchometer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.henw.crunchometer.data.Server
import com.henw.crunchometer.data.Summary
import com.henw.crunchometer.ui.MachineState
import com.henw.crunchometer.ui.components.Pill
import com.henw.crunchometer.ui.components.StatRow
import com.henw.crunchometer.ui.components.fmt
import com.henw.crunchometer.ui.components.formatNumber

/** One card per machine. Only shown when there are two or more. */
@Composable
fun MachinesScreen(
    servers: List<Server>,
    machine: (String) -> MachineState,
    nameOf: (Server) -> String,
    selectedId: String?,
    onOpen: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(servers, key = { it.id }) { server ->
            MachineCard(nameOf(server), machine(server.id), server.id == selectedId) { onOpen(server.id) }
        }
    }
}

@Composable
private fun MachineCard(name: String, m: MachineState, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (selected) c.surfaceContainerHigh else c.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    m.error != null -> Pill("offline", c.errorContainer, c.onErrorContainer)
                    m.summary != null -> Pill("online", c.primaryContainer, c.onPrimaryContainer)
                    else -> Pill("connecting", c.surfaceContainerHighest, c.onSurfaceVariant)
                }
            }
            val s = m.summary
            if (s != null) MachineStats(s)
            m.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.error)
            }
        }
    }
}

@Composable
private fun MachineStats(s: Summary) {
    s.boinc.data?.counts?.let {
        val queued = it.waiting + it.transferring + it.suspended
        StatRow("Tasks", "${it.running} running · $queued queued")
    }
    s.system.data?.let { sys ->
        StatRow("CPU", fmt(sys.cpu.usagePercent, "%") + (sys.cpu.tempC?.let { " · ${fmt(it, " °C")}" } ?: ""))
        if (sys.gpus.isNotEmpty()) {
            // Several GPUs: average load, hottest card.
            val util = sys.gpus.mapNotNull { it.utilPercent }.takeIf { it.isNotEmpty() }?.average()
            val temp = sys.gpus.mapNotNull { it.tempC }.maxOrNull()
            val label = if (sys.gpus.size > 1) "GPUs (${sys.gpus.size})" else "GPU"
            StatRow(label, fmt(util, "%") + (temp?.let { " · ${fmt(it, " °C")}" } ?: ""))
        }
    }
    // Host RAC, not magnitude: magnitude is per CPID, so it would double-count across machines.
    s.boinc.data?.projects?.let { projects ->
        StatRow("Host RAC", formatNumber(projects.sumOf { it.hostRac }))
    }
}
