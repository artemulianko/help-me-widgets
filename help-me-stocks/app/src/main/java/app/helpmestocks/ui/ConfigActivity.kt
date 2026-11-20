@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package app.helpmestocks.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
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
import app.helpmestocks.R
import app.helpmestocks.data.AppLog
import app.helpmestocks.data.AppSettings
import app.helpmestocks.data.BgTone
import app.helpmestocks.data.ChangeMode
import app.helpmestocks.data.ChangeStyle
import app.helpmestocks.data.Content
import app.helpmestocks.data.QuoteSet
import app.helpmestocks.data.Store
import app.helpmestocks.data.TapAction
import app.helpmestocks.data.ThemeMode
import app.helpmestocks.data.UpColor
import app.helpmestocks.data.WidgetConfig
import app.helpmestocks.widget.SampleData
import app.helpmestocks.widget.StocksWidgetReceiver
import app.helpmestocks.widget.WidgetRenderer
import app.helpmestocks.widget.WidgetState
import app.helpmestocks.work.RefreshWorker
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
        if (widgetId <= 0 || info?.provider?.className != StocksWidgetReceiver::class.java.name ||
            info.provider.packageName != packageName
        ) {
            setResult(RESULT_CANCELED); finish(); return
        }
        // configuration_optional: backing out keeps the widget with defaults, so OK either way.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val store = Store(this)
        setContent {
            HelpMeStocksTheme {
                val app by store.app.collectAsState(AppSettings())
                var loaded by remember { mutableStateOf<WidgetConfig?>(null) }
                LaunchedEffect(Unit) { loaded = store.widgetNow(widgetId) }
                loaded?.let { initial ->
                    WidgetEditor(initial, app, store, onClose = { finish() }, onSave = { save(store, it) }, sizes = editorPreviewSizes(this@ConfigActivity, widgetId))
                }
            }
        }
    }

    /** Set on the first Save tap, so a double tap doesn't save and redraw twice. */
    private var saving = false

    private fun save(store: Store, cfg: WidgetConfig) {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            store.updateWidget(widgetId) { cfg }
            WidgetRenderer.render(this@ConfigActivity, widgetId)
            RefreshWorker.scheduleNow(this@ConfigActivity)
            RefreshWorker.refreshNow(this@ConfigActivity, force = false)
            AppLog.i(this@ConfigActivity, "Editor", "Saved widget $widgetId; widget update sent in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            finish()
        }
    }
}

@Composable
fun WidgetEditor(initial: WidgetConfig, app: AppSettings, store: Store, onClose: () -> Unit, onSave: (WidgetConfig) -> Unit, sizes: List<Pair<String, androidx.compose.ui.unit.DpSize>> = previewSizes) {
    var cfg by remember { mutableStateOf(initial) }
    var sizeIdx by remember { mutableIntStateOf(0) } // 0 = this widget's real size
    val ctx = LocalContext.current
    val cached by store.quotes.collectAsState(QuoteSet())
    // Real quotes where we have them; sample ones fill in for anything not fetched yet.
    // Remembered: sample charts are random, and regenerating them on every slider move made them jump.
    val sample = remember(app.watchlist) { SampleData.quotes(app.watchlist.map { it.symbol }) }
    val quotes = QuoteSet(sample.quotes + cached.quotes)
    val state = WidgetState(app, cfg, quotes, refreshing = false, appWidgetId = 0)

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
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
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
                SettingsGroup("Content") {
                    choiceRow("Show", listOf(Content.WATCHLIST to "Watchlist", Content.SINGLE to "One stock"), cfg.content, "content") {
                        cfg = cfg.copy(content = it, single = cfg.single ?: app.watchlist.firstOrNull()?.symbol)
                    }
                    if (cfg.content == Content.SINGLE && app.watchlist.isNotEmpty()) {
                        customRow {
                            Text("Stock")
                            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val selected = app.watchlist.firstOrNull { it.symbol == cfg.single } ?: app.watchlist.first()
                                app.watchlist.forEach { t ->
                                    FilterChip(
                                        selected = t.symbol == selected.symbol, onClick = { cfg = cfg.copy(single = t.symbol) },
                                        label = { Text(t.label) }, modifier = Modifier.testTag("single_${t.symbol}"),
                                    )
                                }
                            }
                        }
                    }
                }

                WatchlistGroup(app, if (cfg.content == Content.SINGLE) "Watchlist · pick from these" else "Watchlist · all widgets")

                SettingsGroup("Layout") {
                    choiceRow("Change", listOf(ChangeMode.PERCENT to "Percent", ChangeMode.PRICE to "Price"), cfg.changeMode, "change") { cfg = cfg.copy(changeMode = it) }
                    choiceRow("Change style", listOf(ChangeStyle.TONAL to "Tonal", ChangeStyle.SOLID to "Solid", ChangeStyle.TEXT to "Text"), cfg.changeStyle, "chip") { cfg = cfg.copy(changeStyle = it) }
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
                    choiceRow("Gains in", listOf(UpColor.GREEN_UP to "Green", UpColor.RED_UP to "Red"), cfg.upColor, "upcolor") { cfg = cfg.copy(upColor = it) }
                }

                SettingsGroup("Show") {
                    switchRow("Company name", cfg.showName, tag = "sw_name") { cfg = cfg.copy(showName = it) }
                    switchRow("Chart", cfg.showChart, "The day's price line on wider widgets", tag = "sw_chart") { cfg = cfg.copy(showChart = it) }
                    switchRow("Market status and time", cfg.showHeader, tag = "sw_header") { cfg = cfg.copy(showHeader = it) }
                    switchRow("Refresh button", cfg.showRefresh, tag = "sw_refresh") { cfg = cfg.copy(showRefresh = it) }
                }

                SettingsGroup("On tap") {
                    listOf(
                        Triple(TapAction.YAHOO, "Yahoo Finance", "Opens the stock in Yahoo Finance, app or web"),
                        Triple(TapAction.GOOGLE, "Google", "Opens the stock in Google Finance"),
                        Triple(TapAction.APP, "Help Me Stocks", "Opens this app"),
                        Triple(TapAction.REFRESH, "Refresh", "Updates the quotes"),
                        Triple(TapAction.NONE, "Nothing", null),
                    ).forEachIndexed { i, (action, title, summary) ->
                        radioRow(title, cfg.tapAction == action, summary, tag = "tap_$i") { cfg = cfg.copy(tapAction = action) }
                    }
                }

                SettingsGroup {
                    navRow("Updates and diagnostics", "Shared by all widgets", R.drawable.ms_refresh) {
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}
