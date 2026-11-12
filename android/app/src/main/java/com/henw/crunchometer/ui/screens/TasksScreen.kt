package com.henw.crunchometer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.henw.crunchometer.data.BoincData
import com.henw.crunchometer.data.CompletedTask
import com.henw.crunchometer.data.Section
import com.henw.crunchometer.data.Task
import com.henw.crunchometer.ui.components.EmptyNote
import com.henw.crunchometer.ui.components.ErrorBanner
import com.henw.crunchometer.ui.components.StatusPill
import com.henw.crunchometer.ui.components.formatDuration
import com.henw.crunchometer.ui.components.formatRelative
import java.util.Locale

@Composable
fun TasksScreen(section: Section<BoincData>, contentPadding: PaddingValues) {
    val data = section.data
    // Queued tasks can number in the dozens; keep them folded away unless asked for.
    var showWaiting by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        section.error?.let { item { ErrorBanner("BOINC: $it") } }
        if (data == null) {
            item { EmptyNote("No BOINC data yet.") }
            return@LazyColumn
        }

        item { CountsRow(data) }

        val (waiting, current) = data.active.partition { it.status == "waiting to run" }

        item { SectionHeader("In progress", current.size) }
        if (current.isEmpty()) item { EmptyNote("Nothing running right now.") }
        items(current, key = { "a:" + it.name }) { TaskCard(it) }

        if (waiting.isNotEmpty()) {
            item(key = "waiting-header") {
                WaitingHeader(waiting.size, showWaiting) { showWaiting = !showWaiting }
            }
            if (showWaiting) item(key = "waiting-list") { WaitingList(waiting) }
        }

        item { SectionHeader("Completed", data.completed.size) }
        item {
            if (data.completed.isEmpty()) {
                EmptyNote("Nothing completed recently.")
            } else {
                CompletedList(data.completed)
            }
        }
    }
}

@Composable
private fun WaitingHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Waiting to run · $count",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggle) { Text(if (expanded) "Hide" else "Show") }
    }
}

/** Queued tasks as compact rows in one card: no progress bar, since they haven't started. */
@Composable
private fun WaitingList(tasks: List<Task>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            tasks.forEachIndexed { i, task ->
                if (i > 0) HorizontalDivider()
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(task.project, style = MaterialTheme.typography.titleSmall)
                    Text(
                        task.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = buildList {
                        task.remainingS?.let { add("~${formatDuration(it)}") }
                        task.deadline?.let { add("due ${formatRelative(it)}") }
                    }
                    if (details.isNotEmpty()) {
                        Text(
                            details.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (task.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        "$title · $count",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Plain numbers, no coloured chips: only errors get colour, and only when there are some. */
@Composable
private fun CountsRow(data: BoincData) {
    val counts = data.counts
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Count(counts.running, "running", Modifier.weight(1f))
        Count(counts.waiting + counts.transferring + counts.suspended, "queued", Modifier.weight(1f))
        Count(counts.readyToReport, "to report", Modifier.weight(1f))
        Count(counts.errors, "errors", Modifier.weight(1f), alert = counts.errors > 0)
    }
}

@Composable
private fun Count(value: Int, label: String, modifier: Modifier, alert: Boolean = false) {
    Column(modifier) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskCard(task: Task) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.project, style = MaterialTheme.typography.titleSmall)
                    Text(
                        task.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(task.status)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { (task.progressPercent / 100.0).toFloat() },
                    modifier = Modifier.weight(1f).height(8.dp),
                    strokeCap = StrokeCap.Round,
                )
                Text(
                    String.format(Locale.US, "%.1f%%", task.progressPercent),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            val details = buildList {
                task.elapsedS?.let { add("${formatDuration(it)} elapsed") }
                task.remainingS?.let { add("~${formatDuration(it)} left") }
                task.resources?.let { add(it) }
            }
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            task.deadline?.let {
                Text(
                    "Deadline ${formatRelative(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Completed tasks as rows in one card rather than a card each. */
@Composable
private fun CompletedList(tasks: List<CompletedTask>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            tasks.forEachIndexed { i, task ->
                if (i > 0) HorizontalDivider()
                CompletedRow(task)
            }
        }
    }
}

@Composable
private fun CompletedRow(task: CompletedTask) {
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.project, style = MaterialTheme.typography.titleSmall)
                Text(
                    task.app ?: task.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(task.status)
        }
        val details = buildList {
            task.elapsedS?.let { add("${formatDuration(it)} run") }
            task.cpuS?.let { add("${formatDuration(it)} CPU") }
            task.completedAt?.let { add("finished ${formatRelative(it)}") }
            if (task.exitStatus != 0) add("exit ${task.exitStatus}")
        }
        if (details.isNotEmpty()) {
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
