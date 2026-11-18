package app.helpmeweather

import app.helpmeweather.data.Condition
import app.helpmeweather.data.GoogleWeatherSource
import app.helpmeweather.data.Http
import app.helpmeweather.data.OpenMeteoSource
import app.helpmeweather.data.Place
import app.helpmeweather.data.Provider
import app.helpmeweather.data.upcomingHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    private val place = Place("Test", 37.42, -122.08)
    /** Fixtures are relative to now: parsing drops implausible (stale/far-future) timestamps. */
    private val base = System.currentTimeMillis() / 1000 / 3600 * 3600

    @Test fun googleResponseParses() {
        val cur = Http.json.parseToJsonElement(
            """{"currentTime":"2026-11-16T23:00:00Z","timeZone":{"id":"America/Los_Angeles"},"isDaytime":true,
            "weatherCondition":{"iconBaseUri":"https://maps.gstatic.com/weather/v1/showers","description":{"text":"Rain","languageCode":"en"},"type":"RAIN"},
            "temperature":{"degrees":12.4,"unit":"CELSIUS"},"feelsLikeTemperature":{"degrees":11.0,"unit":"CELSIUS"},
            "relativeHumidity":88,"uvIndex":1,"precipitation":{"probability":{"percent":80,"type":"RAIN"}},
            "wind":{"speed":{"value":14,"unit":"KILOMETERS_PER_HOUR"}},
            "currentConditionsHistory":{"maxTemperature":{"degrees":14.0},"minTemperature":{"degrees":9.0}}}""",
        )
        val hrs = Http.json.parseToJsonElement(
            """{"forecastHours":[
              {"interval":{"startTime":"2026-11-17T00:00:00Z","endTime":"2026-11-17T01:00:00Z"},"isDaytime":false,
               "weatherCondition":{"type":"SCATTERED_SHOWERS"},"temperature":{"degrees":11.5},"precipitation":{"probability":{"percent":40}}},
              {"interval":{"startTime":"2026-11-17T01:00:00Z"},"isDaytime":false,
               "weatherCondition":{"type":"MOSTLY_CLOUDY"},"temperature":{"degrees":10.9},"precipitation":{"probability":{"percent":10}}}
            ],"timeZone":{"id":"America/Los_Angeles"}}""",
        )
        // Today in the fixture's zone: the parser drops days that are already over.
        val today = java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles"))
        val days = Http.json.parseToJsonElement(
            """{"forecastDays":[{"displayDate":{"year":${today.year},"month":${today.monthValue},"day":${today.dayOfMonth}},
              "daytimeForecast":{"weatherCondition":{"type":"HEAVY_RAIN"},"precipitation":{"probability":{"percent":90}}},
              "maxTemperature":{"degrees":14.2},"minTemperature":{"degrees":8.7},
              "sunEvents":{"sunriseTime":"2026-11-16T14:50:00Z","sunsetTime":"2026-11-17T00:57:00Z"}}]}""",
        )
        val w = GoogleWeatherSource.parse(place, cur, hrs, days)
        assertEquals(Provider.GOOGLE, w.source)
        assertEquals("America/Los_Angeles", w.zoneId)
        assertEquals(12.4, w.current.tempC, 0.001)
        assertEquals(Condition.RAIN, w.current.condition)
        assertEquals("Rain", w.current.description)
        assertEquals(2, w.hourly.size)
        assertEquals(Condition.SCATTERED_SHOWERS, w.hourly[0].condition)
        assertFalse(w.hourly[0].isDay)
        assertEquals(40, w.hourly[0].precipProb)
        assertEquals(1, w.daily.size)
        assertEquals(Condition.HEAVY_RAIN, w.daily[0].condition)
        assertEquals(90, w.daily[0].precipProb)
    }

    @Test fun openMeteoResponseParses() {
        val j = Http.json.parseToJsonElement(
            """{"timezone":"Europe/Kyiv","current":{"time":${base + 600},"temperature_2m":-2.5,"apparent_temperature":-6,
              "relative_humidity_2m":90,"is_day":0,"weather_code":73,"wind_speed_10m":12.2,"uv_index":0},
              "hourly":{"time":[$base,${base + 3600}],"temperature_2m":[-2.4,-3.0],"weather_code":[73,null],
                        "precipitation_probability":[70,null],"is_day":[0,0]},
              "daily":{"time":[$base],"weather_code":[75],"temperature_2m_max":[-1.0],"temperature_2m_min":[-7.5],
                       "precipitation_probability_max":[85],"sunrise":[${base + 20000}],"sunset":[${base + 50000}]}}""",
        )
        val w = OpenMeteoSource.parse(place, j)
        assertEquals(Provider.OPEN_METEO, w.source)
        assertEquals(Condition.SNOW, w.current.condition)
        assertFalse(w.current.isDay)
        assertEquals(70, w.current.precipProb)
        assertEquals(2, w.hourly.size)
        assertEquals(Condition.CLOUDY, w.hourly[1].condition) // null code → safe default
        assertEquals(Condition.HEAVY_SNOW, w.daily[0].condition)
    }

    @Test fun everyGoogleTypeMapsDeliberately() {
        val wet = listOf("LIGHT_RAIN", "RAIN", "HEAVY_RAIN", "RAIN_SHOWERS", "SCATTERED_SHOWERS", "THUNDERSTORM", "SNOW", "HAIL", "RAIN_AND_SNOW")
        wet.forEach { assertTrue(it, GoogleWeatherSource.mapGoogle(it) !in setOf(Condition.CLEAR, Condition.CLOUDY)) }
        assertEquals(Condition.DRIZZLE, GoogleWeatherSource.mapGoogle("CHANCE_OF_SHOWERS"))
        assertEquals(Condition.SCATTERED_THUNDERSTORMS, GoogleWeatherSource.mapGoogle("SCATTERED_THUNDERSTORMS"))
        assertEquals(Condition.WINTRY_MIX, GoogleWeatherSource.mapGoogle("HAIL_SHOWERS"))
        assertEquals(Condition.CLOUDY, GoogleWeatherSource.mapGoogle("SOMETHING_NEW"))
    }

    @Test fun wmoCodesMapToGoogleBuckets() {
        assertEquals(Condition.CLEAR, OpenMeteoSource.mapWmo(0))
        assertEquals(Condition.FOG, OpenMeteoSource.mapWmo(45))
        assertEquals(Condition.DRIZZLE, OpenMeteoSource.mapWmo(61))
        assertEquals(Condition.HEAVY_RAIN, OpenMeteoSource.mapWmo(82))
        assertEquals(Condition.THUNDERSTORM, OpenMeteoSource.mapWmo(99))
        assertEquals(Condition.WINTRY_MIX, OpenMeteoSource.mapWmo(66))
    }

    @Test fun upcomingHoursDropsPastHours() {
        val j = Http.json.parseToJsonElement(
            """{"timezone":"UTC","current":{"temperature_2m":1,"weather_code":0,"is_day":1},
              "hourly":{"time":[$base,${base + 3600},${base + 7200},${base + 10800}],"temperature_2m":[1,2,3,4],"weather_code":[0,0,0,0],"is_day":[1,1,1,1]},
              "daily":{"time":[],"weather_code":[],"temperature_2m_max":[],"temperature_2m_min":[]}}""",
        )
        val w = OpenMeteoSource.parse(place, j)
        val up = w.upcomingHours(nowSec = base + 7300)
        assertEquals(base + 7200, up.first().epochSec) // current hour stays as "now"
        assertEquals(2, up.size)
    }

    @Test fun poisonedTimestampsAreDropped() {
        // A far-future epoch used to survive parsing and then crash every widget render.
        val j = Http.json.parseToJsonElement(
            """{"timezone":"UTC","current":{"temperature_2m":1,"weather_code":0,"is_day":1},
              "hourly":{"time":[$base,1000000000000000000],"temperature_2m":[1,2],"weather_code":[0,0],"is_day":[1,1]},
              "daily":{"time":[-5000000000,$base],"weather_code":[0,0],"temperature_2m_max":[3,4],"temperature_2m_min":[1,2]}}""",
        )
        val w = OpenMeteoSource.parse(place, j)
        assertEquals(listOf(base), w.hourly.map { it.epochSec })
        assertEquals(listOf(base), w.daily.map { it.epochSec })
    }

    @Test fun placeKeyIgnoresGpsJitter() {
        assertEquals(Place("a", 37.4221, -122.0841).key, Place("b", 37.4189, -122.0812).key)
    }
}
