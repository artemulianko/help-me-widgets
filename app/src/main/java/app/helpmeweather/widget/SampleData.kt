package app.helpmeweather.widget

import android.content.Context
import app.helpmeweather.data.AppSettings
import app.helpmeweather.data.Condition
import app.helpmeweather.data.Current
import app.helpmeweather.data.Day
import app.helpmeweather.data.Hour
import app.helpmeweather.data.Place
import app.helpmeweather.data.Provider
import app.helpmeweather.data.WeatherData
import app.helpmeweather.data.WidgetConfig
import java.time.LocalDate
import java.time.ZoneId

/** Static forecast used for the widget-picker preview and as a fallback in the editor. */
object SampleData {
    fun weather(): WeatherData {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis() / 1000
        val hourStart = now - now % 3600
        val conds = listOf(Condition.PARTLY_CLOUDY, Condition.MOSTLY_CLOUDY, Condition.CLOUDY, Condition.RAIN, Condition.DRIZZLE, Condition.PARTLY_CLOUDY, Condition.MOSTLY_CLEAR, Condition.CLEAR)
        val hourly = (0 until 24).map { i ->
            Hour(hourStart + i * 3600L, 21.0 - i * 0.6 + (if (i < 4) i else 4) * 0.4, conds[i % conds.size], i < 6, if (i in 3..4) 60 else 5)
        }
        val today = LocalDate.now(zone)
        val dayConds = listOf(Condition.PARTLY_CLOUDY, Condition.RAIN, Condition.THUNDERSTORM, Condition.CLOUDY, Condition.CLEAR, Condition.MOSTLY_CLEAR, Condition.SNOW)
        val highs = listOf(23.0, 19.0, 17.0, 18.0, 24.0, 26.0, 21.0)
        val lows = listOf(14.0, 12.0, 11.0, 10.0, 13.0, 16.0, 12.0)
        val daily = (0 until 7).map { i ->
            Day(today.plusDays(i.toLong()).atStartOfDay(zone).toEpochSecond(), highs[i], lows[i], dayConds[i], if (i in 1..2) 70 else 0)
        }
        return WeatherData(
            place = Place("Mountain View", 37.39, -122.08), zoneId = zone.id, fetchedAt = System.currentTimeMillis(),
            source = Provider.GOOGLE,
            current = Current(21.0, 20.0, Condition.PARTLY_CLOUDY, true, null, 55, 9.0, 5, 4),
            hourly = hourly, daily = daily,
        )
    }

    fun state(ctx: Context, cfg: WidgetConfig = WidgetConfig(location = Place("Mountain View", 37.39, -122.08))): WidgetState {
        val w = weather()
        return WidgetState(AppSettings(), cfg, w, refreshing = false, place = cfg.location ?: w.place, appWidgetId = 0)
    }
}
