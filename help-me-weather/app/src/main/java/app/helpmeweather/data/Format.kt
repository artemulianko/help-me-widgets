package app.helpmeweather.data

import android.content.Context
import android.text.format.DateFormat
import androidx.core.text.util.LocalePreferences
import app.helpmeweather.R
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
        else -> DateFormat.is24HourFormat(ctx)
    }
    val zoneId: ZoneId = zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    private val locale: Locale = Locale.getDefault()

    fun temp(c: Double): String = "${deg(c)}°"
    fun deg(c: Double): Int = (if (fahrenheit) c * 9 / 5 + 32 else c).roundToInt()

    private val hourOnly = app.timeFormat == TimeFormat.HOUR_ONLY
    private val hourFmt = DateTimeFormatter.ofPattern(
        when {
            hourOnly -> if (is24h) "HH" else "hh"   // "16" / "03"
            is24h -> "HH:mm"                        // "16:00"
            else -> "h a"                           // "3 PM"
        },
        locale,
    )
    private val clockFmt = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale)

    fun hour(epochSec: Long): String = runCatching { hourFmt.format(Instant.ofEpochSecond(epochSec).atZone(zoneId)) }.getOrDefault("–")
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
            Condition.WINDY -> R.string.c_windy
            Condition.DRIZZLE -> R.string.c_drizzle
            Condition.SCATTERED_SHOWERS -> R.string.c_scattered_showers
            Condition.RAIN -> R.string.c_rain
            Condition.HEAVY_RAIN -> R.string.c_heavy_rain
            Condition.FLURRIES -> R.string.c_flurries
            Condition.SNOW -> R.string.c_snow
            Condition.SCATTERED_SNOW -> R.string.c_scattered_snow
            Condition.HEAVY_SNOW -> R.string.c_heavy_snow
            Condition.BLOWING_SNOW -> R.string.c_blowing_snow
            Condition.WINTRY_MIX -> R.string.c_wintry_mix
            Condition.HAIL -> R.string.c_hail
            Condition.THUNDERSTORM -> R.string.c_thunderstorm
            Condition.SCATTERED_THUNDERSTORMS -> R.string.c_scattered_thunderstorms
        }
    )
}

/** Hourly entries from the current hour on (so cached data stays correct between refreshes). */
fun WeatherData.upcomingHours(nowSec: Long = System.currentTimeMillis() / 1000): List<Hour> =
    hourly.dropWhile { it.epochSec + 3600 <= nowSec }

/** Days from today (in the location's zone) on. */
fun WeatherData.upcomingDays(nowSec: Long = System.currentTimeMillis() / 1000): List<Day> =
    daily.dropWhile { it.epochSec + 86_400 <= nowSec }

/** Which variant of the Google icon to use: follow the system, or force light/dark art. */
enum class IconTone { AUTO, LIGHT, DARK }

/** Google's weather icon (same art as Pixel Weather), as light/dark/auto drawable. */
fun Condition.googleIcon(isDay: Boolean, tone: IconTone): Int = when (tone) {
    IconTone.AUTO -> when (this) {
        Condition.CLEAR -> if (isDay) R.drawable.wx_sunny else R.drawable.wx_clear
        Condition.MOSTLY_CLEAR -> if (isDay) R.drawable.wx_mostly_sunny else R.drawable.wx_mostly_clear
        Condition.PARTLY_CLOUDY -> if (isDay) R.drawable.wx_partly_cloudy else R.drawable.wx_partly_clear
        Condition.MOSTLY_CLOUDY -> if (isDay) R.drawable.wx_mostly_cloudy else R.drawable.wx_mostly_cloudy_night
        Condition.CLOUDY, Condition.FOG -> R.drawable.wx_cloudy
        Condition.WINDY -> R.drawable.wx_windy_breezy
        Condition.DRIZZLE -> R.drawable.wx_drizzle
        Condition.SCATTERED_SHOWERS -> R.drawable.wx_scattered_showers
        Condition.RAIN -> R.drawable.wx_showers
        Condition.HEAVY_RAIN -> R.drawable.wx_heavy
        Condition.FLURRIES -> R.drawable.wx_flurries
        Condition.SNOW -> R.drawable.wx_snow_showers
        Condition.SCATTERED_SNOW -> R.drawable.wx_scattered_snow
        Condition.HEAVY_SNOW -> R.drawable.wx_heavy_snow
        Condition.BLOWING_SNOW -> R.drawable.wx_blowing_snow
        Condition.WINTRY_MIX -> R.drawable.wx_wintry_mix
        Condition.HAIL -> R.drawable.wx_sleet_hail
        Condition.THUNDERSTORM -> R.drawable.wx_strong_tstorms
        Condition.SCATTERED_THUNDERSTORMS -> R.drawable.wx_isolated_tstorms
    }
    IconTone.LIGHT -> GoogleIconVariants.light.getValue(googleIcon(isDay, IconTone.AUTO))
    IconTone.DARK -> GoogleIconVariants.dark.getValue(googleIcon(isDay, IconTone.AUTO))
}

