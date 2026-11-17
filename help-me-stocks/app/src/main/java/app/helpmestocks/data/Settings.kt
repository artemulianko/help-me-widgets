package app.helpmestocks.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    /** Shared by every widget; order is display order. */
    val watchlist: List<Ticker> = DefaultWatchlist,
    /** Background refresh interval while a market is open. 0 = manual only. */
    val refreshMinutes: Int = 30,
    val unmeteredOnly: Boolean = false,
)

/** WATCHLIST = rows from the shared list; SINGLE = one stock, large, with its chart. */
enum class Content { WATCHLIST, SINGLE }
enum class ChangeMode { PERCENT, PRICE }
/** TONAL = Material 3 container chip; SOLID = filled (iOS-like); TEXT = coloured text only. */
enum class ChangeStyle { TONAL, SOLID, TEXT }
/** GREEN_UP = Western convention; RED_UP = East Asian convention. */
enum class UpColor { GREEN_UP, RED_UP }
enum class Palette { DYNAMIC, BLUE, TEAL, GREEN, AMBER, ROSE, VIOLET, MONO }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class BgTone { SURFACE, PRIMARY, SECONDARY }
enum class TapAction { YAHOO, GOOGLE, APP, REFRESH, NONE }

@Serializable
data class WidgetConfig(
    val content: Content = Content.WATCHLIST,
    /** Symbol for [Content.SINGLE]; null = first in the watchlist. */
    val single: String? = null,
    val changeMode: ChangeMode = ChangeMode.PERCENT,
    val changeStyle: ChangeStyle = ChangeStyle.TONAL,
    val upColor: UpColor = UpColor.GREEN_UP,
    val palette: Palette = Palette.DYNAMIC,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val bgTone: BgTone = BgTone.SURFACE,
    /** 0..100 in steps of 10. */
    val opacity: Int = 100,
    val showName: Boolean = true,
    val showChart: Boolean = true,
    val showHeader: Boolean = true,
    val showRefresh: Boolean = true,
    val tapAction: TapAction = TapAction.YAHOO,
)

private const val REFRESH_SPINNER_MS = 90_000L

private val Context.prefsStore by preferencesDataStore("settings")
private val Context.cacheStore by preferencesDataStore("quote_cache")

/** Single access point for persisted settings + cached quotes. */
class Store(private val context: Context) {
    private val appKey = stringPreferencesKey("app")
    private fun widgetKey(id: Int) = stringPreferencesKey("w_$id")
    private val refreshingKey = stringPreferencesKey("refreshing")
    private val errorKey = stringPreferencesKey("last_error")
    private val quotesKey = stringPreferencesKey("quotes")

    private val json get() = Http.json

    private fun Preferences.app(): AppSettings =
        this[appKey]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()

    private fun Preferences.widget(id: Int): WidgetConfig =
        this[widgetKey(id)]?.let { runCatching { json.decodeFromString<WidgetConfig>(it) }.getOrNull() } ?: WidgetConfig()

    val app: Flow<AppSettings> = context.prefsStore.data.map { it.app() }
    fun widget(id: Int): Flow<WidgetConfig> = context.prefsStore.data.map { it.widget(id) }
    /**
     * Spinner state, stored as the time it started so it can never stick: a refresh that was
     * killed (process death, cancellation) stops showing after [REFRESH_SPINNER_MS].
     */
    val refreshing: Flow<Boolean> = context.prefsStore.data.map { p ->
        p[refreshingKey]?.toLongOrNull()?.let { System.currentTimeMillis() - it in 0..REFRESH_SPINNER_MS } == true
    }
    val lastError: Flow<String?> = context.prefsStore.data.map { it[errorKey] }

    suspend fun appNow() = app.first()
    suspend fun widgetNow(id: Int) = widget(id).first()

    suspend fun updateApp(f: (AppSettings) -> AppSettings) {
        context.prefsStore.edit { it[appKey] = json.encodeToString(f(it.app())) }
    }

    suspend fun updateWidget(id: Int, f: (WidgetConfig) -> WidgetConfig) {
        context.prefsStore.edit { it[widgetKey(id)] = json.encodeToString(f(it.widget(id))) }
    }

    suspend fun deleteWidget(id: Int) {
        context.prefsStore.edit { it.remove(widgetKey(id)) }
    }

    suspend fun setRefreshing(on: Boolean) {
        context.prefsStore.edit { if (on) it[refreshingKey] = System.currentTimeMillis().toString() else it.remove(refreshingKey) }
    }

    suspend fun setError(msg: String?) {
        context.prefsStore.edit { if (msg == null) it.remove(errorKey) else it[errorKey] = AppLog.redact(msg).take(300) }
    }

    // ---- quote cache: one entry shared by every widget ----

    val quotes: Flow<QuoteSet> = context.cacheStore.data.map { p ->
        p[quotesKey]?.let { runCatching { json.decodeFromString<QuoteSet>(it).sanitized() }.getOrNull() } ?: QuoteSet()
    }

    suspend fun quotesNow() = quotes.first()

    /**
     * Merges fresh quotes in. A quote only replaces a cached one when it's valid and not older
     * (so an overlapping, slower refresh can't roll prices back), and symbols are pruned against
     * the watchlist as it is *now*, not as it was when the request started.
     */
    suspend fun putQuotes(fresh: Collection<Quote>) {
        val keep = appNow().watchlist.map { it.symbol }.toSet()
        context.cacheStore.edit { p ->
            val old = p[quotesKey]?.let { runCatching { json.decodeFromString<QuoteSet>(it) }.getOrNull() }?.quotes.orEmpty()
            val merged = old.toMutableMap()
            fresh.filter { it.isValid() }.forEach { q ->
                val prev = merged[q.symbol]
                if (prev == null || !prev.isValid() || q.isNotOlderThan(prev)) merged[q.symbol] = q
            }
            p[quotesKey] = json.encodeToString(QuoteSet(merged.filterKeys { it in keep }))
        }
    }
}
