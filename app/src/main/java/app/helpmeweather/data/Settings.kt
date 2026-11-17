package app.helpmeweather.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

enum class TempUnit { SYSTEM, CELSIUS, FAHRENHEIT }
/**
 * Forecast hour labels. SYSTEM matches the phone ("3 PM" or "16:00"); H12 is always "3 PM";
 * HOUR_ONLY strips it to the two-digit hour in the phone's 12/24-hour style ("03" or "16").
 * H24 is a legacy value from 0.1.0 and behaves as SYSTEM.
 */
enum class TimeFormat { SYSTEM, H12, H24, HOUR_ONLY }
enum class ProviderPref { GOOGLE, OPEN_METEO }

@Serializable
data class AppSettings(
    val provider: ProviderPref = ProviderPref.GOOGLE,
    val googleApiKey: String = "",
    val tempUnit: TempUnit = TempUnit.SYSTEM,
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    /** Background refresh interval. 0 = manual only. */
    val refreshMinutes: Int = 60,
    val unmeteredOnly: Boolean = false,
    /** Last resolved device location (used by widgets set to "Current location"). */
    val deviceLocation: Place? = null,
    val deviceLocationAt: Long = 0,
    val recentPlaces: List<Place> = emptyList(),
)

enum class WidgetStyle { CLASSIC, BOLD }
enum class ForecastMode { HOURLY, DAILY, NONE }
enum class Palette { DYNAMIC, BLUE, TEAL, GREEN, AMBER, ROSE, VIOLET, MONO }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class BgTone { SURFACE, PRIMARY, SECONDARY }
/** GOOGLE = Google's weather art (as in Pixel Weather); SYMBOLS = Material Symbols tinted with the accent. */
enum class IconStyle { GOOGLE, SYMBOLS }
enum class TapAction { PIXEL_WEATHER, APP, REFRESH, NONE }

@Serializable
data class WidgetConfig(
    /** null = follow device location. */
    val location: Place? = null,
    val style: WidgetStyle = WidgetStyle.CLASSIC,
    val forecast: ForecastMode = ForecastMode.HOURLY,
    val palette: Palette = Palette.DYNAMIC,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val bgTone: BgTone = BgTone.SURFACE,
    /** 0..100 in steps of 10. */
    val opacity: Int = 100,
    val iconStyle: IconStyle = IconStyle.GOOGLE,
    val showLocation: Boolean = true,
    val showHighLow: Boolean = true,
    val showCondition: Boolean = true,
    val showFeelsLike: Boolean = false,
    val showRefresh: Boolean = true,
    val showUpdated: Boolean = false,
    val tapAction: TapAction = TapAction.PIXEL_WEATHER,
)

private val Context.prefsStore by preferencesDataStore("settings")
private val Context.cacheStore by preferencesDataStore("weather_cache")

/** Single access point for persisted settings + cached forecasts. */
class Store(private val context: Context) {
    private val appKey = stringPreferencesKey("app")
    private fun widgetKey(id: Int) = stringPreferencesKey("w_$id")
    private val refreshingKey = stringPreferencesKey("refreshing")
    private val errorKey = stringPreferencesKey("last_error")

    private val json get() = Http.json

    private fun Preferences.app(): AppSettings =
        this[appKey]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()

    private fun Preferences.widget(id: Int): WidgetConfig =
        this[widgetKey(id)]?.let { runCatching { json.decodeFromString<WidgetConfig>(it) }.getOrNull() } ?: WidgetConfig()

    val app: Flow<AppSettings> = context.prefsStore.data.map { it.app() }
    fun widget(id: Int): Flow<WidgetConfig> = context.prefsStore.data.map { it.widget(id) }
    val refreshing: Flow<Boolean> = context.prefsStore.data.map { it[refreshingKey] == "1" }
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
        context.prefsStore.edit { if (on) it[refreshingKey] = "1" else it.remove(refreshingKey) }
    }

    suspend fun setError(msg: String?) {
        context.prefsStore.edit { if (msg == null) it.remove(errorKey) else it[errorKey] = AppLog.redact(msg).take(300) }
    }

    // ---- forecast cache (one entry per ~1km grid cell) ----
    private fun cacheKey(k: String) = stringPreferencesKey(k)

    fun weather(place: Place?): Flow<WeatherData?> = context.cacheStore.data.map { p ->
        place?.let { p[cacheKey(it.key)] }?.let { runCatching { json.decodeFromString<WeatherData>(it).sanitized() }.getOrNull() }
    }

    suspend fun weatherNow(place: Place?) = weather(place).first()

    suspend fun putWeather(data: WeatherData) {
        context.cacheStore.edit { it[cacheKey(data.place.key)] = json.encodeToString(data) }
    }

    /** Drop cached forecasts no widget or app screen uses anymore. */
    suspend fun pruneWeather(keep: Set<String>) {
        context.cacheStore.edit { p -> p.asMap().keys.filter { it.name !in keep }.forEach { p.remove(it) } }
    }
}
