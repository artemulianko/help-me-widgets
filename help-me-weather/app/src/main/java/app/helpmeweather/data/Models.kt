package app.helpmeweather.data

import kotlinx.serialization.Serializable

/**
 * Normalised condition set shared by every provider. Granularity mirrors Google's weather
 * icon set, so a Google forecast renders with exactly the icon Pixel Weather shows.
 */
@Serializable
enum class Condition {
    CLEAR, MOSTLY_CLEAR, PARTLY_CLOUDY, MOSTLY_CLOUDY, CLOUDY, FOG, WINDY,
    DRIZZLE, SCATTERED_SHOWERS, RAIN, HEAVY_RAIN,
    FLURRIES, SNOW, SCATTERED_SNOW, HEAVY_SNOW, BLOWING_SNOW, WINTRY_MIX, HAIL,
    THUNDERSTORM, SCATTERED_THUNDERSTORMS,
}

@Serializable
data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val region: String? = null,
) {
    /** ~1 km grid key so tiny GPS jitter doesn't create new cache entries. */
    val key: String get() = "%.2f,%.2f".format(java.util.Locale.US, lat, lon)
}

@Serializable
data class Current(
    val tempC: Double,
    val feelsLikeC: Double? = null,
    val condition: Condition,
    val isDay: Boolean,
    val description: String? = null,
    val humidity: Int? = null,
    val windKph: Double? = null,
    val precipProb: Int? = null,
    val uvIndex: Int? = null,
)

@Serializable
data class Hour(
    val epochSec: Long,
    val tempC: Double,
    val condition: Condition,
    val isDay: Boolean,
    val precipProb: Int? = null,
)

@Serializable
data class Day(
    /** Local midnight of the forecast day (in the location's zone), epoch seconds. */
    val epochSec: Long,
    val highC: Double,
    val lowC: Double,
    val condition: Condition,
    val precipProb: Int? = null,
    val sunrise: Long? = null,
    val sunset: Long? = null,
)

@Serializable
data class WeatherData(
    val place: Place,
    val zoneId: String,
    val fetchedAt: Long,
    val source: Provider,
    val current: Current,
    val hourly: List<Hour>,
    val daily: List<Day>,
) {
    val today: Day? get() = daily.firstOrNull()

    /**
     * Drops entries with implausible timestamps (outside ~2 days back to ~3 weeks ahead) so a
     * bad provider response can't poison the cache and break rendering.
     */
    fun sanitized(nowSec: Long = System.currentTimeMillis() / 1000): WeatherData {
        val range = (nowSec - 2 * 86_400)..(nowSec + 21 * 86_400)
        return copy(
            hourly = hourly.filter { it.epochSec in range && it.tempC.isFinite() },
            daily = daily.filter { it.epochSec in range && it.highC.isFinite() && it.lowC.isFinite() },
        )
    }
}

@Serializable
enum class Provider { GOOGLE, OPEN_METEO }
