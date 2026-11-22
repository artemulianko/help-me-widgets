package app.helpmecalendar.data

import android.content.Context
import android.text.format.DateFormat
import app.helpmecalendar.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Pure formatting and calendar-grid helpers (easy to unit-test). */
object CalendarFmt {

    fun weekdayFull(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).uppercase(locale)

    fun weekdayShort(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).uppercase(locale)

    fun monthName(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.month.getDisplayName(TextStyle.FULL, locale)

    fun monthYear(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}"

    /** "Today", "Tomorrow", or "Mon, 24 Nov" / "Mon 24". */
    fun dayHeader(ctx: Context, date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (date) {
        today -> ctx.getString(R.string.today)
        today.plusDays(1) -> ctx.getString(R.string.tomorrow)
        else -> DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(date)
    }

    /** Short day prefix for inline future-day events ("Tomorrow", "Mon 24"). */
    fun shortDayPrefix(ctx: Context, date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (date) {
        today -> ""
        today.plusDays(1) -> ctx.getString(R.string.tomorrow)
        else -> DateTimeFormatter.ofPattern("EEE d", locale).format(date)
    }

    /** "All day", "10:00 – 11:00", or "10:00 AM – 11:00 AM". */
    fun eventTimeRange(
        allDay: Boolean,
        startMs: Long,
        endMs: Long,
        is24h: Boolean,
        allDayLabel: String,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (allDay) return allDayLabel
        val pattern = runCatching { DateFormat.getBestDateTimePattern(locale, if (is24h) "Hm" else "hm") }
            .getOrElse { if (is24h) "HH:mm" else "h:mm a" }
        val fmt = DateTimeFormatter.ofPattern(pattern, locale)
        val s = fmt.format(Instant.ofEpochMilli(startMs).atZone(zone))
        if (endMs <= startMs) return s
        val e = fmt.format(Instant.ofEpochMilli(endMs).atZone(zone))
        return "$s – $e"
    }

    /** Compact single start time ("All day", "10:00", "10 AM"). */
    fun eventStart(
        allDay: Boolean,
        startMs: Long,
        is24h: Boolean,
        allDayLabel: String,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (allDay) return allDayLabel
        val zt = Instant.ofEpochMilli(startMs).atZone(zone)
        val skeleton = when {
            is24h -> "Hm"
            zt.minute == 0 -> "h"
            else -> "hm"
        }
        val pattern = runCatching { DateFormat.getBestDateTimePattern(locale, skeleton) }
            .getOrElse {
                when {
                    is24h -> "HH:mm"
                    zt.minute == 0 -> "h a"
                    else -> "h:mm a"
                }
            }
        return DateTimeFormatter.ofPattern(pattern, locale).format(zt)
    }

    fun is24h(ctx: Context): Boolean = DateFormat.is24HourFormat(ctx)

    /** 7 narrow weekday headers starting on the locale's first day of week (e.g. M T W T F S S). */
    fun weekHeaders(locale: Locale = Locale.getDefault()): List<String> {
        val first = WeekFields.of(locale).firstDayOfWeek
        return (0 until 7).map { offset ->
            first.plus(offset.toLong()).getDisplayName(TextStyle.NARROW, locale)
        }
    }

    /**
     * Builds the month grid as a list of 4..6 week rows, each containing 7 nullable day-of-month
     * ints (null = outside the month).
     */
    fun monthGrid(month: YearMonth, firstDayOfWeek: DayOfWeek): List<List<Int?>> {
        val firstOfMonth = month.atDay(1).dayOfWeek
        val leading = (firstOfMonth.value - firstDayOfWeek.value + 7) % 7
        val totalDays = month.lengthOfMonth()
        val cells = List(leading) { null } + (1..totalDays).toList()
        val rows = cells.chunked(7).map { row ->
            if (row.size == 7) row else row + List(7 - row.size) { null }
        }
        return rows
    }
}
