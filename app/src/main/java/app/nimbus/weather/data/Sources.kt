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

// ---- Google Maps Platform Weather API — same Google weather backend Pixel Weather uses ----
class GoogleWeatherSource(private val apiKey: String, private val language: String = Locale.getDefault().toLanguageTag()) : WeatherSource {
    private val base = "https://weather.googleapis.com/v1"

    private fun url(path: String, place: Place, extra: String) =
        "$base/$path?key=${enc(apiKey)}&location.latitude=${place.lat}&location.longitude=${place.lon}" +
            "&unitsSystem=METRIC&languageCode=${enc(language)}$extra"

    override suspend fun fetch(place: Place): WeatherData = coroutineScope {
        val cur = async { Http.getJson(url("currentConditions:lookup", place, "")) }
        val hrs = async { Http.getJson(url("forecast/hours:lookup", place, "&hours=24&pageSize=24")) }
        val days = async { Http.getJson(url("forecast/days:lookup", place, "&days=10&pageSize=10")) }
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
                val t = e["interval"]["startTime"].s?.let { Instant.parse(it).epochSecond } ?: return@mapNotNull null
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
            return WeatherData(place, zid.id, System.currentTimeMillis(), Provider.GOOGLE, current, hourly, daily)
        }

        /** Maps Google's WeatherCondition.Type enum onto our compact set. */
        fun mapGoogle(type: String?): Condition {
            val t = type ?: return Condition.CLOUDY
            return when {
                t == "CLEAR" -> Condition.CLEAR
                t == "MOSTLY_CLEAR" -> Condition.MOSTLY_CLEAR
                t == "PARTLY_CLOUDY" -> Condition.PARTLY_CLOUDY
                t == "MOSTLY_CLOUDY" -> Condition.MOSTLY_CLOUDY
                t == "CLOUDY" -> Condition.CLOUDY
                "THUNDER" in t -> Condition.THUNDERSTORM
                "HAIL" in t -> Condition.HAIL
                "RAIN_AND_SNOW" in t || "SLEET" in t || "FREEZING" in t -> Condition.SLEET
                "SNOW" in t -> if ("HEAVY" in t || "STORM" in t || "BLOWING" in t) Condition.HEAVY_SNOW else Condition.SNOW
                t == "WIND_AND_RAIN" -> Condition.HEAVY_RAIN
                "RAIN" in t || "SHOWER" in t -> when {
                    "HEAVY" in t -> Condition.HEAVY_RAIN
                    t.startsWith("LIGHT_RAIN") || t.startsWith("CHANCE") || t == "LIGHT_RAIN_SHOWERS" -> Condition.DRIZZLE
                    else -> Condition.RAIN
                }
                "WIND" in t -> Condition.WINDY
                "FOG" in t || "HAZE" in t || "MIST" in t -> Condition.FOG
                else -> Condition.CLOUDY
            }
        }
    }
}

// ---- Open-Meteo — free, keyless, open data (ECMWF/DWD/NOAA/… "best match" blend) ---------
class OpenMeteoSource : WeatherSource {
    override suspend fun fetch(place: Place): WeatherData {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
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
            return WeatherData(place, zone, System.currentTimeMillis(), Provider.OPEN_METEO, current, hourly, daily)
        }

        /** WMO weather interpretation codes. */
        fun mapWmo(code: Int?): Condition = when (code) {
            0 -> Condition.CLEAR
            1 -> Condition.MOSTLY_CLEAR
            2 -> Condition.PARTLY_CLOUDY
            3 -> Condition.CLOUDY
            45, 48 -> Condition.FOG
            51, 53, 55 -> Condition.DRIZZLE
            56, 57, 66, 67 -> Condition.SLEET
            61, 63, 80, 81 -> Condition.RAIN
            65, 82 -> Condition.HEAVY_RAIN
            71, 73, 77, 85 -> Condition.SNOW
            75, 86 -> Condition.HEAVY_SNOW
            95 -> Condition.THUNDERSTORM
            96, 99 -> Condition.HAIL
            else -> Condition.CLOUDY
        }
    }
}
