package app.nimbus.weather.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import app.nimbus.weather.data.TempUnit
import app.nimbus.weather.data.TimeFormat
import androidx.lifecycle.lifecycleScope
import app.nimbus.weather.data.AppSettings
import app.nimbus.weather.data.BgTone
import app.nimbus.weather.data.ForecastMode
import app.nimbus.weather.data.IconStyle
import app.nimbus.weather.data.Palette
import app.nimbus.weather.data.Place
import app.nimbus.weather.data.Store
import app.nimbus.weather.data.TapAction
import app.nimbus.weather.data.ThemeMode
import app.nimbus.weather.data.WeatherData
import app.nimbus.weather.data.WeatherRepository
import app.nimbus.weather.data.WidgetConfig
import app.nimbus.weather.data.WidgetStyle
import app.nimbus.weather.widget.SampleData
import app.nimbus.weather.widget.WeatherWidget
import app.nimbus.weather.widget.WidgetState
import app.nimbus.weather.work.RefreshWorker
import kotlinx.coroutines.launch

/** Per-widget editor. Launched by the launcher (configure/reconfigure) or from the app. */
class ConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // If the user backs out of the initial configure step the launcher keeps the widget
        // with defaults (configuration_optional), so OK is the right result either way.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val store = Store(this)
        setContent {
            NimbusTheme {
                val app by store.app.collectAsState(AppSettings())
                var loaded by remember { mutableStateOf<WidgetConfig?>(null) }
                LaunchedEffect(Unit) { loaded = store.widgetNow(widgetId) }
                loaded?.let { initial ->
                    WidgetEditor(
                        initial = initial, app = app, store = store,
                        onBack = { finish() },
                        onSave = { cfg -> save(store, cfg) },
                    )
                }
            }
        }
    }

    private fun save(store: Store, cfg: WidgetConfig) {
        lifecycleScope.launch {
            store.updateWidget(widgetId) { cfg }
            cfg.location?.let { p -> store.updateApp { a -> a.copy(recentPlaces = (listOf(p) + a.recentPlaces).distinctBy { it.key }.take(6)) } }
            runCatching {
                val gid = GlanceAppWidgetManager(this@ConfigActivity).getGlanceIdBy(widgetId)
                WeatherWidget().update(this@ConfigActivity, gid)
            }
            RefreshWorker.schedule(this@ConfigActivity)
            RefreshWorker.refreshNow(this@ConfigActivity, force = false)
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetEditor(initial: WidgetConfig, app: AppSettings, store: Store, onBack: () -> Unit, onSave: (WidgetConfig) -> Unit) {
    var cfg by remember { mutableStateOf(initial) }
    var previewIdx by remember { mutableIntStateOf(1) }
    val place: Place? = cfg.location ?: app.deviceLocation
    val cached by remember(place?.key) { store.weather(place) }.collectAsState(null)
    var fetched by remember { mutableStateOf<WeatherData?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Fetch a preview forecast for a newly picked place (one request, cached afterwards).
    LaunchedEffect(place?.key, cached == null) {
        fetched = null
        if (place != null && cached == null) {
            val (src, fb) = WeatherRepository.sourceFor(app)
            fetched = runCatching { src.fetch(place) }.recoverCatching { fb?.fetch(place) ?: throw it }.getOrNull()
                ?.copy(place = place)?.also { store.putWeather(it) }
        }
    }
    val weather = cached ?: fetched ?: SampleData.weather()
    val state = WidgetState(app, cfg, weather, refreshing = false, place = place ?: weather.place, appWidgetId = 0)

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Customize widget") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onSave(cfg) },
                icon = { Icon(Icons.Default.Check, null) },
                text = { Text("Done") },
                modifier = Modifier.testTag("save"),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                WidgetPreview(state, previewSizes[previewIdx].second)
                Spacer(Modifier.height(12.dp))
                Segmented("Preview size", previewSizes.mapIndexed { i, p -> i to p.first }, previewIdx, { previewIdx = it })
            }

            Section("Location") {
                LocationPicker(cfg.location, app.recentPlaces, app.deviceLocation?.name) { cfg = cfg.copy(location = it) }
            }

            Section("Layout") {
                Segmented("Style", listOf(WidgetStyle.CLASSIC to "Classic", WidgetStyle.BOLD to "Big icon"), cfg.style, { cfg = cfg.copy(style = it) }, "style")
                Segmented("Forecast", listOf(ForecastMode.HOURLY to "Hourly", ForecastMode.DAILY to "Daily", ForecastMode.NONE to "None"), cfg.forecast, { cfg = cfg.copy(forecast = it) }, "forecast")
            }

            Section("Colors") {
                Text("Palette", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                PaletteRow(cfg.palette) { cfg = cfg.copy(palette = it) }
                Segmented("Theme", listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), cfg.themeMode, { cfg = cfg.copy(themeMode = it) }, "theme")
                Segmented("Background", listOf(BgTone.SURFACE to "Neutral", BgTone.PRIMARY to "Primary", BgTone.SECONDARY to "Secondary"), cfg.bgTone, { cfg = cfg.copy(bgTone = it) }, "bg")
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Background opacity", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${cfg.opacity}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = cfg.opacity.toFloat(), onValueChange = { cfg = cfg.copy(opacity = (it / 10).toInt() * 10) },
                        valueRange = 0f..100f, steps = 9, modifier = Modifier.testTag("opacity"),
                    )
                }
                Segmented(
                    "Icons", listOf(IconStyle.COLOR to "Colorful", IconStyle.THEMED to "Themed"), cfg.iconStyle,
                    { cfg = cfg.copy(iconStyle = it) }, "icons",
                )
                if (cfg.iconStyle == IconStyle.THEMED && cfg.palette != Palette.DYNAMIC) {
                    Text(
                        "Themed icons follow your wallpaper colors (Dynamic palette).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }

            Section("Show") {
                SwitchRow("Location name", cfg.showLocation, { cfg = cfg.copy(showLocation = it) })
                SwitchRow("Condition", cfg.showCondition, { cfg = cfg.copy(showCondition = it) })
                SwitchRow("High / low", cfg.showHighLow, { cfg = cfg.copy(showHighLow = it) })
                SwitchRow("Feels like", cfg.showFeelsLike, { cfg = cfg.copy(showFeelsLike = it) })
                SwitchRow("Refresh button", cfg.showRefresh, { cfg = cfg.copy(showRefresh = it) }, tag = "sw_refresh")
                SwitchRow("Last updated time", cfg.showUpdated, { cfg = cfg.copy(showUpdated = it) })
            }

            Section("When tapped") {
                Segmented(
                    "Open", listOf(TapAction.PIXEL_WEATHER to "Google", TapAction.APP to "This app", TapAction.REFRESH to "Refresh", TapAction.NONE to "Nothing"),
                    cfg.tapAction, { cfg = cfg.copy(tapAction = it) }, "tap",
                )
                Text(
                    "“Google” opens Pixel Weather, the same forecast as At a Glance.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            Section("Units (all widgets)") {
                UnitsPrefs(app) { f -> scope.launch { store.updateApp(f); WeatherWidget().updateAll(ctx) } }
            }
            Spacer(Modifier.height(96.dp).navigationBarsPadding())
        }
    }
}

@Composable
fun UnitsPrefs(app: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Segmented(
        "Temperature",
        listOf(TempUnit.SYSTEM to "System", TempUnit.CELSIUS to "°C", TempUnit.FAHRENHEIT to "°F"),
        app.tempUnit, { u -> update { it.copy(tempUnit = u) } }, "unit",
    )
    Segmented(
        "Time format",
        listOf(TimeFormat.SYSTEM to "System", TimeFormat.H12 to "12-hour", TimeFormat.H24 to "24-hour"),
        app.timeFormat, { t -> update { it.copy(timeFormat = t) } }, "time",
    )
}
