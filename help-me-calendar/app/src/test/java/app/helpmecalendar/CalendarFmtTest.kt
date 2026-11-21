package app.helpmecalendar

import app.helpmecalendar.data.AppJson
import app.helpmecalendar.data.CalendarEvent
import app.helpmecalendar.data.CalendarFmt
import app.helpmecalendar.data.Calendars
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

    @Test fun filterUpcomingClampsOngoingMultiDayAndDropsEnded() {
        val zone = ZoneId.of("Europe/Warsaw")
        val today = LocalDate.of(2026, 11, 22)
        val nowMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val ended = CalendarEvent(
            1, 1, "Past",
            today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
            false, 0xFF0000FF.toInt(),
        )
        val multiDayAllDay = CalendarEvent(
            2, 1, "Conference",
            LocalDate.of(2026, 11, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            LocalDate.of(2026, 11, 24).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            true, 0xFF00FF00.toInt(),
        )
        val todayTimed = CalendarEvent(
            3, 1, "Lunch",
            today.atTime(13, 0).atZone(zone).toInstant().toEpochMilli(),
            today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli(),
            false, 0xFFFF0000.toInt(),
        )
        val farFuture = CalendarEvent(
            4, 1, "Next month",
            today.plusDays(10).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
            today.plusDays(10).atTime(11, 0).atZone(zone).toInstant().toEpochMilli(),
            false, 0xFFFF0000.toInt(),
        )
        val out = Calendars.filterUpcoming(listOf(farFuture, todayTimed, ended, multiDayAllDay), daysAhead = 7, nowMs = nowMs, zone = zone)
        assertEquals(listOf("Conference", "Lunch"), out.map { it.title })
        assertEquals(today, out.first().effectiveDate(today, zone))
    }

    @Test fun buildMonthEventColorsCapsAtThreeDistinctColorsPerDay() {
        val zone = ZoneId.of("UTC")
        val nov = YearMonth.of(2026, 11)
        val d22 = LocalDate.of(2026, 11, 22).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val raw = listOf(
            CalendarEvent(1, 1, "A", d22, d22 + 86_400_000L, true, 0xFF111111.toInt()),
            CalendarEvent(2, 1, "B", d22, d22 + 86_400_000L, true, 0xFF111111.toInt()),
            CalendarEvent(3, 2, "C", d22, d22 + 86_400_000L, true, 0xFF222222.toInt()),
            CalendarEvent(4, 3, "D", d22, d22 + 86_400_000L, true, 0xFF333333.toInt()),
            CalendarEvent(5, 4, "E", d22, d22 + 86_400_000L, true, 0xFF444444.toInt()),
        )
        val colors = Calendars.buildMonthEventColors(raw, nov, zone)
        assertEquals(listOf(0xFF111111.toInt(), 0xFF222222.toInt(), 0xFF333333.toInt()), colors[22])
    }
}
