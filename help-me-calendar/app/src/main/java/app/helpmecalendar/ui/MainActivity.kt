@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.helpmecalendar.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.helpmecalendar.R
import app.helpmecalendar.data.AppLog
import app.helpmecalendar.data.Calendars
import app.helpmecalendar.data.DeviceCalendar
import app.helpmecalendar.data.Store
import app.helpmecalendar.data.WidgetConfig
import app.helpmecalendar.widget.CalendarWidget
import app.helpmecalendar.widget.CalendarWidgetReceiver
import app.helpmecalendar.widget.SampleData
import app.helpmecalendar.widget.WidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_WIDGET_ID = "widget_id" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { HelpMeCalendarTheme { HomeScreen() } }
    }
}

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()
    var hasCal by remember { mutableStateOf(Calendars.hasPermission(ctx)) }
    var hibernationExempt by remember { mutableStateOf(ctx.packageManager.isAutoRevokeWhitelisted) }
    var widgets by remember { mutableStateOf<List<Pair<Int, WidgetConfig>>>(emptyList()) }
    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCal = granted
        tick++
        if (granted) scope.launch(Dispatchers.Default) { WidgetRenderer.renderAll(ctx) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasCal = Calendars.hasPermission(ctx)
        hibernationExempt = ctx.packageManager.isAutoRevokeWhitelisted
        tick++
        scope.launch(Dispatchers.Default) { WidgetRenderer.renderAll(ctx) }
    }
    var previewState by remember { mutableStateOf(SampleData.state()) }
    var realCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick, hasCal) {
        widgets = WidgetRenderer.ids(ctx).map { it to store.widgetNow(it) }
        val (cals, state, count) = withContext(Dispatchers.IO) {
            val list = Calendars.list(ctx)
            if (!hasCal) Triple(list, SampleData.state(), 0)
            else {
                val real = CalendarWidget.loadState(ctx, WidgetConfig(), 0)
                Triple(list, if (real.events.isNotEmpty()) real else SampleData.state(), real.events.size)
            }
        }
        calendars = cals
        previewState = state
        realCount = count
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val bg = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = bg,
        topBar = {
            LargeTopAppBar(
                title = { Text("Calendar") }, scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, scrolledContainerColor = bg),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                WidgetPreview(previewState, previewSizes.first { it.first == "4×2" }.second)
                Text(
                    when {
                        !hasCal -> "Sample events · Allow calendar access below"
                        realCount == 0 -> "Sample events · No upcoming events in the next 7 days"
                        realCount == 1 -> "1 upcoming event · ${calendars.count { it.visible }} calendar(s)"
                        else -> "$realCount upcoming events · ${calendars.count { it.visible }} calendar(s)"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp).testTag("status_line"),
                )
                Button(
                    onClick = { requestPin(ctx) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp).testTag("add_widget"),
                ) { Text("Add to home screen") }
            }

            if (!hasCal) {
                SettingsGroup("Calendar access") {
                    infoRow("Read your calendars", "Stays on your phone. No internet permission, no accounts.", R.drawable.ms_calendar) {
                        TextButton(onClick = { permLauncher.launch(Manifest.permission.READ_CALENDAR) }, modifier = Modifier.testTag("grant_calendar")) {
                            Text("Allow")
                        }
                    }
                }
            }

            SettingsGroup("Widgets") {
                if (widgets.isEmpty()) {
                    infoRow("No widgets yet", "Touch & hold the home screen, then Widgets → Help Me Calendar")
                }
                widgets.forEachIndexed { i, (id, c) ->
                    val calSummary = when (val ids = c.calendarIds) {
                        null -> "All calendars"
                        else -> {
                            val names = calendars.filter { it.id in ids }.map { it.name }
                            if (names.isEmpty()) "${ids.size} selected" else names.joinToString(", ")
                        }
                    }
                    navRow("Widget ${i + 1}", calSummary, tag = "widget_row_$i") {
                        ctx.startActivity(
                            Intent(ctx, ConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    }
                }
                if (!hibernationExempt && widgets.isNotEmpty()) {
                    infoRow("Keep updating if unused", "Turn off “Pause app activity if unused” so Android never stops calendar updates when you don’t open the app for months") {
                        TextButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.fromParts("package", ctx.packageName, null)))
                            }.onFailure {
                                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                            }
                        }, modifier = Modifier.testTag("hibernation")) { Text("Turn off") }
                    }
                }
            }

            SettingsGroup("Diagnostics") {
                navRow("Logs", "Calendar updates and system events from the last 3 days", tag = "logs") {
                    ctx.startActivity(Intent(ctx, LogsActivity::class.java))
                }
            }

            Text(
                "Reads only your phone's local calendar store — no internet permission, no accounts, and no background network jobs. Tap any widget above (or long-press it on the home screen) to choose which calendars it shows.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            )
            Spacer(Modifier.height(16.dp).navigationBarsPadding())
        }
    }
}

private fun requestPin(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    if (mgr.isRequestPinAppWidgetSupported) runCatching {
        mgr.requestPinAppWidget(ComponentName(ctx, CalendarWidgetReceiver::class.java), null, null)
    }.onFailure { AppLog.w(ctx, "Widget", "Couldn't show the add-widget dialog", it) }
}
