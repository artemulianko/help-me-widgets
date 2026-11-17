package app.nimbus.weather.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

interface WeatherSource {
    suspend fun fetch(place: Place): WeatherData
}

// ---- tiny JSON helpers -------------------------------------------------------------------
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.getOrNull(i)
internal val JsonElement?.d: Double? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
internal val JsonElement?.i: Int? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
internal val JsonElement?.l: Long? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
internal val JsonElement?.b: Boolean? get() = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } }
internal val JsonElement?.s: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray).orEmpty()

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** ~1 km precision: plenty for a forecast, and providers never get your exact position. */
internal fun Double.coarse(): String = "%.2f".format(Locale.US, this)

// ---- Google Maps Platform Weather API — same Google weather backend Pixel Weather uses ----
/**
 * @param appHeaders `X-Android-Package` / `X-Android-Cert`, so the key can be restricted to this
 *   app in Google Cloud ("Android apps" restriction) and is useless if extracted elsewhere.
 */
class GoogleWeatherSource(
    private val apiKey: String,
    private val appHeaders: Map<String, String> = emptyMap(),
    private val language: String = Locale.getDefault().toLanguageTag(),
) : WeatherSource {
    private val base = "https://weather.googleapis.com/v1"

    private fun url(path: String, place: Place, extra: String) =
        "$base/$path?key=${enc(apiKey)}&location.latitude=${place.lat.coarse()}&location.longitude=${place.lon.coarse()}" +
            "&unitsSystem=METRIC&languageCode=${enc(language)}$extra"

    override suspend fun fetch(place: Place): WeatherData = coroutineScope {
        val cur = async { Http.getJson(url("currentConditions:lookup", place, ""), appHeaders) }
        val hrs = async { Http.getJson(url("forecast/hours:lookup", place, "&hours=24&pageSize=24"), appHeaders) }
        val days = async { Http.getJson(url("forecast/days:lookup", place, "&days=10&pageSize=10"), appHeaders) }
        parse(place, cur.await(), hrs.await(), days.await())
    }

    companion object {
        fun parse(place: Place, c: JsonElement, h: JsonElement, d: JsonElement): WeatherData {
            val zone = c["timeZone"]["id"].s ?: h["timeZone"]["id"].s ?: ZoneId.systemDefault().id
            val zid = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())
            val current = Current(
                tempC = c["temperature"]["degrees"].d ?: error("no temperature"),
                feelsLikeC = c["feelsLikeTemperature"]["degrees"].d,
                condition = mapGoogle(c["weatherCondition"]["type"].s),
                isDay = c["isDaytime"].b ?: true,
                description = c["weatherCondition"]["description"]["text"].s,
                humidity = c["relativeHumidity"].i,
                windKph = c["wind"]["speed"]["value"].d,
                precipProb = c["precipitation"]["probability"]["percent"].i,
                uvIndex = c["uvIndex"].i,
            )
            val hourly = h["forecastHours"].arr.mapNotNull { e ->
                val t = e["interval"]["startTime"].s?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() } ?: return@mapNotNull null
                Hour(
                    epochSec = t,
                    tempC = e["temperature"]["degrees"].d ?: return@mapNotNull null,
                    condition = mapGoogle(e["weatherCondition"]["type"].s),
                    isDay = e["isDaytime"].b ?: true,
                    precipProb = e["precipitation"]["probability"]["percent"].i,
                )
            }
            val daily = d["forecastDays"].arr.mapNotNull { e ->
                val dd = e["displayDate"]
                val date = runCatching { LocalDate.of(dd["year"].i!!, dd["month"].i!!, dd["day"].i!!) }.getOrNull()
                    ?: return@mapNotNull null
                val dayPart = e["daytimeForecast"]
                Day(
                    epochSec = date.atStartOfDay(zid).toEpochSecond(),
                    highC = e["maxTemperature"]["degrees"].d ?: return@mapNotNull null,
                    lowC = e["minTemperature"]["degrees"].d ?: return@mapNotNull null,
                    condition = mapGoogle(dayPart["weatherCondition"]["type"].s),
                    precipProb = dayPart["precipitation"]["probability"]["percent"].i,
                    sunrise = e["sunEvents"]["sunriseTime"].s?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() },
                    sunset = e["sunEvents"]["sunsetTime"].s?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() },
                )
            }
            return WeatherData(place, zid.id, System.currentTimeMillis(), Provider.GOOGLE, current, hourly, daily).sanitized()
        }

        /** Google WeatherCondition.Type → our set (per Google's condition→icon table). */
        fun mapGoogle(type: String?): Condition = when (type) {
            "CLEAR" -> Condition.CLEAR
            "MOSTLY_CLEAR" -> Condition.MOSTLY_CLEAR
            "PARTLY_CLOUDY" -> Condition.PARTLY_CLOUDY
            "MOSTLY_CLOUDY" -> Condition.MOSTLY_CLOUDY
            "CLOUDY" -> Condition.CLOUDY
            "WINDY" -> Condition.WINDY
            "LIGHT_RAIN_SHOWERS", "CHANCE_OF_SHOWERS", "LIGHT_RAIN" -> Condition.DRIZZLE
            "SCATTERED_SHOWERS" -> Condition.SCATTERED_SHOWERS
            "WIND_AND_RAIN", "RAIN_SHOWERS", "LIGHT_TO_MODERATE_RAIN", "RAIN" -> Condition.RAIN
            "HEAVY_RAIN_SHOWERS", "MODERATE_TO_HEAVY_RAIN", "HEAVY_RAIN", "RAIN_PERIODICALLY_HEAVY" -> Condition.HEAVY_RAIN
            "LIGHT_SNOW_SHOWERS", "LIGHT_SNOW" -> Condition.FLURRIES
            "CHANCE_OF_SNOW_SHOWERS", "LIGHT_TO_MODERATE_SNOW", "SNOW", "SNOWSTORM" -> Condition.SNOW
            "SCATTERED_SNOW_SHOWERS", "SNOW_SHOWERS" -> Condition.SCATTERED_SNOW
            "HEAVY_SNOW_SHOWERS", "MODERATE_TO_HEAVY_SNOW", "HEAVY_SNOW", "SNOW_PERIODICALLY_HEAVY", "HEAVY_SNOW_STORM" -> Condition.HEAVY_SNOW
            "BLOWING_SNOW" -> Condition.BLOWING_SNOW
            "RAIN_AND_SNOW", "HAIL_SHOWERS" -> Condition.WINTRY_MIX
            "HAIL" -> Condition.HAIL
            "THUNDERSTORM", "THUNDERSHOWER", "LIGHT_THUNDERSTORM_RAIN", "HEAVY_THUNDERSTORM" -> Condition.THUNDERSTORM
            "SCATTERED_THUNDERSTORMS" -> Condition.SCATTERED_THUNDERSTORMS
            else -> Condition.CLOUDY
        }
    }
}

