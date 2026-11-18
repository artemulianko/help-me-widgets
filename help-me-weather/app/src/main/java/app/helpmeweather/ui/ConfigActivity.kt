@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.helpmeweather.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import app.helpmeweather.R
import app.helpmeweather.data.AppSettings
import app.helpmeweather.data.BgTone
import app.helpmeweather.data.ForecastMode
import app.helpmeweather.data.IconStyle
import app.helpmeweather.data.Locations
import app.helpmeweather.data.Place
import app.helpmeweather.data.Store
import app.helpmeweather.data.TapAction
import app.helpmeweather.data.TempUnit
import app.helpmeweather.data.TimeFormat
import app.helpmeweather.data.ThemeMode
import app.helpmeweather.data.WeatherData
import app.helpmeweather.data.WeatherRepository
import app.helpmeweather.data.WidgetConfig
import app.helpmeweather.data.WidgetStyle
import app.helpmeweather.widget.SampleData
import app.helpmeweather.widget.WidgetRenderer
import app.helpmeweather.widget.WidgetState
import app.helpmeweather.work.RefreshWorker
import kotlinx.coroutines.delay
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
        if (widgetId <= 0 || info?.provider?.className != app.helpmeweather.widget.WeatherWidgetReceiver::class.java.name ||
            info.provider.packageName != packageName
        ) {
            setResult(RESULT_CANCELED); finish(); return
        }
        // configuration_optional: backing out keeps the widget with defaults, so OK either way.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val store = Store(this)
        setContent {
            HelpMeWeatherTheme {
                val app by store.app.collectAsState(AppSettings())
                var loaded by remember { mutableStateOf<WidgetConfig?>(null) }
                LaunchedEffect(Unit) { loaded = store.widgetNow(widgetId) }
                loaded?.let { initial ->
                    WidgetEditor(initial, app, store, onClose = { finish() }, onSave = { save(store, it) }, sizes = editorPreviewSizes(this@ConfigActivity, widgetId))
                }
            }
        }
    }

    private fun save(store: Store, cfg: WidgetConfig) {
        lifecycleScope.launch {
            store.updateWidget(widgetId) { cfg }
            cfg.location?.let { p -> store.updateApp { a -> a.copy(recentPlaces = (listOf(p) + a.recentPlaces).distinctBy { it.key }.take(5)) } }
            WidgetRenderer.render(this@ConfigActivity, widgetId)
            RefreshWorker.scheduleNow(this@ConfigActivity)
            RefreshWorker.refreshNow(this@ConfigActivity, force = false)
            finish()
        }
    }
}

