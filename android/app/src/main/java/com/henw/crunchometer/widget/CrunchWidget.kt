package com.henw.crunchometer.widget

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColor
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.henw.crunchometer.MainActivity
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class CrunchWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            WidgetBody(prefs.widgetSnapshot(), prefs.widgetConfig())
        }
    }
}

class CrunchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CrunchWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresher.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefresher.cancel(context)
    }
}

private val Ink = Color(0xFF15191C)
private val Paper = Color(0xFFF5F7F8)

private class Palette(val text: ColorProvider, val muted: ColorProvider, val track: ColorProvider, val bg: ColorProvider)

private fun palette(tone: TextTone, opacity: Int): Palette {
    val a = opacity / 100f
    fun pair(day: Color, night: Color) = when (tone) {
        TextTone.Light -> ColorProvider(night)
        TextTone.Dark -> ColorProvider(day)
        TextTone.System -> DayNightColor(day = day, night = night)
    }
    return Palette(
        text = pair(Ink, Paper),
        muted = pair(Ink.copy(alpha = 0.65f), Paper.copy(alpha = 0.7f)),
        track = pair(Ink.copy(alpha = 0.15f), Paper.copy(alpha = 0.25f)),
        // Dark text sits on a light backing and vice versa, so the opacity slider always helps legibility.
        bg = pair(Paper.copy(alpha = a), Ink.copy(alpha = a)),
    )
}

@Composable
private fun WidgetBody(snap: WidgetSnapshot?, cfg: WidgetConfig) {
    val p = palette(cfg.tone, cfg.opacity)
    val big = TextStyle(color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(color = p.muted, fontSize = 12.sp)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(p.bg)
            .cornerRadius(16.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (snap == null) {
            Text("Crunch-o-meter", style = big)
            Text("Open the app to connect", style = small)
            return@Column
        }

        if (cfg.showTasks) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${snap.running} running", style = big)
                if (snap.queued > 0) Text("  ${snap.queued} queued", style = small)
            }
            snap.topProgress?.let { pct ->
                Spacer(GlanceModifier.height(4.dp))
                LinearProgressIndicator(
                    progress = (pct / 100.0).toFloat().coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = p.text,
                    backgroundColor = p.track,
                )
                Spacer(GlanceModifier.height(2.dp))
                Text("${snap.topProject ?: "Task"} · ${pct.oneDecimal()}%", style = small, maxLines = 1)
            }
            Spacer(GlanceModifier.height(6.dp))
        }

        val stats = buildList {
            if (cfg.showMagnitude && snap.magnitude != null) {
                add((if (snap.magnitudeIsEstimate) "~" else "") + "${snap.magnitude.oneDecimal()} mag")
            }
            if (cfg.showCpu && snap.cpuPercent != null) add("CPU ${snap.cpuPercent.roundToInt()}%")
            if (cfg.showGpu && snap.gpuPercent != null) {
                add("GPU ${snap.gpuPercent.roundToInt()}%" + (snap.gpuTempC?.let { " ${it.roundToInt()}°" } ?: ""))
            }
        }
        if (stats.isNotEmpty()) {
            Row {
                stats.forEachIndexed { i, s ->
                    if (i > 0) Spacer(GlanceModifier.width(12.dp))
                    Text(s, style = big)
                }
            }
        }

        Text(footer(snap), style = small, maxLines = 1)
    }
}

@Composable
private fun footer(snap: WidgetSnapshot): String {
    val context = LocalContext.current
    val time = if (snap.updatedAtMs > 0) DateFormat.getTimeFormat(context).format(Date(snap.updatedAtMs)) else null
    return when {
        snap.error != null && time != null -> "Offline · last at $time"
        snap.error != null -> snap.error
        time != null -> "Updated $time"
        else -> ""
    }
}

private fun Double.oneDecimal() = String.format(Locale.US, "%.1f", this)
