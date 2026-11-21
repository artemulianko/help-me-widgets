package app.helpmecalendar.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

data class DeviceCalendar(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    val visible: Boolean,
)

data class CalendarEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String? = null,
) {
    /** Calendar date this event belongs to (all-day instances are stored at UTC midnight). */
    fun localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        if (allDay) Instant.ofEpochMilli(startMs).atZone(ZoneOffset.UTC).toLocalDate()
        else Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
}

/**
 * Reads calendars and upcoming event instances directly from Android's local CalendarContract.
 * No internet, no accounts, no caching needed.
 */
object Calendars {
    private const val DEFAULT_COLOR = 0xFF4285F4.toInt()

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** All calendars synced on the device, sorted by account and display name. */
    fun list(ctx: Context): List<DeviceCalendar> {
        if (!hasPermission(ctx)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
        )
        return runCatching {
            val out = mutableListOf<DeviceCalendar>()
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += DeviceCalendar(
                        id = c.getLong(0),
                        name = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Calendar",
                        account = c.getString(2).orEmpty(),
                        color = if (c.isNull(3)) DEFAULT_COLOR else (c.getInt(3) or 0xFF000000.toInt()),
                        visible = c.getInt(4) != 0,
                    )
                }
            }
            out
        }.onFailure { AppLog.w(ctx, "Calendar", "Couldn't read calendars", it) }.getOrDefault(emptyList())
    }

    /**
     * Upcoming event instances from now through [daysAhead] days ahead.
     * [calendarIds] == null uses all visible calendars; an empty set returns no events.
     */
    fun upcoming(
        ctx: Context,
        calendarIds: Set<Long>?,
        daysAhead: Int,
        includeAllDay: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CalendarEvent> {
        if (!hasPermission(ctx)) return emptyList()
        if (calendarIds != null && calendarIds.isEmpty()) return emptyList()

        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        // Query from start of yesterday UTC so all-day events in positive UTC offsets aren't missed.
        val queryStart = today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endDate = today.plusDays(daysAhead.coerceIn(1, 31).toLong())
        val queryEnd = endDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val raw = queryInstances(ctx, queryStart, queryEnd, calendarIds, includeAllDay)

        return raw
            .filter { e ->
                if (e.allDay) {
                    val d = e.localDate(zone)
                    val endDay = Instant.ofEpochMilli(e.endMs).atZone(ZoneOffset.UTC).toLocalDate()
                    !endDay.isBefore(today.plusDays(1)) && d.isBefore(endDate)
                } else {
                    e.endMs > nowMs && e.startMs < queryEnd
                }
            }
            .sortedWith(
                compareBy<CalendarEvent> { it.localDate(zone) }
                    .thenBy { !it.allDay }
                    .thenBy { it.startMs }
                    .thenBy { it.title },
            )
            .distinctBy { Triple(it.id, it.startMs, it.allDay) }
    }

    /** Map of day-of-month (1..31) in [month] to up to 3 distinct calendar colors for events on that day. */
    fun monthEventColors(
        ctx: Context,
        calendarIds: Set<Long>?,
        month: YearMonth,
        includeAllDay: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<Int, List<Int>> {
        if (!hasPermission(ctx)) return emptyMap()
        if (calendarIds != null && calendarIds.isEmpty()) return emptyMap()

        val firstDay = month.atDay(1)
        val nextMonth = month.plusMonths(1).atDay(1)
        val queryStart = firstDay.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val queryEnd = nextMonth.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val raw = queryInstances(ctx, queryStart, queryEnd, calendarIds, includeAllDay)

        val map = mutableMapOf<Int, LinkedHashSet<Int>>()
        fun add(day: Int, color: Int) {
            val set = map.getOrPut(day) { LinkedHashSet() }
            if (set.size < 3) set += color
        }
        for (e in raw) {
            if (e.allDay) {
                val startD = Instant.ofEpochMilli(e.startMs).atZone(ZoneOffset.UTC).toLocalDate()
                val endD = Instant.ofEpochMilli(e.endMs).atZone(ZoneOffset.UTC).toLocalDate()
                var d = if (startD.isBefore(firstDay)) firstDay else startD
                while (d.isBefore(endD) && d.isBefore(nextMonth)) {
                    if (d.year == month.year && d.month == month.month) add(d.dayOfMonth, e.color)
                    d = d.plusDays(1)
                }
            } else {
                val d = e.localDate(zone)
                if (d.year == month.year && d.month == month.month) add(d.dayOfMonth, e.color)
            }
        }
        return map.mapValues { it.value.toList() }
    }

    private fun queryInstances(
        ctx: Context,
        startMs: Long,
        endMs: Long,
        calendarIds: Set<Long>?,
        includeAllDay: Boolean,
    ): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, startMs)
            ContentUris.appendId(this, endMs)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_COLOR,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.VISIBLE,
        )
        return runCatching {
            val out = mutableListOf<CalendarEvent>()
            ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val calId = c.getLong(1)
                    val visible = c.getInt(10) != 0
                    if (calendarIds != null) {
                        if (calId !in calendarIds) continue
                    } else if (!visible) {
                        continue
                    }
                    val allDay = c.getInt(5) != 0
                    if (allDay && !includeAllDay) continue
                    val status = c.getInt(9)
                    if (status == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val rawColor = when {
                        !c.isNull(6) -> c.getInt(6)
                        !c.isNull(7) -> c.getInt(7)
                        else -> DEFAULT_COLOR
                    }
                    out += CalendarEvent(
                        id = c.getLong(0),
                        calendarId = calId,
                        title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "(No title)",
                        startMs = c.getLong(3),
                        endMs = c.getLong(4),
                        allDay = allDay,
                        color = rawColor or 0xFF000000.toInt(),
                        location = c.getString(8)?.takeIf { it.isNotBlank() },
                    )
                }
            }
            out
        }.onFailure { AppLog.w(ctx, "Calendar", "Couldn't read events", it) }.getOrDefault(emptyList())
    }

    /** Opens the system calendar app at [timeMs]. */
    fun openCalendarIntent(timeMs: Long = System.currentTimeMillis()): Intent {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(timeMs.toString())
            .build()
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens a specific event in the system calendar app. */
    fun openEventIntent(event: CalendarEvent): Intent {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id)
        return Intent(Intent.ACTION_VIEW, uri)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMs)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
