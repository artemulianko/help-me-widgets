@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.helpmeweather.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import app.helpmeweather.BuildConfig
import app.helpmeweather.R
import app.helpmeweather.data.AppSettings
import app.helpmeweather.data.Locations
import app.helpmeweather.data.Provider
import app.helpmeweather.data.ProviderPref
import app.helpmeweather.data.Store
import app.helpmeweather.data.TempUnit
import app.helpmeweather.data.TimeFormat
import app.helpmeweather.data.WeatherRepository
import app.helpmeweather.data.WidgetConfig
import app.helpmeweather.widget.SampleData
import app.helpmeweather.widget.WidgetRenderer
import app.helpmeweather.widget.WeatherWidgetReceiver
import app.helpmeweather.widget.WidgetState
import app.helpmeweather.work.RefreshWorker
import kotlinx.coroutines.launch
import java.text.DateFormat

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_WIDGET_ID = "widget_id" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { publishPickerPreviews(this@MainActivity) }
        setContent { HelpMeWeatherTheme { HomeScreen() } }
    }
}

/** Generated widget-picker previews (Android 15+). Rate limited by the OS, so once per version. */
private suspend fun publishPickerPreviews(ctx: Context) {
    if (Build.VERSION.SDK_INT < 35) return
    val prefs = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)
    if (prefs.getInt("previews_v", 0) == BuildConfig.VERSION_CODE) return
    if (WidgetRenderer.publishPreviews(ctx)) prefs.edit().putInt("previews_v", BuildConfig.VERSION_CODE).apply()
}

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
    // Android 12+ "Pause app activity if unused" can stop a rarely-opened app after ~3 months.
    var hibernationExempt by remember { mutableStateOf(ctx.packageManager.isAutoRevokeWhitelisted) }
    var widgets by remember { mutableStateOf<List<Pair<Int, WidgetConfig>>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }

    fun refresh(force: Boolean) = scope.launch {
        WeatherRepository.refresh(ctx, force = force, foreground = true, reason = if (force) "refresh in app" else "app opened")
        RefreshWorker.scheduleNow(ctx)
    }
    fun updateApp(f: (AppSettings) -> AppSettings) = scope.launch { store.updateApp(f); WidgetRenderer.renderAll(ctx) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasLoc = Locations.hasForeground(ctx)
        hasBg = Locations.hasBackground(ctx)
        hibernationExempt = ctx.packageManager.isAutoRevokeWhitelisted
        tick++
        refresh(false) // cheap no-op when fresh; refreshes location while we have foreground access
        // Cheap re-render on open so widgets reflect any setting or system change immediately.
        scope.launch { WidgetRenderer.renderAll(ctx) }
    }
    LaunchedEffect(tick) { widgets = WeatherRepository.widgetIds(ctx).map { it to store.widgetNow(it) } }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLoc = granted
        if (granted) refresh(true)
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasBg = it }

    val place = app.deviceLocation ?: widgets.firstNotNullOfOrNull { it.second.location }
    val weather by remember(place?.key) { store.weather(place) }.collectAsState(null)

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val bg = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = bg,
        topBar = {
            LargeTopAppBar(
                title = { Text("Weather") }, scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, scrolledContainerColor = bg),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val w = weather ?: SampleData.weather()
                WidgetPreview(
                    WidgetState(app, WidgetConfig(location = if (app.deviceLocation == null) place else null), w, refreshing, place ?: w.place, 0),
                    previewSizes[3].second,
                )
                Text(
                    weather?.let {
                        val src = if (it.source == Provider.GOOGLE) "Google" else "Open-Meteo"
                        "Updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(it.fetchedAt)} · $src"
                    } ?: "Sample forecast",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp).testTag("status_line"),
                )
                lastError?.let {
                    Text(
                        it.take(140), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { refresh(true) }, enabled = !refreshing,
                        modifier = Modifier.weight(1f).height(48.dp).testTag("refresh_now"),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(painterResource(R.drawable.ms_refresh), null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(if (refreshing) "Refreshing" else "Refresh")
                    }
                    Button(
                        onClick = { requestPin(ctx) },
                        modifier = Modifier.weight(1f).height(48.dp).testTag("add_widget"),
                    ) { Text("Add to home screen") }
                }
            }

            SettingsGroup("Location") {
                if (!hasLoc) {
                    infoRow("Use your location", "City-level accuracy only. No GPS.", R.drawable.ms_near_me) {
                        TextButton(onClick = { permLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }, modifier = Modifier.testTag("grant_location")) { Text("Allow") }
                    }
                } else {
                    infoRow(app.deviceLocation?.name ?: "Finding location…", app.deviceLocation?.region ?: "Current location", R.drawable.ms_near_me)
                    if (!hasBg) {
                        infoRow("Follow me while closed", "Choose “Allow all the time” so widgets update location in the background") {
                            TextButton(onClick = {
                                runCatching { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }.onFailure {
                                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                                }
                            }) { Text("Allow") }
                        }
                    }
                }
            }

            SettingsGroup("Widgets") {
                if (widgets.isEmpty()) {
                    infoRow("No widgets yet", "Touch & hold the home screen, then Widgets → Help Me Weather")
                }
                widgets.forEachIndexed { i, (id, c) ->
                    navRow("Widget ${i + 1}", c.location?.name ?: "Current location", tag = "widget_row_$i") {
                        // Replace any editor already open instead of stacking another.
                        ctx.startActivity(
                            Intent(ctx, ConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    }
                }
            }

            SettingsGroup("Units") {
                choiceRow("Temperature", listOf(TempUnit.SYSTEM to "Auto", TempUnit.CELSIUS to "°C", TempUnit.FAHRENHEIT to "°F"), app.tempUnit, "unit") { u -> updateApp { it.copy(tempUnit = u) } }
                choiceRow("Time", listOf(TimeFormat.SYSTEM to "System", TimeFormat.H12 to "AM/PM", TimeFormat.HOUR_ONLY to "Hour only"), if (app.timeFormat == TimeFormat.H24) TimeFormat.SYSTEM else app.timeFormat, "time") { t -> updateApp { it.copy(timeFormat = t) } }
            }

            SettingsGroup("Updates") {
                customRow {
                    Text("Refresh every")
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30 to "30 min", 60 to "1 hr", 120 to "2 hr", 180 to "3 hr", 360 to "6 hr", 0 to "Manual").forEach { (m, label) ->
                            FilterChip(
                                selected = app.refreshMinutes == m,
                                onClick = { scope.launch { store.updateApp { it.copy(refreshMinutes = m) }; RefreshWorker.scheduleNow(ctx) } },
                                label = { Text(label) }, modifier = Modifier.testTag("interval_$m"),
                            )
                        }
                    }
                }
                if (!hibernationExempt && widgets.isNotEmpty()) {
                    infoRow("Keep updating if unused", "Turn off “Pause app activity if unused” so Android never pauses the widget when you don’t open the app for months") {
                        TextButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.fromParts("package", ctx.packageName, null)))
                            }.onFailure {
                                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                            }
                        }, modifier = Modifier.testTag("hibernation")) { Text("Turn off") }
                    }
                }
                switchRow("Wi‑Fi only", app.unmeteredOnly, "Background updates wait for an unmetered network") { v ->
                    scope.launch { store.updateApp { it.copy(unmeteredOnly = v) }; RefreshWorker.scheduleNow(ctx) }
                }
            }

            SettingsGroup("Forecast source") {
                choiceRow("Provider", listOf(ProviderPref.GOOGLE to "Google", ProviderPref.OPEN_METEO to "Open-Meteo"), app.provider, "provider") { p ->
                    scope.launch { store.updateApp { it.copy(provider = p) }; refresh(true) }
                }
                if (app.provider == ProviderPref.GOOGLE) {
                    customRow { ApiKeyField(app) { k -> scope.launch { store.updateApp { it.copy(googleApiKey = k) }; refresh(true) } } }
                }
            }

            SettingsGroup("Diagnostics") {
                navRow("Logs", "Refreshes, location updates and errors from the last 3 days", tag = "logs") {
                    ctx.startActivity(Intent(ctx, LogsActivity::class.java))
                }
            }

            Text(
                if (app.provider == ProviderPref.GOOGLE)
                    "Google is the forecast Pixel Weather and At a Glance use. It needs a Google Maps Platform key with the Weather API enabled; without one, Open-Meteo is used. Updates are batched by Android, skipped on low battery and never use GPS."
                else "Open-Meteo combines national weather services (ECMWF, DWD, NOAA). Data CC BY 4.0. Updates are batched by Android, skipped on low battery and never use GPS.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            )
            Spacer(Modifier.height(16.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun ApiKeyField(app: AppSettings, onSave: (String) -> Unit) {
    var key by remember(app.googleApiKey) { mutableStateOf(app.googleApiKey) }
    val ctx = LocalContext.current
    OutlinedTextField(
        value = key, onValueChange = { key = it.trim() },
        label = { Text("Google Maps Platform API key") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("api_key"),
    )
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.google.com/maps/documentation/weather/get-api-key")))
        }) { Text("Get a key") }
        TextButton(onClick = { onSave(key) }, enabled = key != app.googleApiKey, modifier = Modifier.testTag("api_key_save")) { Text("Save") }
    }
}

private fun requestPin(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    // Android refuses (throws) unless we're in the foreground, e.g. a tap landing mid-transition.
    if (mgr.isRequestPinAppWidgetSupported) runCatching {
        mgr.requestPinAppWidget(ComponentName(ctx, WeatherWidgetReceiver::class.java), null, null)
    }.onFailure { app.helpmeweather.data.AppLog.w(ctx, "Widget", "Couldn't show the add-widget dialog", it) }
}
