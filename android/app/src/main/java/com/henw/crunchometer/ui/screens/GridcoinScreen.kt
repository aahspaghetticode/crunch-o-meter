package com.henw.crunchometer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.henw.crunchometer.data.BoincData
import com.henw.crunchometer.data.GridcoinData
import com.henw.crunchometer.data.Section
import com.henw.crunchometer.ui.components.BigNumber
import com.henw.crunchometer.ui.components.EmptyNote
import com.henw.crunchometer.ui.components.ErrorBanner
import com.henw.crunchometer.ui.components.Pill
import com.henw.crunchometer.ui.components.SectionCard
import com.henw.crunchometer.ui.components.StatRow
import com.henw.crunchometer.ui.components.formatNumber
import java.util.Locale

@Composable
fun GridcoinScreen(
    gridcoin: Section<GridcoinData>,
    boinc: Section<BoincData>,
    contentPadding: PaddingValues,
) {
    val g = gridcoin.data
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        gridcoin.error?.let { item { ErrorBanner("Gridcoin: $it") } }

        item {
            val c = MaterialTheme.colorScheme
            SectionCard(
                title = "Magnitude",
                trailing = {
                    when (g?.source) {
                        "wallet" -> Pill("from wallet", c.primaryContainer, c.onPrimaryContainer)
                        "estimate" -> Pill("estimate", c.surfaceContainerHighest, c.onSurfaceVariant)
                        else -> Pill("unavailable", c.surfaceContainerHighest, c.onSurfaceVariant)
                    }
                },
            ) {
                BigNumber(formatNumber(g?.magnitude, 2), "current magnitude")
                if (g != null && g.source == "wallet" && g.estimate != null) {
                    StatRow("Config estimate", formatNumber(g.estimate, 2))
                }
                g?.cpid?.let {
                    StatRow("CPID", it.take(8) + "…" + it.takeLast(4))
                }
                g?.note?.let { EmptyNote(it) }
                g?.walletError?.let { EmptyNote("Wallet: $it") }
            }
        }

        val perProject = g?.perProject.orEmpty()
        if (perProject.isNotEmpty()) {
            item {
                SectionCard(title = "Per project") {
                    perProject.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            StatRow("Your RAC", formatNumber(p.rac, 1))
                            StatRow("Mag / RAC", p.magPerRac?.let { String.format(Locale.US, "%.6f", it) } ?: "not set")
                            StatRow("Est. magnitude", formatNumber(p.magnitude, 2))
                        }
                    }
                }
            }
        }

        val projects = boinc.data?.projects.orEmpty()
        item {
            SectionCard(title = "BOINC credit") {
                if (projects.isEmpty()) EmptyNote("No BOINC projects reported.")
                projects.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                buildString {
                                    append("Host RAC ${formatNumber(p.hostRac, 0)}")
                                    if (p.suspended) append(" · suspended")
                                    if (p.noNewWork) append(" · no new tasks")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatNumber(p.userRac, 0), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                            Text("user RAC", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