/** Material Symbols Rounded glyph, tinted with the theme's accent ("Themed" icon style). */
fun Condition.symbolIcon(isDay: Boolean): Int = when (this) {
    Condition.CLEAR -> if (isDay) R.drawable.ms_sunny else R.drawable.ms_clear_night
    Condition.MOSTLY_CLEAR, Condition.PARTLY_CLOUDY -> if (isDay) R.drawable.ms_partly_cloudy_day else R.drawable.ms_partly_cloudy_night
    Condition.MOSTLY_CLOUDY, Condition.CLOUDY -> R.drawable.ms_cloud
    Condition.FOG -> R.drawable.ms_foggy
    Condition.WINDY -> R.drawable.ms_air
    Condition.DRIZZLE, Condition.SCATTERED_SHOWERS -> R.drawable.ms_rainy_light
    Condition.RAIN -> R.drawable.ms_rainy
    Condition.HEAVY_RAIN -> R.drawable.ms_rainy_heavy
    Condition.FLURRIES, Condition.SNOW -> R.drawable.ms_weather_snowy
    Condition.SCATTERED_SNOW -> R.drawable.ms_cloudy_snowing
    Condition.HEAVY_SNOW -> R.drawable.ms_snowing_heavy
    Condition.BLOWING_SNOW -> R.drawable.ms_snowing
    Condition.WINTRY_MIX -> R.drawable.ms_weather_mix
    Condition.HAIL -> R.drawable.ms_weather_hail
    Condition.THUNDERSTORM, Condition.SCATTERED_THUNDERSTORMS -> R.drawable.ms_thunderstorm
}

private object GoogleIconVariants {
    val light: Map<Int, Int> = mapOf(
        R.drawable.wx_sunny to R.drawable.wx_sunny_light, R.drawable.wx_clear to R.drawable.wx_clear_light,
        R.drawable.wx_mostly_sunny to R.drawable.wx_mostly_sunny_light, R.drawable.wx_mostly_clear to R.drawable.wx_mostly_clear_light,
        R.drawable.wx_partly_cloudy to R.drawable.wx_partly_cloudy_light, R.drawable.wx_partly_clear to R.drawable.wx_partly_clear_light,
        R.drawable.wx_mostly_cloudy to R.drawable.wx_mostly_cloudy_light, R.drawable.wx_mostly_cloudy_night to R.drawable.wx_mostly_cloudy_night_light,
        R.drawable.wx_cloudy to R.drawable.wx_cloudy_light, R.drawable.wx_windy_breezy to R.drawable.wx_windy_breezy_light,
        R.drawable.wx_drizzle to R.drawable.wx_drizzle_light, R.drawable.wx_scattered_showers to R.drawable.wx_scattered_showers_light,
        R.drawable.wx_showers to R.drawable.wx_showers_light, R.drawable.wx_heavy to R.drawable.wx_heavy_light,
        R.drawable.wx_flurries to R.drawable.wx_flurries_light, R.drawable.wx_snow_showers to R.drawable.wx_snow_showers_light,
        R.drawable.wx_scattered_snow to R.drawable.wx_scattered_snow_light, R.drawable.wx_heavy_snow to R.drawable.wx_heavy_snow_light,
        R.drawable.wx_blowing_snow to R.drawable.wx_blowing_snow_light, R.drawable.wx_wintry_mix to R.drawable.wx_wintry_mix_light,
        R.drawable.wx_sleet_hail to R.drawable.wx_sleet_hail_light, R.drawable.wx_strong_tstorms to R.drawable.wx_strong_tstorms_light,
        R.drawable.wx_isolated_tstorms to R.drawable.wx_isolated_tstorms_light,
    )
    val dark: Map<Int, Int> = mapOf(
        R.drawable.wx_sunny to R.drawable.wx_sunny_dark, R.drawable.wx_clear to R.drawable.wx_clear_dark,
        R.drawable.wx_mostly_sunny to R.drawable.wx_mostly_sunny_dark, R.drawable.wx_mostly_clear to R.drawable.wx_mostly_clear_dark,
        R.drawable.wx_partly_cloudy to R.drawable.wx_partly_cloudy_dark, R.drawable.wx_partly_clear to R.drawable.wx_partly_clear_dark,
        R.drawable.wx_mostly_cloudy to R.drawable.wx_mostly_cloudy_dark, R.drawable.wx_mostly_cloudy_night to R.drawable.wx_mostly_cloudy_night_dark,
        R.drawable.wx_cloudy to R.drawable.wx_cloudy_dark, R.drawable.wx_windy_breezy to R.drawable.wx_windy_breezy_dark,
        R.drawable.wx_drizzle to R.drawable.wx_drizzle_dark, R.drawable.wx_scattered_showers to R.drawable.wx_scattered_showers_dark,
        R.drawable.wx_showers to R.drawable.wx_showers_dark, R.drawable.wx_heavy to R.drawable.wx_heavy_dark,
        R.drawable.wx_flurries to R.drawable.wx_flurries_dark, R.drawable.wx_snow_showers to R.drawable.wx_snow_showers_dark,
        R.drawable.wx_scattered_snow to R.drawable.wx_scattered_snow_dark, R.drawable.wx_heavy_snow to R.drawable.wx_heavy_snow_dark,
        R.drawable.wx_blowing_snow to R.drawable.wx_blowing_snow_dark, R.drawable.wx_wintry_mix to R.drawable.wx_wintry_mix_dark,
        R.drawable.wx_sleet_hail to R.drawable.wx_sleet_hail_dark, R.drawable.wx_strong_tstorms to R.drawable.wx_strong_tstorms_dark,
        R.drawable.wx_isolated_tstorms to R.drawable.wx_isolated_tstorms_dark,
    )
}
