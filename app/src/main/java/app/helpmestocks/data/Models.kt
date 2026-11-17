package app.helpmestocks.data

import kotlinx.serialization.Serializable

/** One watchlist entry. [label] is what the widget shows ("AAPL", or "S&P 500" for ^GSPC). */
@Serializable
data class Ticker(
    val symbol: String,
    val name: String,
    val label: String = symbol,
    /** Yahoo quote type: EQUITY, ETF, INDEX, CRYPTOCURRENCY, MUTUALFUND, CURRENCY, FUTURE. */
    val type: String = "EQUITY",
    /** Display name of the exchange ("NASDAQ", "XETRA"); null for indices. */
    val exchange: String? = null,
    /** ISO code as Yahoo reports it ("USD", "EUR", "GBp" = pence); null until looked up, "" if Yahoo has none. */
    val currency: String? = null,
)

/**
 * Latest regular-session quote plus the intraday points for the sparkline.
 * Session bounds come from the provider, so market hours work for every exchange
 * (and crypto, whose "session" is the whole UTC day) without a holiday calendar.
 */
@Serializable
data class Quote(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePct: Double,
    val previousClose: Double? = null,
    /** Regular session of the data, epoch seconds. */
    val sessionStart: Long,
    val sessionEnd: Long,
    /** Intraday times (epoch s) and prices, same length. */
    val times: List<Long> = emptyList(),
    val prices: List<Double> = emptyList(),
    val fetchedAt: Long,
) {
    /** Whether this quote is at least as recent as [other]: by last trade, then by fetch time. */
    fun isNotOlderThan(other: Quote): Boolean {
        val a = times.lastOrNull() ?: 0L
        val b = other.times.lastOrNull() ?: 0L
        return if (a != b) a > b else fetchedAt >= other.fetchedAt
    }

    /** Usable numbers and timestamps (checked before a quote may replace a cached one). */
    fun isValid(nowSec: Long = System.currentTimeMillis() / 1000): Boolean =
        price.isFinite() && price > 0 && change.isFinite() && changePct.isFinite() &&
            (previousClose?.isFinite() ?: true) && prices.all { it.isFinite() } && times.size == prices.size &&
            sessionStart in (nowSec - 30L * 86_400)..(nowSec + 2L * 86_400) && sessionEnd >= sessionStart &&
            // Epoch seconds within a day of the session (rejects milliseconds or garbage).
            times.all { it in (sessionStart - 86_400)..(sessionEnd + 86_400) }
}

@Serializable
data class QuoteSet(val quotes: Map<String, Quote> = emptyMap()) {
    val fetchedAt: Long get() = quotes.values.maxOfOrNull { it.fetchedAt } ?: 0L
    operator fun get(symbol: String) = quotes[symbol]

    /**
     * Drops entries with non-finite prices or implausible timestamps so a bad response can't
     * poison the cache and break rendering.
     */
    fun sanitized(nowSec: Long = System.currentTimeMillis() / 1000): QuoteSet {
        return QuoteSet(quotes.filterValues { it.isValid(nowSec) })
    }
}

/** Where the default watchlist starts: the three US indices Apple's widget also opens with, plus a few megacaps. */
val DefaultWatchlist = listOf(
    Ticker("^GSPC", "S&P 500", "S&P 500", "INDEX"),
    Ticker("^IXIC", "Nasdaq Composite", "Nasdaq", "INDEX"),
    Ticker("^DJI", "Dow Jones Industrial Average", "Dow Jones", "INDEX"),
    Ticker("GOOGL", "Alphabet Inc.", "GOOGL", "EQUITY", "NASDAQ", "USD"),
    Ticker("AAPL", "Apple Inc.", "AAPL", "EQUITY", "NASDAQ", "USD"),
    Ticker("NVDA", "NVIDIA Corporation", "NVDA", "EQUITY", "NASDAQ", "USD"),
)

/** One batched request covers the whole list (Yahoo's limit per request is 20). */
const val MAX_WATCHLIST = YahooFinance.BATCH
