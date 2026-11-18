package app.helpmeclock

import app.helpmeclock.data.alarmLabel
import app.helpmeclock.data.stripAmPm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class AlarmLabelTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(17, 23, 30) // Tue 17 Nov, 11:30 PM

    @Test fun withinADayShowsOnlyTheTime() {
        assertEquals("7:30 AM", alarmLabel(at(18, 7, 30), now, zone, Locale.US, is24h = false))
        assertEquals("7:30", alarmLabel(at(18, 7, 30), now, zone, Locale.US, is24h = true))
    }

    @Test fun laterThanADayAddsTheWeekday() {
        assertEquals("Thu 7:30 AM", alarmLabel(at(19, 7, 30), now, zone, Locale.US, is24h = false))
        assertEquals("Thu 23:45", alarmLabel(at(19, 23, 45), now, zone, Locale.US, is24h = true))
    }

    @Test fun exactlyTwentyFourHoursIsLater() {
        assertEquals("Wed 11:30 PM", alarmLabel(at(18, 23, 30), now, zone, Locale.US, is24h = false))
    }

    @Test fun followsTheLocale() {
        assertEquals("Do. 7:30", alarmLabel(at(19, 7, 30), now, zone, Locale.GERMANY, is24h = true))
    }

    @Test fun stripsAmPmMarkerWherever() {
        assertEquals("h:mm", stripAmPm("h:mm a"))
        assertEquals("h:mm", stripAmPm("h:mm a"))
        assertEquals("h:mm", stripAmPm("a h:mm"))
        assertEquals("H:mm", stripAmPm("H:mm"))
    }
}
