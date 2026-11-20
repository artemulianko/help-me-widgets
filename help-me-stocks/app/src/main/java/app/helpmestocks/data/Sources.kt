package app.helpmestocks.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.getOrNull(i)
internal val JsonElement?.d: Double? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
internal val JsonElement?.l: Long? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal val JsonElement?.s: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray).orEmpty()

/**
 * Yahoo Finance's public chart endpoints — the data behind Apple's Stocks widget. No key or
 * account. `spark` returns price, change and the intraday line for up to 20 symbols in one
 * ~2 KB gzipped response, so a whole watchlist costs a single request.
 */
object YahooFinance {
    private val hosts = listOf("https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com")
    const val BATCH = 20

    /** Fetches quotes in batches of [BATCH]; each batch tries the second host if the first fails. */
    suspend fun quotes(symbols: List<String>, now: Long = System.currentTimeMillis()): List<Quote> =
        symbols.distinct().chunked(BATCH).flatMap { batch ->
            val q = batch.joinToString(",") { enc(it) }
            parseSpark(onAnyHost("/v8/finance/spark?symbols=$q&range=1d&interval=5m"), now)
        }

    /** Currency and exchange for one symbol (search results don't include the currency). */
    suspend fun details(symbol: String): Pair<String?, String?> {
        val m = onAnyHost("/v8/finance/chart/${enc(symbol)}?range=1d&interval=1d")["chart"]["result"][0]["meta"]
        return m["currency"].s to m["fullExchangeName"].s
    }

    /** Fills in the currency (and a better exchange name) for a ticker, keeping it as is on failure. */
    suspend fun withDetails(t: Ticker): Ticker = try {
        val (cur, exch) = details(t.symbol)
        // "" marks "looked up, none reported" so it isn't requested again on every refresh.
        t.copy(currency = cur ?: t.currency ?: "", exchange = if (t.type == "INDEX") null else exchangeName(t.exchange ?: exch))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // a cancelled refresh stops here instead of looking up the next ticker
    } catch (e: Exception) {
        t
    }

    /** Nasdaq's market tiers (NasdaqGS/GM/CM) all read as "NASDAQ"; NYSE's likewise. */
    fun exchangeName(e: String?): String? = when {
        e == null -> null
        e.startsWith("Nasdaq", ignoreCase = true) -> "NASDAQ"
        e.startsWith("NYSE", ignoreCase = true) -> "NYSE"
        else -> e
    }

    suspend fun search(query: String): List<Ticker> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return parseSearch(onAnyHost("/v1/finance/search?q=${enc(q)}&quotesCount=10&newsCount=0&listsCount=0&enableFuzzyQuery=false"))
    }

    private suspend fun onAnyHost(path: String): JsonElement {
        var last: Exception? = null
        for (h in hosts) {
            try {
                return Http.getJson(h + path)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("No response")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** `{ "AAPL": { timestamp: [...], close: [...], previousClose, fulldayPrice, … }, … }` */
    fun parseSpark(root: JsonElement, now: Long): List<Quote> {
        (root["spark"]["error"]["description"].s)?.let { throw java.io.IOException(it) }
        val obj = root as? JsonObject ?: throw java.io.IOException("Unexpected response")
        return obj.mapNotNull { (key, v) ->
            val ts = v["timestamp"].arr
            val cs = v["close"].arr
            val pairs = ts.indices.mapNotNull { i ->
                val t = ts[i].l ?: return@mapNotNull null
                val c = cs.getOrNull(i).d ?: return@mapNotNull null
                if (c.isFinite()) t to c else null
            }
            val prev = v["previousClose"].d ?: v["chartPreviousClose"].d
            val price = v["fulldayPrice"].d ?: pairs.lastOrNull()?.second ?: return@mapNotNull null
            val change = v["fulldayChange"].d ?: prev?.let { price - it } ?: 0.0
            val pct = v["fulldayChangePercent"].d ?: prev?.takeIf { it != 0.0 }?.let { change / it * 100 } ?: 0.0
            val start = v["start"].l ?: pairs.firstOrNull()?.first ?: return@mapNotNull null
            val end = v["end"].l ?: pairs.lastOrNull()?.first ?: start
            Quote(
                symbol = v["symbol"].s ?: key, price = price, change = change, changePct = pct, previousClose = prev,
                sessionStart = start, sessionEnd = end,
                times = pairs.map { it.first }, prices = pairs.map { it.second }, fetchedAt = now,
            )
        }
    }

    private val searchable = setOf("EQUITY", "ETF", "INDEX", "CRYPTOCURRENCY", "MUTUALFUND", "CURRENCY", "FUTURE")

    fun parseSearch(root: JsonElement): List<Ticker> = root["quotes"].arr.mapNotNull { q ->
        val symbol = q["symbol"].s ?: return@mapNotNull null
        val type = q["quoteType"].s ?: return@mapNotNull null
        if (type !in searchable) return@mapNotNull null
        val name = (q["longname"].s ?: q["shortname"].s ?: symbol).trim()
        Ticker(symbol, name, labelFor(symbol, type, q["shortname"].s), type, if (type == "INDEX") null else exchangeName(q["exchDisp"].s ?: q["exchange"].s))
    }

    /**
     * Tickers read best as symbols; indices and FX read best by name. Listings outside the US
     * carry an exchange suffix ("VWCE.DE"); the widget shows the exchange name instead
     * ("VWCE" · XETRA), so the suffix is dropped from the label.
     */
    fun labelFor(symbol: String, type: String, shortName: String?): String = when {
        type == "INDEX" && !shortName.isNullOrBlank() -> shortName.trim().take(16)
        type == "CURRENCY" && symbol.endsWith("=X") && symbol.length == 8 -> symbol.substring(0, 3) + "/" + symbol.substring(3, 6)
        type in setOf("EQUITY", "ETF", "MUTUALFUND") && Regex("^[A-Z0-9&-]+\\.[A-Z]{1,3}$").matches(symbol) -> symbol.substringBeforeLast('.')
        else -> symbol.removePrefix("^")
    }
}
