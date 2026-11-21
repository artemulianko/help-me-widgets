package app.helpmecalendar.widget

import app.helpmecalendar.data.CalendarEvent
import app.helpmecalendar.data.WidgetConfig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Sample events used in the in-app preview (when no permission or no events) and render tests. */
object SampleData {
    private const val BLUE = 0xFF4285F4.toInt()
    private const val TEAL = 0xFF009688.toInt()
    private const val AMBER = 0xFFF4B400.toInt()
    private const val ROSE = 0xFFE91E63.toInt()

    fun events(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        fun at(dayOffset: Long, hour: Int, min: Int): Long =
            today.plusDays(dayOffset).atTime(LocalTime.of(hour, min)).atZone(zone).toInstant().toEpochMilli()
        fun allDayStart(dayOffset: Long): Long =
            today.plusDays(dayOffset).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        return listOf(
            CalendarEvent(0, 1, "Team standup", at(0, 10, 0), at(0, 10, 30), false, BLUE, "Meet"),
            CalendarEvent(0, 2, "Lunch with Alex", at(0, 13, 0), at(0, 14, 0), false, TEAL, "Bistro"),
            CalendarEvent(0, 1, "Design review", at(0, 16, 30), at(0, 17, 15), false, BLUE),
            CalendarEvent(0, 3, "Mom's birthday", allDayStart(1), allDayStart(2), true, ROSE),
            CalendarEvent(0, 2, "Dentist", at(1, 11, 0), at(1, 12, 0), false, AMBER, "Downtown"),
            CalendarEvent(0, 1, "Sprint planning", at(2, 10, 0), at(2, 11, 30), false, BLUE),
        )
    }

    fun monthColors(today: LocalDate = LocalDate.now()): Map<Int, List<Int>> {
        val len = today.lengthOfMonth()
        return buildMap {
            put(today.dayOfMonth, listOf(BLUE, TEAL))
            put((today.dayOfMonth + 1).coerceAtMost(len), listOf(ROSE, AMBER))
            put((today.dayOfMonth + 2).coerceAtMost(len), listOf(BLUE))
            put((today.dayOfMonth + 5).coerceAtMost(len), listOf(TEAL))
            put((today.dayOfMonth - 3).coerceAtLeast(1), listOf(AMBER))
        }
    }

    fun state(cfg: WidgetConfig = WidgetConfig(), nowMs: Long = System.currentTimeMillis()): WidgetState {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val ev = events(nowMs, zone).filter { cfg.showAllDay || !it.allDay }
        return WidgetState(cfg, hasPermission = true, events = ev, monthEventColors = monthColors(today), appWidgetId = 0, nowMs = nowMs)
    }
}
