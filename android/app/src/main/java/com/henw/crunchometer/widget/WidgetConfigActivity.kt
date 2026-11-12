package com.henw.crunchometer.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.henw.crunchometer.ui.theme.CrunchOMeterTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Opens when a widget is added (skippable on Android 12+) and when it's reconfigured
 * from the home screen's long-press menu.
 */
class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out without saving must not leave a half-added widget.
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()

        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            val prefs: Preferences =
                getAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)
            val initial = prefs.widgetConfig()
            setContent {
                CrunchOMeterTheme {
                    ConfigScreen(initial, onSave = { save(it) })
                }
            }
        }
    }

    private fun save(cfg: WidgetConfig) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { p ->
                p[WidgetKeys.SHOW_TASKS] = cfg.showTasks
                p[WidgetKeys.SHOW_MAGNITUDE] = cfg.showMagnitude
                p[WidgetKeys.SHOW_CPU] = cfg.showCpu
                p[WidgetKeys.SHOW_GPU] = cfg.showGpu
                p[WidgetKeys.OPACITY] = cfg.opacity
                p[WidgetKeys.TONE] = cfg.tone.name
            }
            CrunchWidget().update(this@WidgetConfigActivity, glanceId)
            WidgetRefresher.schedule(this@WidgetConfigActivity)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(initial: WidgetConfig, onSave: (WidgetConfig) -> Unit) {
    var cfg by remember { mutableStateOf(initial) }

    Scaffold(topBar = { TopAppBar(title = { Text("Widget") }) }) { inner ->
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Preview(cfg)

            Text("Show", style = MaterialTheme.typography.titleSmall)
            ToggleRow("Tasks and progress", cfg.showTasks) { cfg = cfg.copy(showTasks = it) }
            ToggleRow("Gridcoin magnitude", cfg.showMagnitude) { cfg = cfg.copy(showMagnitude = it) }
            ToggleRow("CPU usage", cfg.showCpu) { cfg = cfg.copy(showCpu = it) }
            ToggleRow("GPU usage and temperature", cfg.showGpu) { cfg = cfg.copy(showGpu = it) }

            Text("Background ${cfg.opacity}%", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = cfg.opacity.toFloat(),
                onValueChange = { cfg = cfg.copy(opacity = it.roundToInt()) },
                valueRange = 0f..100f,
            )

            Text("Text", style = MaterialTheme.typography.titleSmall)
            listOf(
                TextTone.System to "Follow light/dark mode",
                TextTone.Light to "Light, for dark wallpapers",
                TextTone.Dark to "Dark, for light wallpapers",
            ).forEach { (tone, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = cfg.tone == tone, role = Role.RadioButton) { cfg = cfg.copy(tone = tone) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = cfg.tone == tone, onClick = null)
                    Text(label, Modifier.padding(start = 12.dp))
                }
            }

            Button(onClick = { onSave(cfg) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Rough stand-in for the real widget over a fake wallpaper, so opacity and tone are easy to judge. */
@Composable
private fun Preview(cfg: WidgetConfig) {
    val dark = when (cfg.tone) {
        TextTone.Light -> true
        TextTone.Dark -> false
        TextTone.System -> isSystemInDarkTheme()
    }
    val ink = if (dark) Color(0xFFF5F7F8) else Color(0xFF15191C)
    val backing = (if (dark) Color(0xFF15191C) else Color(0xFFF5F7F8)).copy(alpha = cfg.opacity / 100f)
    val wallpaper = if (dark) listOf(Color(0xFF2E4057), Color(0xFF4F6D7A)) else listOf(Color(0xFFDDE6ED), Color(0xFFB8CAD6))

    Box(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(wallpaper))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(backing)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (cfg.showTasks) Text("3 running", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            val stats = buildList {
                if (cfg.showMagnitude) add("187.4 mag")
                if (cfg.showCpu) add("CPU 63%")
                if (cfg.showGpu) add("GPU 98% 71°")
            }
            if (stats.isNotEmpty()) {
                Text(stats.joinToString("   "), color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Text("Updated 9:41", color = ink.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}