// ---- Open-Meteo — free, keyless, open data (ECMWF/DWD/NOAA/… "best match" blend) ---------
class OpenMeteoSource : WeatherSource {
    override suspend fun fetch(place: Place): WeatherData {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat.coarse()}&longitude=${place.lon.coarse()}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,wind_speed_10m,uv_index" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=10&forecast_hours=36&past_hours=1&timeformat=unixtime"
        return parse(place, Http.getJson(url))
    }

    companion object {
        fun parse(place: Place, j: JsonElement): WeatherData {
            val zone = j["timezone"].s ?: ZoneId.systemDefault().id
            val c = j["current"]
            val h = j["hourly"]
            val d = j["daily"]
            val hourly = h["time"].arr.mapIndexedNotNull { idx, t ->
                Hour(
                    epochSec = t.l ?: return@mapIndexedNotNull null,
                    tempC = h["temperature_2m"][idx].d ?: return@mapIndexedNotNull null,
                    condition = mapWmo(h["weather_code"][idx].i),
                    isDay = h["is_day"][idx].b ?: true,
                    precipProb = h["precipitation_probability"][idx].i,
                )
            }
            val daily = d["time"].arr.mapIndexedNotNull { idx, t ->
                Day(
                    epochSec = t.l ?: return@mapIndexedNotNull null,
                    highC = d["temperature_2m_max"][idx].d ?: return@mapIndexedNotNull null,
                    lowC = d["temperature_2m_min"][idx].d ?: return@mapIndexedNotNull null,
                    condition = mapWmo(d["weather_code"][idx].i),
                    precipProb = d["precipitation_probability_max"][idx].i,
                    sunrise = d["sunrise"][idx].l,
                    sunset = d["sunset"][idx].l,
                )
            }
            val nowEpoch = c["time"].l ?: (System.currentTimeMillis() / 1000)
            val current = Current(
                tempC = c["temperature_2m"].d ?: error("no temperature"),
                feelsLikeC = c["apparent_temperature"].d,
                condition = mapWmo(c["weather_code"].i),
                isDay = c["is_day"].b ?: true,
                humidity = c["relative_humidity_2m"].i,
                windKph = c["wind_speed_10m"].d,
                uvIndex = c["uv_index"].d?.toInt(),
                precipProb = hourly.lastOrNull { it.epochSec <= nowEpoch }?.precipProb,
            )
            return WeatherData(place, zone, System.currentTimeMillis(), Provider.OPEN_METEO, current, hourly, daily).sanitized()
        }

        /** WMO weather interpretation codes → our set, matching Google's buckets. */
        fun mapWmo(code: Int?): Condition = when (code) {
            0 -> Condition.CLEAR
            1 -> Condition.MOSTLY_CLEAR
            2 -> Condition.PARTLY_CLOUDY
            3 -> Condition.CLOUDY
            45, 48 -> Condition.FOG
            51, 53, 55, 61 -> Condition.DRIZZLE
            80 -> Condition.SCATTERED_SHOWERS
            63, 81 -> Condition.RAIN
            65, 82 -> Condition.HEAVY_RAIN
            71, 77 -> Condition.FLURRIES
            73 -> Condition.SNOW
            85 -> Condition.SCATTERED_SNOW
            75, 86 -> Condition.HEAVY_SNOW
            56, 57, 66, 67 -> Condition.WINTRY_MIX
            95, 96, 99 -> Condition.THUNDERSTORM
            else -> Condition.CLOUDY
        }
    }
}
