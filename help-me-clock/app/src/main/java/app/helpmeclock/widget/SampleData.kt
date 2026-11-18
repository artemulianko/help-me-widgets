package app.helpmeclock.widget

import app.helpmeclock.data.NextAlarm
import app.helpmeclock.data.WidgetConfig
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Picker previews and the in-app preview when no alarm is set: the next 7:30 AM. */
object SampleData {
    fun alarm(now: Long = System.currentTimeMillis()): NextAlarm {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).atTime(LocalTime.of(7, 30)).atZone(zone).toInstant().toEpochMilli()
        val at = if (today > now) today else LocalDate.now(zone).plusDays(1).atTime(LocalTime.of(7, 30)).atZone(zone).toInstant().toEpochMilli()
        return NextAlarm(at, null)
    }

    fun state(cfg: WidgetConfig = WidgetConfig()) = WidgetState(cfg, alarm(), appWidgetId = 0)
}
