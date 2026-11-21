package app.helpmestocks.data

import android.content.Context
import app.helpmestocks.widget.WidgetRenderer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object StocksRepository {
    /** Periodic, one-time and in-app refreshes run one at a time, never interleaved. */
    private val lock = Mutex()

    fun widgetIds(ctx: Context): List<Int> = WidgetRenderer.ids(ctx).toList()

    /**
     * Refreshes the shared watchlist. Nothing is downloaded unless at least one symbol needs it
     * ([Market.needsFetch]: stale during market hours, or a new session is due); when something
     * does, the whole watchlist comes back in one batched request, so every widget is updated
     * by a single small download.
     */
    /**
     * [widgets] = false: fetch only, no spinner and no widget redraw (the caller redraws later).
     * Used by the refresh window: while any window is in front of the home screen, the launcher
     * holds widget updates back and applies them afterwards, and on a Pixel 11 Pro one of those
     * late updates sometimes left the widget an empty frame until the next update.
     */
    suspend fun refresh(ctx: Context, force: Boolean, reason: String = "scheduled", widgets: Boolean = true, spinner: Boolean = force): Result<Boolean> = lock.withLock {
        val started = System.currentTimeMillis()
        val store = Store(ctx)
        var changed = false
        try {
            // Spinner only for user-initiated refreshes; background refreshes cost a single render.
            var spinnerMs = 0L
            if (spinner && widgets) {
                store.setRefreshing(true)
                spinnerMs = timed { WidgetRenderer.renderAll(ctx) }
            }
            val app = store.appNow()
            val symbols = app.watchlist.map { it.symbol }
            AppLog.i(ctx, "Refresh", "Started ($reason${if (force) ", forced" else ""}) · ${widgetIds(ctx).size} widget(s), ${symbols.size} symbol(s)${if (spinner && widgets) " · spinner shown in $spinnerMs ms" else ""}")
            if (symbols.isEmpty()) {
                store.setError(null)
                AppLog.i(ctx, "Refresh", "Watchlist is empty; nothing to fetch")
                return Result.success(widgets && force)
            }
            val cached = store.quotesNow()
            val now = System.currentTimeMillis()
            val maxAge = (app.refreshMinutes.coerceAtLeast(15) * 60_000L * 3 / 4).coerceAtLeast(10 * 60_000L)
            val due = symbols.filter { Market.needsFetch(cached[it], now, maxAge) }

            if (!force && due.isEmpty()) {
                val open = symbols.mapNotNull { cached[it] }.count { Market.isOpen(it, now / 1000) }
                AppLog.i(ctx, "Fetch", if (open == 0) "Markets closed and prices final; skipped (no network)" else "Quotes still fresh; skipped")
                return Result.success(false)
            }
            // Protect the free endpoint: even a manual refresh reuses quotes fetched <1 min ago.
            if (force && due.isEmpty() && now - cached.fetchedAt < 60_000L) {
                AppLog.i(ctx, "Fetch", "Fetched under a minute ago, reusing it")
                return Result.success(widgets)
            }

            val t0 = System.currentTimeMillis()
            AppLog.i(ctx, "Fetch", "Requesting Yahoo Finance for ${symbols.size} symbol(s) (${due.size} due)")
            var error: String? = null
            val fresh = runCatching {
                val requested = symbols.toSet()
                YahooFinance.quotes(symbols).filter { it.symbol in requested && it.isValid() }.also {
                    if (it.isEmpty()) throw java.io.IOException("No usable quotes in the response")
                }
            }.onFailure { e ->
                currentCoroutineContext().ensureActive() // cancelled (replaced/stopped): not a failure
                AppLog.e(ctx, "Fetch", "No data; keeping last quotes", e)
                error = when (e) {
                    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
                        "No internet connection. Showing the last quotes."
                    is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException ->
                        "Yahoo Finance didn't respond. Showing the last quotes."
                    else -> "Couldn't update: ${(e as? HttpException)?.detail ?: e.message ?: e.javaClass.simpleName}. Showing the last quotes."
                }
            }.getOrNull()
            if (fresh != null) {
                store.putQuotes(fresh)
                changed = true
                val missing = symbols - fresh.map { it.symbol }.toSet()
                if (missing.isNotEmpty()) AppLog.w(ctx, "Fetch", "No quote for ${missing.joinToString()}")
                AppLog.i(ctx, "Fetch", "${fresh.size} quote(s) · ${System.currentTimeMillis() - t0} ms")
                fillDetails(ctx, store)
            }
            store.setError(error)
            AppLog.i(ctx, "Refresh", "Done in ${System.currentTimeMillis() - started} ms${if (error != null) " (with errors)" else ""}")
            return if (error == null) Result.success(widgets && (force || changed)) else Result.failure(Exception(error))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // the worker logs why Android stopped it
        } catch (e: Exception) {
            AppLog.e(ctx, "Refresh", "Failed", e)
            store.setError(e.message)
            return Result.failure(e)
        } finally {
            // Must run even when cancelled, or the spinner would outlive the refresh.
            withContext(NonCancellable) {
                if (spinner && widgets) store.setRefreshing(false)
                if (widgets && (force || changed)) {
                    val ms = timed { WidgetRenderer.renderAll(ctx) }
                    if (force) AppLog.i(ctx, "Refresh", "Widgets redrawn in $ms ms")
                }
            }
        }
    }

    /**
     * Currency and exchange for entries that don't have them yet (a few per refresh). Runs only
     * after a real fetch, so closed markets still cost no network; a failed lookup is retried
     * on a later fetch, a successful one with no currency is stored as "" and never repeated.
     */
    private suspend fun fillDetails(ctx: Context, store: Store) {
        val missing = store.appNow().watchlist.filter { it.currency == null && it.type != "INDEX" }.take(5)
        if (missing.isEmpty()) return
        val filled = missing.map { YahooFinance.withDetails(it) }.filter { it.currency != null }.associateBy { it.symbol }
        if (filled.isEmpty()) return
        store.updateApp { a -> a.copy(watchlist = a.watchlist.map { w -> filled[w.symbol]?.takeIf { w.currency == null } ?: w }) }
        AppLog.i(ctx, "Watchlist", "Looked up currency for ${filled.keys.joinToString()}")
    }

    private inline fun timed(block: () -> Unit): Long {
        val t = android.os.SystemClock.elapsedRealtime(); block(); return android.os.SystemClock.elapsedRealtime() - t
    }
}
