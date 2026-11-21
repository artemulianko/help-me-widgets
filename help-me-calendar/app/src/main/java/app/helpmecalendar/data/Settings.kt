package app.helpmecalendar.data

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

/** CALENDAR = the phone's calendar app (the natural target). */
enum class TapAction { CALENDAR, APP, NONE }

@Serializable
data class WidgetConfig(
    val palette: Palette = Palette.DYNAMIC,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val bgTone: BgTone = BgTone.SURFACE,
    /** 0..100 in steps of 10. */
    val opacity: Int = 100,
    /**
     * Per-widget calendar filter. Null means all visible calendars on the device; a set restricts
     * this widget to only those calendar IDs (e.g. Personal vs Work).
     */
    val calendarIds: Set<Long>? = null,
    val showAllDay: Boolean = true,
    val showLocation: Boolean = false,
    /** 3-day upcoming mini-strip with calendar-color dots under the date on 4×2. */
    val showUpcomingDays: Boolean = true,
    /** Show a cozy lounging cat illustration when there are no upcoming events. */
    val showEmptyCat: Boolean = true,
    /** How many days ahead to include in the agenda: 1 (today), 3, 7, or 14. */
    val daysAhead: Int = 7,
    val tapAction: TapAction = TapAction.CALENDAR,
)

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

private val Context.prefsStore by preferencesDataStore("settings")

/** Per-widget settings. Events are read directly from Android's CalendarContract. */
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
