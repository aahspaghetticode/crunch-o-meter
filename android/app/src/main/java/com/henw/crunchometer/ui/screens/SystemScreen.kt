package com.henw.crunchometer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.henw.crunchometer.data.Cpu
import com.henw.crunchometer.data.Gpu
import com.henw.crunchometer.data.LactData
import com.henw.crunchometer.data.LactGpu
import com.henw.crunchometer.data.Section
import com.henw.crunchometer.data.SystemData
import com.henw.crunchometer.ui.components.BigNumber
import com.henw.crunchometer.ui.components.EmptyNote
import com.henw.crunchometer.ui.components.ErrorBanner
import com.henw.crunchometer.ui.components.MeterRow
import com.henw.crunchometer.ui.components.Pill
import com.henw.crunchometer.ui.components.SectionCard
import com.henw.crunchometer.ui.components.Sparkline
import com.henw.crunchometer.ui.components.StatRow
import com.henw.crunchometer.ui.components.fmt
import com.henw.crunchometer.ui.components.formatBytes
import java.util.Locale

@Composable
fun SystemScreen(
    system: Section<SystemData>,
    lact: Section<LactData>,
    contentPadding: PaddingValues,
) {
    val sys = system.data
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        system.error?.let { item { ErrorBanner("System: $it") } }
        if (sys != null) {
            item { CpuCard(sys.cpu, sys.history.cpu) }
            sys.gpuError?.let { item { ErrorBanner("GPU: $it") } }
            items(sys.gpus, key = { "gpu" + it.index }) { gpu ->
                GpuCard(gpu, sys.history.gpu.getOrNull(gpu.index).orEmpty())
            }
        }
        item { LactCard(lact) }
    }
}

@Composable
private fun CpuCard(cpu: Cpu, history: List<Double>) {
    SectionCard(title = cpu.model.ifBlank { "CPU" }) {
        Row(verticalAlignment = Alignment.Bottom) {
            BigNumber(fmt(cpu.usagePercent, "%"), "CPU usage", Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(fmt(cpu.tempC, " °C"), style = MaterialTheme.typography.titleMedium)
                Text(fmt(cpu.freqMhz, " MHz"), style = MaterialTheme.typography.bodySmall)
            }
        }
        Sparkline(history)
        if (cpu.perCore.isNotEmpty()) CoreBars(cpu.perCore)
        if (cpu.loadAvg.size == 3) {
            StatRow("Load (1/5/15 min)", cpu.loadAvg.joinToString(" / ") { String.format(Locale.US, "%.2f", it) })
        }
        val memFrac = if (cpu.memUsedBytes != null && cpu.memTotalBytes != null && cpu.memTotalBytes > 0)
            cpu.memUsedBytes.toFloat() / cpu.memTotalBytes else null
        MeterRow("RAM", "${formatBytes(cpu.memUsedBytes)} / ${formatBytes(cpu.memTotalBytes)}", memFrac)
    }
}

/** One thin vertical bar per logical core. */
@Composable
private fun CoreBars(perCore: List<Double>) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        perCore.forEach { v ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.surfaceContainerHighest),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .fillMaxHeight((v / 100.0).toFloat().coerceIn(0.02f, 1f))
                        .background(c.primary),
                )
            }
        }
    }
    Text(
        "${perCore.size} threads",
        style = MaterialTheme.typography.labelSmall,
        color = c.onSurfaceVariant,
    )
}

@Composable
private fun GpuCard(gpu: Gpu, history: List<Double>) {
    SectionCard(title = gpu.name) {
        Row(verticalAlignment = Alignment.Bottom) {
            BigNumber(fmt(gpu.utilPercent, "%"), "GPU usage", Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(fmt(gpu.tempC, " °C"), style = MaterialTheme.typography.titleMedium)
                gpu.pstate?.let { Text("P$it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Sparkline(history, color = MaterialTheme.colorScheme.tertiary)

        val powerFrac = if (gpu.powerW != null && gpu.powerLimitW != null && gpu.powerLimitW > 0)
            (gpu.powerW / gpu.powerLimitW).toFloat() else null
        MeterRow("Power", "${fmt(gpu.powerW, " W", 1)} / ${fmt(gpu.powerLimitW, " W")}", powerFrac)

        val vramFrac = if (gpu.memUsedBytes != null && gpu.memTotalBytes != null && gpu.memTotalBytes > 0)
            gpu.memUsedBytes.toFloat() / gpu.memTotalBytes else null
        MeterRow("VRAM", "${formatBytes(gpu.memUsedBytes)} / ${formatBytes(gpu.memTotalBytes)}", vramFrac)

        StatRow("Core clock", "${fmt(gpu.coreClockMhz, "")} / ${fmt(gpu.maxCoreClockMhz, " MHz")}")
        StatRow("Memory clock", "${fmt(gpu.memClockMhz, "")} / ${fmt(gpu.maxMemClockMhz, " MHz")}")
        StatRow("Memory controller", fmt(gpu.memUtilPercent, "%"))
        StatRow("Fan", fmt(gpu.fanPercent, "%"))
    }
}

@Composable
private fun LactCard(section: Section<LactData>) {
    val c = MaterialTheme.colorScheme
    val data = section.data
    SectionCard(
        title = "LACT settings",
        trailing = {
            when {
                data == null -> Pill("no data", c.surfaceContainerHighest, c.onSurfaceVariant)
                data.daemonReachable -> Pill("daemon up", c.primaryContainer, c.onPrimaryContainer)
                else -> Pill("daemon down", c.errorContainer, c.onErrorContainer)
            }
        },
    ) {
        Text(
            "Read-only. Change these in LACT on the PC.",
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant,
        )
        section.error?.let { ErrorBanner(it) }
        data?.daemonError?.let { EmptyNote(it) }
        data?.currentProfile?.let { StatRow("Profile", it) }
        if (data != null && data.gpus.isEmpty()) EmptyNote("No GPU settings saved in LACT's config yet.")
        data?.gpus?.forEachIndexed { i, gpu ->
            if (i > 0) HorizontalDivider()
            LactGpuBlock(gpu)
        }
    }
}

@Composable
private fun LactGpuBlock(gpu: LactGpu) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(gpu.name, style = MaterialTheme.typography.titleSmall)
        if (gpu.name != gpu.id) {
            Text(gpu.id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        gpu.settings.forEach { s -> StatRow(prettyKey(s.key), s.value) }
    }
}

/** clocks_configuration.gpu_clock_offsets -> Gpu clock offsets */
private fun prettyKey(key: String): String {
    val known = mapOf(
        "power_cap" to "Power limit (W)",
        "clocks_configuration.gpu_clock_offsets" to "Core clock offset (MHz)",
        "clocks_configuration.mem_clock_offsets" to "Memory clock offset (MHz)",
        "clocks_configuration.max_core_clock" to "Max core clock (MHz)",
        "clocks_configuration.min_core_clock" to "Min core clock (MHz)",
        "clocks_configuration.max_memory_clock" to "Max memory clock (MHz)",
        "fan_control_enabled" to "Custom fan control",
        "fan_control_settings.mode" to "Fan mode",
        "fan_control_settings.static_speed" to "Fan static speed",
        "fan_control_settings.curve" to "Fan curve (°C: speed)",
        "performance_level" to "Performance level",
    )
    known[key]?.let { return it }
    return key.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
}
