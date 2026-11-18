@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.helpmeclock.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.helpmeclock.R
import app.helpmeclock.data.Alarms
import app.helpmeclock.data.BgTone
import app.helpmeclock.data.DateStyle
import app.helpmeclock.data.Store
import app.helpmeclock.data.TapAction
import app.helpmeclock.data.ThemeMode
import app.helpmeclock.data.Weight
import app.helpmeclock.data.WidgetConfig
import app.helpmeclock.widget.ClockWidgetReceiver
import app.helpmeclock.widget.SampleData
import app.helpmeclock.widget.WidgetRenderer
import app.helpmeclock.widget.WidgetState
import kotlinx.coroutines.launch

/** Per-widget editor. Opened by the launcher (add / "Customize") or from the app. */
class ConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Only edit widgets that really are ours (the activity is exported for the launcher).
        val info = runCatching { AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId) }.getOrNull()
        if (widgetId <= 0 || info?.provider?.className != ClockWidgetReceiver::class.java.name ||
            info.provider.packageName != packageName
        ) {
            setResult(RESULT_CANCELED); finish(); return
        }
        // configuration_optional: backing out keeps the widget with defaults, so OK either way.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val store = Store(this)
        setContent {
            HelpMeClockTheme {
                var loaded by remember { mutableStateOf<WidgetConfig?>(null) }
                LaunchedEffect(Unit) { loaded = store.widgetNow(widgetId) }
                loaded?.let { initial ->
                    WidgetEditor(initial, onClose = { finish() }, onSave = { save(store, it) })
                }
            }
        }
    }

    private fun save(store: Store, cfg: WidgetConfig) {
        lifecycleScope.launch {
            store.updateWidget(widgetId) { cfg }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { WidgetRenderer.render(this@ConfigActivity, widgetId) }
            finish()
        }
    }
}

@Composable
fun WidgetEditor(initial: WidgetConfig, onClose: () -> Unit, onSave: (WidgetConfig) -> Unit) {
    var cfg by remember { mutableStateOf(initial) }
    var sizeIdx by remember { mutableIntStateOf(previewSizes.indexOfFirst { it.first == "4×1" }) }
    val ctx = LocalContext.current
    // Your real next alarm; a sample one when none is set, so you can see where it goes.
    val alarm = remember { Alarms.next(ctx) ?: SampleData.alarm() }
    // Keyed on the settings only, so the preview re-renders exactly when you change something.
    val state = remember(cfg) { WidgetState(cfg, alarm, appWidgetId = 0) }
    // Date format samples are today's date in your language, so you pick what you'll see.
    val today = remember { java.util.Date() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text("Customize widget") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") } },
                actions = { TextButton(onClick = { onSave(cfg) }, modifier = Modifier.testTag("save")) { Text("Save") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Pinned live preview: the real widget, re-rendered on every change.
            Column(Modifier.padding(horizontal = 16.dp)) {
                WidgetPreview(state, previewSizes[sizeIdx].second)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    previewSizes.forEachIndexed { i, (label, _) ->
                        SegmentedButton(
                            selected = i == sizeIdx, onClick = { sizeIdx = i },
                            shape = SegmentedButtonDefaults.itemShape(i, previewSizes.size),
                            modifier = Modifier.testTag("size_$i"),
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SettingsGroup("Clock") {
                    choiceRow("Weight", listOf(Weight.LIGHT to "Light", Weight.REGULAR to "Regular", Weight.MEDIUM to "Medium"), cfg.weight, "weight") {
                        cfg = cfg.copy(weight = it)
                    }
                }

                SettingsGroup("Appearance") {
                    customRow {
                        Text("Colors", modifier = Modifier.padding(bottom = 8.dp))
                        PaletteSwatches(cfg.palette) { cfg = cfg.copy(palette = it) }
                    }
                    choiceRow("Theme", listOf(ThemeMode.SYSTEM to "Auto", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), cfg.themeMode, "theme") { cfg = cfg.copy(themeMode = it) }
                    choiceRow("Background", listOf(BgTone.SURFACE to "Neutral", BgTone.SECONDARY to "Tonal", BgTone.PRIMARY to "Vibrant"), cfg.bgTone, "bg") { cfg = cfg.copy(bgTone = it) }
                    customRow {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Background opacity", modifier = Modifier.weight(1f))
                            Text("${cfg.opacity}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = cfg.opacity.toFloat(), onValueChange = { cfg = cfg.copy(opacity = (it / 10f).toInt() * 10) },
                            valueRange = 0f..100f, steps = 9, modifier = Modifier.testTag("opacity"),
                        )
                    }
                }

                SettingsGroup("Show") {
                    // Time and date can't both be off: turning one off brings the other back.
                    switchRow("Time", cfg.showTime, "Turn off if your status bar already shows it", tag = "sw_time") {
                        cfg = cfg.copy(showTime = it, showDate = cfg.showDate || !it)
                    }
                    switchRow("Date", cfg.showDate, tag = "sw_date") { cfg = cfg.copy(showDate = it, showTime = cfg.showTime || !it) }
                    if (cfg.showDate) {
                        choiceRow(
                            "Date format",
                            DateStyle.entries.map { it to java.text.SimpleDateFormat(it.pattern, java.util.Locale.getDefault()).format(today) },
                            cfg.dateStyle, "datefmt",
                        ) { cfg = cfg.copy(dateStyle = it) }
                    }
                    switchRow("Next alarm", cfg.showAlarm, "Alarms from your clock app. Hidden when none is set", tag = "sw_alarm") { cfg = cfg.copy(showAlarm = it) }
                }

                SettingsGroup("On tap") {
                    listOf(
                        Triple(TapAction.CLOCK, "Clock", "Opens your clock app. Tapping the alarm opens that alarm"),
                        Triple(TapAction.APP, "Help Me Clock", "Opens this app"),
                        Triple(TapAction.NONE, "Nothing", null),
                    ).forEachIndexed { i, (action, title, summary) ->
                        radioRow(title, cfg.tapAction == action, summary, tag = "tap_$i") { cfg = cfg.copy(tapAction = action) }
                    }
                }

                SettingsGroup {
                    navRow("Diagnostics", "Logs shared by all widgets", R.drawable.ms_schedule) {
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}
