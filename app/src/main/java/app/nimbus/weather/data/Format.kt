package app.nimbus.weather.data

import android.content.Context
import android.text.format.DateFormat
import androidx.core.text.util.LocalePreferences
import app.nimbus.weather.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Formatting that honours app settings, falling back to the system's regional preferences. */
class Fmt(private val ctx: Context, app: AppSettings, zone: String?) {
    val fahrenheit = when (app.tempUnit) {
        TempUnit.CELSIUS -> false
        TempUnit.FAHRENHEIT -> true
        // Android 14+ "Regional preferences → Temperature" (falls back to locale default).
        TempUnit.SYSTEM -> LocalePreferences.getTemperatureUnit() == LocalePreferences.TemperatureUnit.FAHRENHEIT
    }
    val is24h = when (app.timeFormat) {
        TimeFormat.H12 -> false
        TimeFormat.H24 -> true
        TimeFormat.SYSTEM -> DateFormat.is24HourFormat(ctx)
    }
    val zoneId: ZoneId = zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    private val locale: Locale = Locale.getDefault()

    fun temp(c: Double): String = "${deg(c)}°"
    fun deg(c: Double): Int = (if (fahrenheit) c * 9 / 5 + 32 else c).roundToInt()

    private val hourFmt = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h a", locale)
    private val clockFmt = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale)

    fun hour(epochSec: Long): String = hourFmt.format(Instant.ofEpochSecond(epochSec).atZone(zoneId))
    fun clock(epochMs: Long): String = clockFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun day(epochSec: Long, index: Int): String =
        if (index == 0) ctx.getString(R.string.today)
        else Instant.ofEpochSecond(epochSec).atZone(zoneId).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

    fun condition(c: Current): String = c.description ?: conditionName(c.condition)

    fun conditionName(c: Condition): String = ctx.getString(
        when (c) {
            Condition.CLEAR -> R.string.c_clear
            Condition.MOSTLY_CLEAR -> R.string.c_mostly_clear
            Condition.PARTLY_CLOUDY -> R.string.c_partly_cloudy
            Condition.MOSTLY_CLOUDY -> R.string.c_mostly_cloudy
            Condition.CLOUDY -> R.string.c_cloudy
            Condition.FOG -> R.string.c_fog
            Condition.DRIZZLE -> R.string.c_drizzle
            Condition.RAIN -> R.string.c_rain
            Condition.HEAVY_RAIN -> R.string.c_heavy_rain
            Condition.SNOW -> R.string.c_snow
            Condition.HEAVY_SNOW -> R.string.c_heavy_snow
            Condition.SLEET -> R.string.c_sleet
            Condition.HAIL -> R.string.c_hail
            Condition.THUNDERSTORM -> R.string.c_thunderstorm
            Condition.WINDY -> R.string.c_windy
        }
    )
}

/** Hourly entries from the current hour on (so cached data stays correct between refreshes). */
fun WeatherData.upcomingHours(nowSec: Long = System.currentTimeMillis() / 1000): List<Hour> =
    hourly.dropWhile { it.epochSec + 3600 <= nowSec }

/** Days from today (in the location's zone) on. */
fun WeatherData.upcomingDays(nowSec: Long = System.currentTimeMillis() / 1000): List<Day> =
    daily.dropWhile { it.epochSec + 86_400 <= nowSec }

fun Condition.iconRes(isDay: Boolean, themed: Boolean): Int {
    val base = when (this) {
        Condition.CLEAR -> if (isDay) R.drawable.ic_w_clear_day else R.drawable.ic_w_clear_night
        Condition.MOSTLY_CLEAR -> if (isDay) R.drawable.ic_w_mostly_clear_day else R.drawable.ic_w_mostly_clear_night
        Condition.PARTLY_CLOUDY -> if (isDay) R.drawable.ic_w_partly_cloudy_day else R.drawable.ic_w_partly_cloudy_night
        Condition.MOSTLY_CLOUDY -> if (isDay) R.drawable.ic_w_mostly_cloudy_day else R.drawable.ic_w_mostly_cloudy_night
        Condition.CLOUDY -> R.drawable.ic_w_cloudy
        Condition.FOG -> R.drawable.ic_w_fog
        Condition.DRIZZLE -> R.drawable.ic_w_drizzle
        Condition.RAIN -> R.drawable.ic_w_rain
        Condition.HEAVY_RAIN -> R.drawable.ic_w_heavy_rain
        Condition.SNOW -> R.drawable.ic_w_snow
        Condition.HEAVY_SNOW -> R.drawable.ic_w_heavy_snow
        Condition.SLEET -> R.drawable.ic_w_sleet
        Condition.HAIL -> R.drawable.ic_w_hail
        Condition.THUNDERSTORM -> R.drawable.ic_w_thunderstorm
        Condition.WINDY -> R.drawable.ic_w_windy
    }
    return if (themed) ThemedIcons.map[base] ?: base else base
}
