package app.nimbus.weather.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import app.nimbus.weather.BuildConfig
import app.nimbus.weather.data.AppSettings
import app.nimbus.weather.data.Locations
import app.nimbus.weather.data.Provider
import app.nimbus.weather.data.ProviderPref
import app.nimbus.weather.data.Store
import app.nimbus.weather.data.WeatherRepository
import app.nimbus.weather.data.WidgetConfig
import app.nimbus.weather.widget.SampleData
import app.nimbus.weather.widget.WeatherWidget
import app.nimbus.weather.widget.WeatherWidgetReceiver
import app.nimbus.weather.widget.WidgetState
import app.nimbus.weather.work.RefreshWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_WIDGET_ID = "widget_id" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { publishPickerPreviews(this@MainActivity) }
        setContent { NimbusTheme { HomeScreen() } }
    }
}

/** Generated widget-picker previews (Android 15+). Rate limited by the OS, so once per version. */
private suspend fun publishPickerPreviews(ctx: Context) {
    if (Build.VERSION.SDK_INT < 35) return
    val prefs = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)
    if (prefs.getInt("previews_v", 0) == BuildConfig.VERSION_CODE) return
    runCatching {
        val r = GlanceAppWidgetManager(ctx).setWidgetPreviews(WeatherWidgetReceiver::class)
        if (r == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) prefs.edit().putInt("previews_v", BuildConfig.VERSION_CODE).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()
    val app by store.app.collectAsState(AppSettings())
    val refreshing by store.refreshing.collectAsState(false)
    val lastError by store.lastError.collectAsState(null)
    var hasLoc by remember { mutableStateOf(Locations.hasForeground(ctx)) }
    var hasBg by remember { mutableStateOf(Locations.hasBackground(ctx)) }
    var widgetIds by remember { mutableStateOf<List<Int>>(emptyList()) }
    var widgetConfigs by remember { mutableStateOf<Map<Int, WidgetConfig>>(emptyMap()) }
    var tick by remember { mutableIntStateOf(0) }

    fun refresh(force: Boolean) = scope.launch {
        WeatherRepository.refresh(ctx, force = force, foreground = true)
        RefreshWorker.scheduleNow(ctx)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasLoc = Locations.hasForeground(ctx)
        hasBg = Locations.hasBackground(ctx)
        tick++
        refresh(false) // no-op if fresh; uses foreground location access while visible
    }
    LaunchedEffect(tick) {
        widgetIds = WeatherRepository.widgetIds(ctx)
        widgetConfigs = widgetIds.associateWith { store.widgetNow(it) }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLoc = granted
        if (granted) refresh(true)
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasBg = it }

    val place = app.deviceLocation ?: widgetConfigs.values.firstNotNullOfOrNull { it.location }
    val weather by remember(place?.key) { store.weather(place) }.collectAsState(null)

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Weather") }, scrollBehavior = scroll) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            // Hero: the real widget, rendered with your data.
            Column(Modifier.padding(horizontal = 16.dp)) {
                val w = weather ?: SampleData.weather()
                WidgetPreview(
                    WidgetState(app, WidgetConfig(location = if (app.deviceLocation == null) place else null), w, refreshing, place ?: w.place, 0),
                    previewSizes[2].second,
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { refresh(true) }, enabled = !refreshing, modifier = Modifier.weight(1f).testTag("refresh_now")) {
                        Icon(Icons.Default.Refresh, null); Spacer(Modifier.padding(4.dp)); Text(if (refreshing) "Refreshing…" else "Refresh now")
                    }
                    Button(onClick = { requestPin(ctx) }, modifier = Modifier.weight(1f).testTag("add_widget")) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.padding(4.dp)); Text("Add widget")
                    }
                }
                weather?.let {
                    val src = if (it.source == Provider.GOOGLE) "Google Weather" else "Open-Meteo"
                    Text(
                        "${it.place.name} · $src · updated ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(it.fetchedAt)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp).testTag("status_line"),
                    )
                }
                lastError?.let {
                    Row(Modifier.padding(top = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 6.dp))
                        Text(it.take(160), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Section("Location") {
                if (!hasLoc) {
                    InfoRow(Icons.Default.MyLocation, "Use your location", "Approximate (city-level) location is enough. No GPS, no tracking.") {
                        Button(onClick = { permLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }, modifier = Modifier.testTag("grant_location")) { Text("Allow") }
                    }
                } else {
                    InfoRow(Icons.Default.LocationOn, app.deviceLocation?.name ?: "Locating…", app.deviceLocation?.region ?: "Current location")
                    if (!hasBg) {
                        InfoRow(
                            Icons.Default.MyLocation, "Update location in background",
                            "Choose “Allow all the time” so widgets follow you while the app is closed. Otherwise the location updates whenever you open the app.",
                        ) {
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 30) {
                                    runCatching { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                                        .onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) }
                                }
                            }) { Text("Allow") }
                        }
                    }
                }
            }

            Section("Your widgets") {
                if (widgetIds.isEmpty()) {
                    InfoRow(Icons.Default.Widgets, "No widgets yet", "Long-press your home screen → Widgets → Nimbus Weather, or tap “Add widget”.")
                }
                widgetIds.forEachIndexed { i, id ->
                    val c = widgetConfigs[id] ?: WidgetConfig()
                    ListItem(
                        headlineContent = { Text("Widget ${i + 1}") },
                        supportingContent = { Text(c.location?.name ?: "Current location") },
                        leadingContent = { Icon(Icons.Default.Widgets, null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            ctx.startActivity(Intent(ctx, ConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        }.testTag("widget_row_$i"),
                    )
                }
            }

            Section("Weather source") {
                Segmented(
                    "Provider",
                    listOf(ProviderPref.GOOGLE to "Google Weather", ProviderPref.OPEN_METEO to "Open-Meteo"),
                    app.provider, { p -> scope.launch { store.updateApp { it.copy(provider = p) }; refresh(true) } }, "provider",
                )
                if (app.provider == ProviderPref.GOOGLE) ApiKeyField(app) { k -> scope.launch { store.updateApp { it.copy(googleApiKey = k) }; refresh(true) } }
                Text(
                    if (app.provider == ProviderPref.GOOGLE)
                        "Google Weather is the same Google forecast Pixel Weather and At a Glance show. It needs a free Google Maps Platform key with the Weather API enabled. Without a key — or if Google is unreachable — Open-Meteo is used automatically."
                    else "Open-Meteo blends national weather services (ECMWF, DWD, NOAA…). Free, no key needed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            Section("Units") {
                UnitsPrefs(app) { f -> scope.launch { store.updateApp(f); WeatherWidget().updateAll(ctx) } }
            }

            Section("Background refresh") {
                Text("Update every", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30 to "30m", 60 to "1h", 120 to "2h", 180 to "3h", 360 to "6h", 0 to "Off").forEach { (m, label) ->
                        FilterChip(
                            selected = app.refreshMinutes == m,
                            onClick = { scope.launch { store.updateApp { it.copy(refreshMinutes = m) }; RefreshWorker.scheduleNow(ctx) } },
                            label = { Text(label) },
                            modifier = Modifier.testTag("interval_$m"),
                        )
                    }
                }
                SwitchRow(
                    "Only on Wi‑Fi", app.unmeteredOnly,
                    { v -> scope.launch { store.updateApp { it.copy(unmeteredOnly = v) }; RefreshWorker.scheduleNow(ctx) } },
                    "Background updates wait for an unmetered network",
                )
                InfoRow(
                    Icons.Default.BatteryChargingFull, "Battery friendly",
                    "Updates are batched by Android (WorkManager), skipped on low battery, use cell/Wi‑Fi location instead of GPS, and only download what a widget shows.",
                )
            }

            Section("About") {
                InfoRow(Icons.Default.Cloud, "Data", "Google Weather API (Google Maps Platform). Fallback weather data by Open-Meteo.com, CC BY 4.0. City search by Open-Meteo geocoding.")
            }
            Spacer(Modifier.height(32.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun ApiKeyField(app: AppSettings, onSave: (String) -> Unit) {
    var key by remember(app.googleApiKey) { mutableStateOf(app.googleApiKey) }
    val builtIn = BuildConfig.GOOGLE_WEATHER_KEY.isNotBlank()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = key, onValueChange = { key = it.trim() },
            label = { Text(if (builtIn) "API key (built-in key in use)" else "Google Maps Platform API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("api_key"),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            val ctx = LocalContext.current
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.google.com/maps/documentation/weather/get-api-key")))
            }) { Text("Get a key") }
            OutlinedButton(onClick = { onSave(key) }, enabled = key != app.googleApiKey, modifier = Modifier.testTag("api_key_save")) { Text("Save") }
        }
    }
}

private fun requestPin(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    if (mgr.isRequestPinAppWidgetSupported) {
        mgr.requestPinAppWidget(ComponentName(ctx, WeatherWidgetReceiver::class.java), null, null)
    }
}
