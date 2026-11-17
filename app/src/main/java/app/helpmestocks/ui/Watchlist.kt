@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package app.helpmestocks.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.helpmestocks.R
import app.helpmestocks.data.AppLog
import app.helpmestocks.data.AppSettings
import app.helpmestocks.data.MAX_WATCHLIST
import app.helpmestocks.data.Store
import app.helpmestocks.data.Ticker
import app.helpmestocks.data.YahooFinance
import app.helpmestocks.widget.WidgetRenderer
import app.helpmestocks.work.RefreshWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Applies [f] to the stored watchlist (not a snapshot, so quick taps never undo each other)
 * and re-renders widgets. Reordering or removing needs no network; a background fetch is
 * queued only when a stock on the list has no quote yet.
 */
private suspend fun saveWatchlist(ctx: Context, store: Store, f: (List<Ticker>) -> List<Ticker>) {
    store.updateApp { it.copy(watchlist = f(it.watchlist).distinctBy { t -> t.symbol }.take(MAX_WATCHLIST)) }
    WidgetRenderer.renderAll(ctx)
    val quotes = store.quotesNow()
    if (store.appNow().watchlist.any { quotes[it.symbol] == null }) {
        RefreshWorker.refreshNow(ctx, force = false, reason = "stock added")
    }
}

/**
 * The shared watchlist: reorder, remove, and search to add. Used by the app and the widget
 * editor, so a stock added anywhere shows up on every widget.
 */
@Composable
fun WatchlistGroup(app: AppSettings, title: String = "Watchlist") {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Ticker>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val list = app.watchlist
    // Results appear below the field, often behind the keyboard: scroll them into view.
    val lastResult = remember { BringIntoViewRequester() }
    LaunchedEffect(results) { if (results.isNotEmpty()) { delay(50); runCatching { lastResult.bringIntoView() } } }

    LaunchedEffect(query) {
        failed = false
        if (query.isBlank()) { results = emptyList(); loading = false; return@LaunchedEffect }
        delay(300)
        loading = true
        try {
            results = YahooFinance.search(query)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // the query changed (or a result was picked): not a failure
        } catch (e: Exception) {
            failed = true; results = emptyList()
            AppLog.w(ctx, "Search", "Search failed", e)
        } finally {
            loading = false
        }
    }

    fun update(f: (List<Ticker>) -> List<Ticker>) = scope.launch { saveWatchlist(ctx, store, f) }

    SettingsGroup(title) {
        if (list.isEmpty()) infoRow("No stocks yet", "Search below to add stocks, ETFs, indices or crypto")
        list.forEachIndexed { i, t ->
            item { shapes ->
                SegmentedListItem(
                    shapes = shapes, modifier = Modifier.testTag("wl_${t.symbol}"),
                    supportingContent = { Text(listOfNotNull(t.symbol.takeIf { it != t.label }, t.name.takeIf { it != t.label }, t.exchange, t.currency).joinToString(" · "), maxLines = 1) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { update { l -> move(l, t.symbol, -1) } }, enabled = i > 0) {
                                IconRes(R.drawable.ms_arrow_upward, contentDescription = "Move ${t.label} up")
                            }
                            IconButton(onClick = { update { l -> move(l, t.symbol, 1) } }, enabled = i < list.lastIndex) {
                                IconRes(R.drawable.ms_arrow_downward, contentDescription = "Move ${t.label} down")
                            }
                            IconButton(onClick = { update { l -> l.filterNot { it.symbol == t.symbol } } }, modifier = Modifier.testTag("wl_remove_${t.symbol}")) {
                                IconRes(R.drawable.ms_delete, contentDescription = "Remove ${t.label}")
                            }
                        }
                    },
                ) { Text(t.label, maxLines = 1) }
            }
        }
        customRow {
            val full = list.size >= MAX_WATCHLIST
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(40) },
                modifier = Modifier.fillMaxWidth().testTag("wl_search"),
                enabled = !full,
                placeholder = { Text(if (full) "Watchlist is full ($MAX_WATCHLIST)" else "Add a stock, ETF, index or crypto") },
                leadingIcon = { IconRes(R.drawable.ms_search) },
                trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                shape = RoundedCornerShape(16.dp),
            )
            if (failed) Text("Search is unavailable right now. Check your connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        results.forEachIndexed { ri, r ->
            val added = list.any { it.symbol == r.symbol }
            item { shapes ->
                SegmentedListItem(
                    onClick = {
                        if (!added && list.size < MAX_WATCHLIST) {
                            // Add right away, then fill in the currency and exchange (one small request).
                            scope.launch {
                                saveWatchlist(ctx, store) { it + r }
                                AppLog.i(ctx, "Watchlist", "Added ${r.symbol}")
                                val d = YahooFinance.withDetails(r)
                                if (d != r) saveWatchlist(ctx, store) { l -> l.map { if (it.symbol == d.symbol) d else it } }
                            }
                        }
                        query = ""; focus.clearFocus()
                    },
                    shapes = shapes, enabled = !added,
                    modifier = Modifier.testTag("result_${r.symbol}").let { if (ri == results.lastIndex) it.bringIntoViewRequester(lastResult) else it },
                    leadingContent = { IconRes(if (added) R.drawable.ms_show_chart else R.drawable.ms_add) },
                    supportingContent = { Text(listOfNotNull(r.name, r.exchange, typeName(r.type)).distinct().joinToString(" · "), maxLines = 1) },
                ) { Text(if (added) "${r.symbol} · added" else r.symbol, maxLines = 1) }
            }
        }
    }
}

/** Moves a symbol by [delta] places, by identity, so it's correct even if the list changed meanwhile. */
private fun move(l: List<Ticker>, symbol: String, delta: Int): List<Ticker> {
    val i = l.indexOfFirst { it.symbol == symbol }
    val j = i + delta
    if (i < 0 || j !in l.indices) return l
    return l.toMutableList().apply { add(j, removeAt(i)) }
}

private fun typeName(t: String) = when (t) {
    "EQUITY" -> null
    "ETF" -> "ETF"
    "INDEX" -> "Index"
    "CRYPTOCURRENCY" -> "Crypto"
    "MUTUALFUND" -> "Fund"
    "CURRENCY" -> "Currency"
    "FUTURE" -> "Futures"
    else -> null
}
