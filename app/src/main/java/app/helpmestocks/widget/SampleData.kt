package app.helpmestocks.widget

import app.helpmestocks.data.AppSettings
import app.helpmestocks.data.DefaultWatchlist
import app.helpmestocks.data.Quote
import app.helpmestocks.data.QuoteSet
import app.helpmestocks.data.WidgetConfig
import kotlin.math.sin
import kotlin.random.Random

/** Deterministic sample quotes used for the widget-picker preview and before the first fetch. */
object SampleData {
    private val samples = mapOf(
        "^GSPC" to (6812.40 to 0.42), "^IXIC" to (23140.12 to 0.88), "^DJI" to (47210.55 to -0.21),
        "GOOGL" to (312.75 to 1.36), "AAPL" to (354.17 to -0.62), "NVDA" to (198.40 to 2.14),
    )

    fun quotes(symbols: List<String> = DefaultWatchlist.map { it.symbol }, nowMs: Long = System.currentTimeMillis()): QuoteSet {
        val now = nowMs / 1000
        // A session that's ~60% through, like a weekday early afternoon.
        val start = now - 4 * 3600
        val end = start + 6 * 3600 + 1800
        return QuoteSet(symbols.associateWith { sym ->
            val (base, pct) = samples[sym] ?: (100.0 + (sym.hashCode() and 0xff) to ((sym.hashCode() % 300) / 100.0))
            val prev = base / (1 + pct / 100)
            val r = Random(sym.hashCode())
            val n = ((now - start) / 300).toInt() + 1
            val drift = base - prev
            var noise = 0.0
            val prices = (0 until n).map { i ->
                val f = i / (n - 1.0)
                noise = noise * 0.85 + (r.nextDouble() - 0.5) * prev * 0.0018
                if (i == n - 1) base else prev + drift * f + sin(f * 7 + sym.length) * prev * 0.0015 + noise
            }
            Quote(
                symbol = sym, price = base, change = base - prev, changePct = pct, previousClose = prev,
                sessionStart = start, sessionEnd = end,
                times = (0 until n).map { start + it * 300L }, prices = prices, fetchedAt = nowMs,
            )
        })
    }

    fun state(cfg: WidgetConfig = WidgetConfig(), app: AppSettings = AppSettings()): WidgetState =
        WidgetState(app, cfg, quotes(app.watchlist.map { it.symbol }), refreshing = false, appWidgetId = 0)
}
