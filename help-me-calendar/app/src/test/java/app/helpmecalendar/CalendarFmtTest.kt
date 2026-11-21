package app.helpmecalendar

import app.helpmecalendar.data.AppJson
import app.helpmecalendar.data.CalendarEvent
import app.helpmecalendar.data.CalendarFmt
import app.helpmecalendar.data.WidgetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class CalendarFmtTest {

    @Test fun monthGridStartsOnRequestedFirstDayOfWeek() {
        // November 2026 starts on a Sunday (Nov 1 = Sunday, 30 days).
        val nov = YearMonth.of(2026, 11)
        val sunGrid = CalendarFmt.monthGrid(nov, DayOfWeek.SUNDAY)
        assertEquals(5, sunGrid.size)
        assertEquals(1, sunGrid[0][0])
        assertEquals(30, sunGrid[4][1])
        assertNull(sunGrid[4][2])

        val monGrid = CalendarFmt.monthGrid(nov, DayOfWeek.MONDAY)
        assertEquals(6, monGrid.size)
        assertNull(monGrid[0][0])
        assertEquals(1, monGrid[0][6])
        assertEquals(30, monGrid[5][0])
    }

    @Test fun allDayEventDateUsesUtcMidnightRegardlessOfLocalZone() {
        val nov22Utc = LocalDate.of(2026, 11, 22).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ev = CalendarEvent(1, 1, "Birthday", nov22Utc, nov22Utc + 86_400_000L, allDay = true, color = 0xFF0000FF.toInt())
        // Must stay Nov 22 in both America/Los_Angeles (UTC-8) and Europe/Warsaw (UTC+1).
        assertEquals(LocalDate.of(2026, 11, 22), ev.localDate(ZoneId.of("America/Los_Angeles")))
        assertEquals(LocalDate.of(2026, 11, 22), ev.localDate(ZoneId.of("Europe/Warsaw")))
    }

    @Test fun eventTimeFormats24hAnd12hAndAllDay() {
        val zone = ZoneId.of("UTC")
        val start = LocalDate.of(2026, 11, 22).atTime(14, 30).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 11, 22).atTime(15, 15).atZone(zone).toInstant().toEpochMilli()
        assertEquals("All day", CalendarFmt.eventTimeRange(true, start, end, true, "All day", zone, Locale.US))
        assertEquals("14:30 – 15:15", CalendarFmt.eventTimeRange(false, start, end, true, "All day", zone, Locale.US))
        assertEquals("2:30 PM – 3:15 PM", CalendarFmt.eventTimeRange(false, start, end, false, "All day", zone, Locale.US))
        assertEquals("14:30", CalendarFmt.eventStart(false, start, true, "All day", zone, Locale.US))
    }

    @Test fun widgetConfigRoundTripsCalendarIds() {
        val cfg = WidgetConfig(calendarIds = setOf(1L, 4L, 9L), showAllDay = false, daysAhead = 14)
        val json = AppJson.encodeToString(cfg)
        val back = AppJson.decodeFromString<WidgetConfig>(json)
        assertEquals(setOf(1L, 4L, 9L), back.calendarIds)
        assertEquals(false, back.showAllDay)
        assertEquals(14, back.daysAhead)

        val defaultBack = AppJson.decodeFromString<WidgetConfig>(AppJson.encodeToString(WidgetConfig()))
        assertNull(defaultBack.calendarIds)
        assertTrue(defaultBack.showAllDay)
    }
}
