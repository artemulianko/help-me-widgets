@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.helpmecalendar.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.helpmecalendar.R
import app.helpmecalendar.data.AppLog
import app.helpmecalendar.data.BgTone
import app.helpmecalendar.data.Calendars
import app.helpmecalendar.data.DeviceCalendar
import app.helpmecalendar.data.Store
import app.helpmecalendar.data.TapAction
import app.helpmecalendar.data.ThemeMode
import app.helpmecalendar.data.WidgetConfig
import app.helpmecalendar.widget.CalendarWidget
import app.helpmecalendar.widget.CalendarWidgetReceiver
import app.helpmecalendar.widget.SampleData
import app.helpmecalendar.widget.WidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Per-widget editor. Opened by the launcher (add / "Customize") or from the app. */
class ConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = runCatching { AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId) }.getOrNull()
        if (widgetId <= 0 || info?.provider?.className != CalendarWidgetReceiver::class.java.name ||
            info.provider.packageName != packageName
        ) {
            setResult(RESULT_CANCELED); finish(); return
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val store = Store(this)
        setContent {
            HelpMeCalendarTheme {
                var loaded by remember { mutableStateOf<WidgetConfig?>(null) }
                LaunchedEffect(Unit) { loaded = store.widgetNow(widgetId) }
                loaded?.let { initial ->
                    WidgetEditor(initial, onClose = { finish() }, onSave = { save(store, it) }, sizes = editorPreviewSizes(this@ConfigActivity, widgetId))
                }
            }
        }
    }

    private var saving = false

    private fun save(store: Store, cfg: WidgetConfig) {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            store.updateWidget(widgetId) { cfg }
            withContext(Dispatchers.Default) { WidgetRenderer.render(this@ConfigActivity, widgetId) }
            AppLog.i(this@ConfigActivity, "Editor", "Saved widget $widgetId; widget update sent in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            finish()
        }
    }
}

@Composable
fun WidgetEditor(
    initial: WidgetConfig,
    onClose: () -> Unit,
    onSave: (WidgetConfig) -> Unit,
    sizes: List<Pair<String, DpSize>> = previewSizes,
) {
    var cfg by remember { mutableStateOf(initial) }
    var sizeIdx by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var hasCal by remember { mutableStateOf(Calendars.hasPermission(ctx)) }
    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    var state by remember { mutableStateOf(SampleData.state(initial)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCal = granted
        if (granted) scope.launch(Dispatchers.Default) { WidgetRenderer.renderAll(ctx) }
    }
    LaunchedEffect(hasCal) {
        calendars = if (hasCal) withContext(Dispatchers.IO) { Calendars.list(ctx) } else emptyList()
    }
    LaunchedEffect(cfg, hasCal) {
        state = if (hasCal) {
            withContext(Dispatchers.IO) {
                val real = CalendarWidget.loadState(ctx, cfg, 0)
                if (real.events.isNotEmpty() || cfg.calendarIds != null) real else SampleData.state(cfg)
            }
        } else {
            SampleData.state(cfg)
        }
    }

    // Effective selected calendar IDs (null = all visible calendars on the device).
    val visibleIds = remember(calendars) { calendars.filter { it.visible }.map { it.id }.toSet() }
    val selectedIds = cfg.calendarIds ?: visibleIds

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
            Column(Modifier.padding(horizontal = 16.dp)) {
                WidgetPreview(state, sizes[sizeIdx].second)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    sizes.forEachIndexed { i, (label, _) ->
                        SegmentedButton(
                            selected = i == sizeIdx, onClick = { sizeIdx = i },
                            shape = SegmentedButtonDefaults.itemShape(i, sizes.size),
                            modifier = Modifier.testTag("size_$i"),
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SettingsGroup("Calendars") {
                    if (!hasCal) {
                        infoRow("Allow calendar access", "Needed to list and filter your calendars", R.drawable.ms_calendar) {
                            TextButton(onClick = { permLauncher.launch(Manifest.permission.READ_CALENDAR) }, modifier = Modifier.testTag("grant_calendar")) {
                                Text("Allow")
                            }
                        }
                    } else if (calendars.isEmpty()) {
                        infoRow("No calendars found", "Add an account in your Calendar app")
                    } else {
                        switchRow(
                            "All calendars",
                            checked = cfg.calendarIds == null,
                            summary = "Show events from every visible calendar",
                            tag = "cal_all",
                        ) { all ->
                            cfg = cfg.copy(calendarIds = if (all) null else visibleIds)
                        }
                        calendars.forEachIndexed { idx, cal ->
                            val isOn = cal.id in selectedIds
                            calendarSwitchRow(
                                title = cal.name,
                                account = cal.account,
                                color = cal.color,
                                checked = isOn,
                                tag = "cal_$idx",
                            ) { checked ->
                                val next = if (checked) selectedIds + cal.id else selectedIds - cal.id
                                cfg = cfg.copy(calendarIds = next)
                            }
                        }
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
                            value = cfg.opacity.toFloat(), onValueChange = { cfg = cfg.copy(opacity = kotlin.math.round(it / 10f).toInt() * 10) },
                            valueRange = 0f..100f, steps = 9, modifier = Modifier.testTag("opacity"),
                        )
                    }
                }

                SettingsGroup("Show") {
                    choiceRow(
                        "Range",
                        listOf(1 to "Today", 3 to "3 days", 7 to "7 days", 14 to "14 days"),
                        cfg.daysAhead,
                        "days",
                    ) { cfg = cfg.copy(daysAhead = it) }
                    switchRow("All-day events", cfg.showAllDay, tag = "sw_allday") { cfg = cfg.copy(showAllDay = it) }
                    switchRow("Upcoming days strip", cfg.showUpcomingDays, "Next 3 days with event dots under the date (4×2)", tag = "sw_upcoming_days") { cfg = cfg.copy(showUpcomingDays = it) }
                    switchRow("Cat when free", cfg.showEmptyCat, "Show a lounging cat illustration when you have no upcoming events", tag = "sw_empty_cat") { cfg = cfg.copy(showEmptyCat = it) }
                    switchRow("Event location", cfg.showLocation, "Shown beside the time when space allows", tag = "sw_location") { cfg = cfg.copy(showLocation = it) }
                }

                SettingsGroup("On tap") {
                    listOf(
                        Triple(TapAction.CALENDAR, "Calendar", "Opens your calendar app (tapping an event opens that event)"),
                        Triple(TapAction.APP, "Help Me Calendar", "Opens this app"),
                        Triple(TapAction.NONE, "Nothing", null),
                    ).forEachIndexed { i, (action, title, summary) ->
                        radioRow(title, cfg.tapAction == action, summary, tag = "tap_$i") { cfg = cfg.copy(tapAction = action) }
                    }
                }

                SettingsGroup {
                    navRow("Diagnostics", "Logs shared by all widgets", R.drawable.ms_calendar) {
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}
