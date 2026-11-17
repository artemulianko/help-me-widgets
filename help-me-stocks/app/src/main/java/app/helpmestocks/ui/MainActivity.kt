@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.helpmestocks.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import app.helpmestocks.BuildConfig
import app.helpmestocks.R
import app.helpmestocks.data.AppSettings
import app.helpmestocks.data.Content
import app.helpmestocks.data.QuoteSet
import app.helpmestocks.data.StocksRepository
import app.helpmestocks.data.Store
import app.helpmestocks.data.WidgetConfig
import app.helpmestocks.widget.SampleData
import app.helpmestocks.widget.StocksWidgetReceiver
import app.helpmestocks.widget.WidgetRenderer
import app.helpmestocks.widget.WidgetState
import app.helpmestocks.work.RefreshWorker
import kotlinx.coroutines.launch
import java.text.DateFormat

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_WIDGET_ID = "widget_id" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { publishPickerPreviews(this@MainActivity) }
        setContent { HelpMeStocksTheme { HomeScreen() } }
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
    val quotes by store.quotes.collectAsState(QuoteSet())
    val refreshing by store.refreshing.collectAsState(false)
    val lastError by store.lastError.collectAsState(null)
    // Android 12+ "Pause app activity if unused" can stop a rarely-opened app after ~3 months.
    var hibernationExempt by remember { mutableStateOf(ctx.packageManager.isAutoRevokeWhitelisted) }
    var widgets by remember { mutableStateOf<List<Pair<Int, WidgetConfig>>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }

    fun refresh(force: Boolean) = scope.launch {
        StocksRepository.refresh(ctx, force = force, reason = if (force) "refresh in app" else "app opened")
        RefreshWorker.scheduleNow(ctx)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hibernationExempt = ctx.packageManager.isAutoRevokeWhitelisted
        tick++
        refresh(false) // cheap no-op when fresh or markets are closed
        // Cheap re-render on open so widgets reflect any setting or system change immediately.
        scope.launch { WidgetRenderer.renderAll(ctx) }
    }
    LaunchedEffect(tick) { widgets = StocksRepository.widgetIds(ctx).map { it to store.widgetNow(it) } }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val bg = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = bg,
        topBar = {
            LargeTopAppBar(
                title = { Text("Stocks") }, scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, scrolledContainerColor = bg),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val real = quotes.quotes.isNotEmpty()
                val q = if (real) quotes else SampleData.quotes(app.watchlist.map { it.symbol })
                WidgetPreview(WidgetState(app, WidgetConfig(), q, refreshing, 0), previewSizes[2].second)
                Text(
                    if (real) "Updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(quotes.fetchedAt)} · Yahoo Finance"
                    else "Sample quotes",
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

            WatchlistGroup(app)

            SettingsGroup("Widgets") {
                if (widgets.isEmpty()) {
                    infoRow("No widgets yet", "Touch & hold the home screen, then Widgets → Help Me Stocks")
                }
                widgets.forEachIndexed { i, (id, c) ->
                    val what = if (c.content == Content.SINGLE)
                        app.watchlist.firstOrNull { it.symbol == c.single }?.label ?: app.watchlist.firstOrNull()?.label ?: "One stock"
                    else "Watchlist"
                    navRow("Widget ${i + 1}", what, tag = "widget_row_$i") {
                        // Replace any editor already open instead of stacking another.
                        ctx.startActivity(
                            Intent(ctx, ConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    }
                }
            }

            SettingsGroup("Updates") {
                customRow {
                    Text("Refresh while markets are open")
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "15 min", 30 to "30 min", 60 to "1 hr", 120 to "2 hr", 0 to "Manual").forEach { (m, label) ->
                            FilterChip(
                                selected = app.refreshMinutes == m,
                                onClick = { scope.launch { store.updateApp { it.copy(refreshMinutes = m) }; RefreshWorker.scheduleNow(ctx) } },
                                label = { Text(label) }, modifier = Modifier.testTag("interval_$m"),
                            )
                        }
                    }
                    Text(
                        "When markets are closed the closing prices are kept and nothing is downloaded until the next session.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
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

            SettingsGroup("Diagnostics") {
                navRow("Logs", "Refreshes, skipped updates and errors from the last 3 days", tag = "logs") {
                    ctx.startActivity(Intent(ctx, LogsActivity::class.java))
                }
            }

            Text(
                "Quotes from Yahoo Finance, the source behind Apple's Stocks widget. Prices may be delayed by up to 20 minutes depending on the exchange. Not investment advice. Updates are batched by Android and skipped on low battery.",
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
        mgr.requestPinAppWidget(ComponentName(ctx, StocksWidgetReceiver::class.java), null, null)
    }.onFailure { app.helpmestocks.data.AppLog.w(ctx, "Widget", "Couldn't show the add-widget dialog", it) }
}
