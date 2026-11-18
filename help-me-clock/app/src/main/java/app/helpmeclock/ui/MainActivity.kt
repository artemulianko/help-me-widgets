@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.helpmeclock.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.lifecycle.lifecycleScope
import app.helpmeclock.BuildConfig
import app.helpmeclock.data.Alarms
import app.helpmeclock.data.Store
import app.helpmeclock.data.WidgetConfig
import app.helpmeclock.widget.ClockWidgetReceiver
import app.helpmeclock.widget.SampleData
import app.helpmeclock.widget.WidgetRenderer
import app.helpmeclock.widget.WidgetState
import app.helpmeclock.widget.describeNextAlarm
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_WIDGET_ID = "widget_id" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { publishPickerPreviews(this@MainActivity) }
        setContent { HelpMeClockTheme { HomeScreen() } }
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
    // Android 12+ "Pause app activity if unused" can stop a rarely-opened app after ~3 months,
    // and a paused app no longer hears about alarm changes.
    var hibernationExempt by remember { mutableStateOf(ctx.packageManager.isAutoRevokeWhitelisted) }
    var widgets by remember { mutableStateOf<List<Pair<Int, WidgetConfig>>>(emptyList()) }
    var alarmLine by remember { mutableStateOf("") }
    var hasAlarm by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hibernationExempt = ctx.packageManager.isAutoRevokeWhitelisted
        alarmLine = describeNextAlarm(ctx)
        hasAlarm = Alarms.next(ctx) != null
        tick++
        // Re-render on open so widgets reflect any setting or system change immediately. Off the
        // main thread: composing measures text, and on a busy phone that caused an ANR.
        scope.launch(kotlinx.coroutines.Dispatchers.Default) { WidgetRenderer.renderAll(ctx) }
    }
    LaunchedEffect(tick) { widgets = WidgetRenderer.ids(ctx).map { it to store.widgetNow(it) } }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val bg = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = bg,
        topBar = {
            LargeTopAppBar(
                title = { Text("Clock") }, scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, scrolledContainerColor = bg),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val alarm = remember(tick) { Alarms.next(ctx) ?: SampleData.alarm() }
                WidgetPreview(WidgetState(WidgetConfig(), alarm, 0), previewSizes.first { it.first == "4×1" }.second)
                Text(
                    if (hasAlarm) alarmLine.substringBefore(" (shown)") else "Sample alarm · $alarmLine",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp).testTag("status_line"),
                )
                Button(
                    onClick = { requestPin(ctx) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp).testTag("add_widget"),
                ) { Text("Add to home screen") }
            }

            SettingsGroup("Widgets") {
                if (widgets.isEmpty()) {
                    infoRow("No widgets yet", "Touch & hold the home screen, then Widgets → Help Me Clock")
                }
                widgets.forEachIndexed { i, (id, c) ->
                    val what = listOfNotNull("time".takeIf { c.showTime }, "date".takeIf { c.showDate }, "alarm".takeIf { c.showAlarm })
                        .joinToString(", ").replaceFirstChar { it.uppercase() }
                    navRow("Widget ${i + 1}", what, tag = "widget_row_$i") {
                        // Replace any editor already open instead of stacking another.
                        ctx.startActivity(
                            Intent(ctx, ConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    }
                }
            }

            SettingsGroup("Updates and diagnostics") {
                if (!hibernationExempt && widgets.isNotEmpty()) {
                    infoRow("Keep the alarm current if unused", "Turn off “Pause app activity if unused” so Android never stops the widget from hearing about alarm changes") {
                        TextButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.fromParts("package", ctx.packageName, null)))
                            }.onFailure {
                                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                            }
                        }, modifier = Modifier.testTag("hibernation")) { Text("Turn off") }
                    }
                }
                navRow("Logs", "Alarm changes, restarts and errors from the last 3 days", tag = "logs") {
                    ctx.startActivity(Intent(ctx, LogsActivity::class.java))
                }
            }

            Text(
                "The time is drawn by your launcher, so the widget never wakes the phone to tick. Help Me Clock has no internet permission: nothing leaves your phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            )
            Spacer(Modifier.height(16.dp).navigationBarsPadding())
        }
    }
}

private fun requestPin(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    // Android refuses (throws) unless we're in the foreground, e.g. a tap landing mid-transition.
    if (mgr.isRequestPinAppWidgetSupported) runCatching {
        mgr.requestPinAppWidget(ComponentName(ctx, ClockWidgetReceiver::class.java), null, null)
    }.onFailure { app.helpmeclock.data.AppLog.w(ctx, "Widget", "Couldn't show the add-widget dialog", it) }
}