@Composable
fun WidgetEditor(initial: WidgetConfig, app: AppSettings, store: Store, onClose: () -> Unit, onSave: (WidgetConfig) -> Unit, sizes: List<Pair<String, androidx.compose.ui.unit.DpSize>> = previewSizes) {
    var cfg by remember { mutableStateOf(initial) }
    var sizeIdx by remember { mutableIntStateOf(0) } // 0 = this widget's real size
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val place: Place? = cfg.location ?: app.deviceLocation
    val cached by remember(place?.key) { store.weather(place) }.collectAsState(null)
    var fetched by remember { mutableStateOf<WeatherData?>(null) }

    // One request for a newly picked place so the preview shows real weather (and it's cached).
    LaunchedEffect(place?.key, cached == null) {
        fetched = null
        if (place != null && cached == null) {
            WeatherRepository.init(ctx)
            val (src, fallback) = WeatherRepository.sourceFor(app)
            fetched = runCatching { src.fetch(place) }.recoverCatching { fallback?.fetch(place) ?: throw it }.getOrNull()
                ?.copy(place = place)?.also { store.putWeather(it) }
        }
    }
    val weather = cached ?: fetched ?: SampleData.weather()
    val state = WidgetState(app, cfg, weather, refreshing = false, place = place ?: weather.place, appWidgetId = 0)

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
            // Pinned live preview — the real widget, re-rendered on every change.
            Column(Modifier.padding(horizontal = 16.dp)) {
                WidgetPreview(state, sizes[sizeIdx].second)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    sizes.forEachIndexed { i, (label, _) ->
                        SegmentedButton(
                            selected = i == sizeIdx, onClick = { sizeIdx = i },
                            shape = SegmentedButtonDefaults.itemShape(i, sizes.size),
                            modifier = Modifier.testTag("size_$i"),
                            label = { Text(label) },
                        )
                    }
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                LocationGroup(cfg.location, app) { cfg = cfg.copy(location = it) }

                SettingsGroup("Layout") {
                    choiceRow("Style", listOf(WidgetStyle.CLASSIC to "Classic", WidgetStyle.BOLD to "Large icon"), cfg.style, "style") { cfg = cfg.copy(style = it) }
                    choiceRow("Forecast", listOf(ForecastMode.HOURLY to "Hourly", ForecastMode.DAILY to "Daily", ForecastMode.NONE to "None"), cfg.forecast, "forecast") { cfg = cfg.copy(forecast = it) }
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
                    choiceRow("Icons", listOf(IconStyle.GOOGLE to "Google", IconStyle.SYMBOLS to "Monochrome"), cfg.iconStyle, "icons") { cfg = cfg.copy(iconStyle = it) }
                }

                SettingsGroup("Show") {
                    switchRow("Location name", cfg.showLocation) { cfg = cfg.copy(showLocation = it) }
                    switchRow("Condition", cfg.showCondition) { cfg = cfg.copy(showCondition = it) }
                    switchRow("High and low", cfg.showHighLow) { cfg = cfg.copy(showHighLow = it) }
                    switchRow("Feels like", cfg.showFeelsLike) { cfg = cfg.copy(showFeelsLike = it) }
                    switchRow("Refresh button", cfg.showRefresh, tag = "sw_refresh") { cfg = cfg.copy(showRefresh = it) }
                    switchRow("Last updated time", cfg.showUpdated) { cfg = cfg.copy(showUpdated = it) }
                }

                SettingsGroup("On tap") {
                    choiceRow(
                        "Open",
                        listOf(TapAction.PIXEL_WEATHER to "Google", TapAction.APP to "This app", TapAction.REFRESH to "Refresh", TapAction.NONE to "Nothing"),
                        cfg.tapAction, "tap",
                    ) { cfg = cfg.copy(tapAction = it) }
                }

                SettingsGroup("Units · all widgets") {
                    choiceRow("Temperature", listOf(TempUnit.SYSTEM to "Auto", TempUnit.CELSIUS to "°C", TempUnit.FAHRENHEIT to "°F"), app.tempUnit, "e_unit") { u ->
                        scope.launch { store.updateApp { it.copy(tempUnit = u) }; WidgetRenderer.renderAll(ctx) }
                    }
                    choiceRow("Time", listOf(TimeFormat.SYSTEM to "System", TimeFormat.H12 to "AM/PM", TimeFormat.HOUR_ONLY to "Hour only"), if (app.timeFormat == TimeFormat.H24) TimeFormat.SYSTEM else app.timeFormat, "e_time") { t ->
                        scope.launch { store.updateApp { it.copy(timeFormat = t) }; WidgetRenderer.renderAll(ctx) }
                    }
                }

                SettingsGroup {
                    navRow("Data source and updates", "Shared by all widgets", R.drawable.ms_refresh) {
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun LocationGroup(selected: Place?, app: AppSettings, onPick: (Place?) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); loading = false; return@LaunchedEffect }
        delay(300)
        loading = true
        results = runCatching { Locations.search(query) }.getOrDefault(emptyList())
        loading = false
    }
    val places = (listOfNotNull(selected) + app.recentPlaces).distinctBy { it.key }.take(4)
    SettingsGroup("Location") {
        item { shapes ->
            SegmentedListItem(
                onClick = { onPick(null) }, shapes = shapes, modifier = Modifier.testTag("loc_current"),
                leadingContent = { IconRes(R.drawable.ms_near_me) },
                supportingContent = { Text(app.deviceLocation?.name ?: "Approximate location") },
                trailingContent = { RadioButton(selected = selected == null, onClick = null) },
            ) { Text("Current location") }
        }
        places.forEach { p ->
            item { shapes ->
                SegmentedListItem(
                    onClick = { onPick(p) }, shapes = shapes, modifier = Modifier.testTag("place_${p.name}"),
                    leadingContent = { IconRes(R.drawable.ms_location_on) },
                    supportingContent = p.region?.let { { Text(it) } },
                    trailingContent = { RadioButton(selected = selected?.key == p.key, onClick = null) },
                ) { Text(p.name) }
            }
        }
        customRow {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().testTag("loc_search"),
                placeholder = { Text("Search for a city") },
                trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
        }
        results.forEach { p ->
            item { shapes ->
                SegmentedListItem(
                    onClick = { onPick(p); query = "" }, shapes = shapes, modifier = Modifier.testTag("result_${p.name}"),
                    leadingContent = { IconRes(R.drawable.ms_location_on) },
                    supportingContent = p.region?.let { { Text(it) } },
                ) { Text(p.name) }
            }
        }
    }
}
