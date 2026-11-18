package app.helpmeclock.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Palette { DYNAMIC, BLUE, TEAL, GREEN, AMBER, ROSE, VIOLET, MONO }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class BgTone { SURFACE, PRIMARY, SECONDARY }
/** Weight of the big numerals only; the date and alarm always stay regular. */
enum class Weight(val value: Int) { LIGHT(300), REGULAR(400), MEDIUM(500) }
/**
 * How the date line reads. Patterns use pattern letters, so names follow your language; the
 * order (day before or after month) is your choice, not the locale's. A format that doesn't fit
 * a size falls back to a shorter one rather than being cut off.
 */
enum class DateStyle(val pattern: String) {
    DAY_MONTH("EEE, d MMM"),        // Tue, 17 Nov
    MONTH_DAY("EEE, MMM d"),        // Tue, Nov 17
    LONG("EEEE, d MMMM"),           // Tuesday, 17 November
}
/** CLOCK = the phone's clock app (the natural target, like Pixel's own clock widgets). */
enum class TapAction { CLOCK, APP, NONE }

@Serializable
data class WidgetConfig(
    val weight: Weight = Weight.REGULAR,
    val palette: Palette = Palette.DYNAMIC,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val bgTone: BgTone = BgTone.SURFACE,
    /** 0..100 in steps of 10. */
    val opacity: Int = 100,
    /** Off: the date leads (for when the status bar already shows the time). */
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showAlarm: Boolean = true,
    val dateStyle: DateStyle = DateStyle.DAY_MONTH,
    val tapAction: TapAction = TapAction.CLOCK,
)

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

private val Context.prefsStore by preferencesDataStore("settings")

/** Per-widget settings. The clock has no data to cache: the launcher ticks the time itself. */
class Store(private val context: Context) {
    private fun widgetKey(id: Int) = stringPreferencesKey("w_$id")

    private fun Preferences.widget(id: Int): WidgetConfig =
        this[widgetKey(id)]?.let { runCatching { AppJson.decodeFromString<WidgetConfig>(it) }.getOrNull() } ?: WidgetConfig()

    fun widget(id: Int): Flow<WidgetConfig> = context.prefsStore.data.map { it.widget(id) }
    suspend fun widgetNow(id: Int) = widget(id).first()

    suspend fun updateWidget(id: Int, f: (WidgetConfig) -> WidgetConfig) {
        context.prefsStore.edit { it[widgetKey(id)] = AppJson.encodeToString(f(it.widget(id))) }
    }

    suspend fun deleteWidget(id: Int) {
        context.prefsStore.edit { it.remove(widgetKey(id)) }
    }
}
