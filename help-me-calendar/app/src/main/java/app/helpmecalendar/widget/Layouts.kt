package app.helpmecalendar.widget

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.helpmecalendar.R
import app.helpmecalendar.data.CalendarEvent
import app.helpmecalendar.data.CalendarFmt
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.floor

private val PAD = 16.dp
private val GAP = 12.dp

/** System font families, resolved once: Google Sans Flex / Google Sans on Pixel, else Roboto. */
private object Fonts {
    private fun pick(vararg names: String): FontFamily =
        names.firstOrNull { Typeface.create(it, Typeface.NORMAL) != Typeface.DEFAULT }?.let { FontFamily(it) } ?: FontFamily.SansSerif
    val display: FontFamily by lazy { pick("google-sans-flex", "google-sans") }
    val text: FontFamily by lazy { pick("google-sans-flex", "google-sans-text", "google-sans") }
}

private val fontScale: Float get() = android.content.res.Resources.getSystem().configuration.fontScale.coerceAtLeast(0.5f)

private fun style(c: ColorProvider, size: Float, weight: FontWeight = FontWeight.Normal, display: Boolean = false, align: TextAlign? = null) =
    TextStyle(color = c, fontSize = (size * minOf(fontScale, 1f) / fontScale).sp, fontWeight = weight, fontFamily = if (display) Fonts.display else Fonts.text, textAlign = align)

/** Everything a layout needs, resolved once per composition. */
private class Scope(
    val ctx: Context,
    val s: WidgetState,
    val c: WColors,
    val width: Float,
    val height: Float,
) {
    val cfg get() = s.cfg
    val zone: ZoneId = ZoneId.systemDefault()
    val locale: Locale = Locale.getDefault()
    val today: LocalDate = Instant.ofEpochMilli(s.nowMs).atZone(zone).toLocalDate()
    val is24h: Boolean = CalendarFmt.is24h(ctx)
    val allDayLabel: String = ctx.getString(R.string.all_day)

    fun metaLine(e: CalendarEvent, compact: Boolean = false): String {
        val d = e.effectiveDate(today, zone)
        val dayPrefix = CalendarFmt.shortDayPrefix(ctx, d, today, locale)
        val time = if (compact) CalendarFmt.eventStart(e.allDay, e.startMs, is24h, allDayLabel, zone, locale)
        else CalendarFmt.eventTimeRange(e.allDay, e.startMs, e.endMs, is24h, allDayLabel, zone, locale)
        val loc = if (cfg.showLocation && !compact) e.location else null
        return listOfNotNull(dayPrefix.ifEmpty { null }, time, loc).joinToString(" · ")
    }

    /** Up to 3 distinct calendar colors for events on [d] (stable across the day for the current month). */
    fun dayColors(d: LocalDate): List<Int> =
        if (d.month == today.month && d.year == today.year) s.monthEventColors[d.dayOfMonth].orEmpty()
        else s.events.filter { it.effectiveDate(today, zone) == d }.map { it.color }.distinct().take(3)
}

@Composable
fun WidgetLayouts(s: WidgetState, c: WColors) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    if (!s.hasPermission) {
        Message(c, R.drawable.ms_calendar, ctx.getString(R.string.tap_to_allow_calendar))
        return
    }
    val x = Scope(ctx, s, c, size.width.value, size.height.value)
    // Breakpoints by grid span (Pixel cells ≈ 97dp wide × 105dp tall).
    when {
        x.height < 130 -> when {
            x.width < 150 -> Tiny(x)
            x.width < 270 -> Compact(x)
            else -> Strip(x)
        }
        x.width < 150 -> Narrow(x)
        x.height < 280 -> if (x.width < 270) Small(x) else Medium(x)
        else -> if (x.width < 270) Tall(x) else Large(x)
    }
}

// ---------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------

/** Up to 3 tiny calendar-color dots under or beside a date. */
@Composable
private fun ColorDots(colors: List<Int>, dotSize: Dp = 4.dp, gap: Dp = 3.dp) {
    if (colors.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        colors.take(3).forEachIndexed { i, argb ->
            if (i > 0) Spacer(GlanceModifier.width(gap))
            Box(
                modifier = GlanceModifier.size(dotSize).cornerRadius(dotSize / 2)
                    .background(ColorProvider(Color(argb))),
            ) {}
        }
    }
}

/** Fantastical / iOS-style event row: 3dp vertical calendar color pill + title + time. */
@Composable
private fun EventRow(x: Scope, e: CalendarEvent, compact: Boolean = false, modifier: GlanceModifier = GlanceModifier) {
    val barColor = ColorProvider(Color(e.color))
    var mod = modifier.fillMaxWidth()
    eventTap(x.ctx, x.s, e)?.let { mod = mod.clickable(it) }
    Row(modifier = mod, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = GlanceModifier.width(3.dp).height(if (compact) 30.dp else 36.dp)
                .cornerRadius(2.dp).background(barColor),
        ) {}
        Spacer(GlanceModifier.width(9.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(e.title, style = style(x.c.text, if (compact) 15f else 16f, FontWeight.Medium), maxLines = 1)
            Text(x.metaLine(e, compact), style = style(x.c.textVariant, if (compact) 12f else 13f), maxLines = 1)
        }
    }
}

@Composable
private fun EmptyHint(x: Scope, text: String = x.ctx.getString(R.string.no_events)) {
    Text(text, style = style(x.c.textVariant, 13f), maxLines = 2)
}

@Composable
private fun EventList(x: Scope, events: List<CalendarEvent>, maxItems: Int, compact: Boolean = false) {
    val shown = events.take(maxItems.coerceAtMost(8))
    if (shown.isEmpty()) {
        EmptyHint(x)
        return
    }
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        shown.forEachIndexed { i, e ->
            EventRow(x, e, compact = compact, modifier = GlanceModifier.padding(top = if (i == 0) 0.dp else if (compact) 6.dp else 8.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------
// Layouts by size
// ---------------------------------------------------------------------------------------

/** 1×1 — classic iOS calendar date tile with today's calendar color dots underneath. */
@Composable
private fun Tiny(x: Scope) {
    val dots = x.dayColors(x.today)
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(CalendarFmt.weekdayShort(x.today, x.locale), style = style(x.c.accent, 11f, FontWeight.Bold), maxLines = 1)
        Text(x.today.dayOfMonth.toString(), style = style(x.c.text, 34f, FontWeight.Medium, display = true), maxLines = 1)
        if (dots.isNotEmpty()) {
            Spacer(GlanceModifier.height(3.dp))
            ColorDots(dots, dotSize = 4.dp)
        }
    }
}

/** 2×1 / 3×1 — date badge left with event dots, next event right. */
@Composable
private fun Compact(x: Scope) {
    val dots = x.dayColors(x.today)
    Row(modifier = GlanceModifier.fillMaxSize().padding(horizontal = PAD, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = GlanceModifier.width(46.dp)) {
            Text(CalendarFmt.weekdayShort(x.today, x.locale), style = style(x.c.accent, 11f, FontWeight.Bold), maxLines = 1)
            Text(x.today.dayOfMonth.toString(), style = style(x.c.text, 28f, FontWeight.Medium, display = true), maxLines = 1)
            if (dots.isNotEmpty()) {
                Spacer(GlanceModifier.height(2.dp))
                ColorDots(dots, dotSize = 3.5.dp, gap = 2.dp)
            }
        }
        Spacer(GlanceModifier.width(GAP))
        Column(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            val next = x.s.events.firstOrNull()
            if (next != null) EventRow(x, next, compact = true) else EmptyHint(x)
        }
    }
}

/** 4×1 / 5×1 — date + dots left, next 2 events right. */
@Composable
private fun Strip(x: Scope) {
    val dots = x.dayColors(x.today)
    Row(modifier = GlanceModifier.fillMaxSize().padding(horizontal = PAD, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.Start, modifier = GlanceModifier.width(68.dp)) {
            Text(CalendarFmt.weekdayShort(x.today, x.locale), style = style(x.c.accent, 11f, FontWeight.Bold), maxLines = 1)
            Text(x.today.dayOfMonth.toString(), style = style(x.c.text, 34f, FontWeight.Normal, display = true), maxLines = 1)
            if (dots.isNotEmpty()) {
                Spacer(GlanceModifier.height(2.dp))
                ColorDots(dots, dotSize = 4.dp)
            }
        }
        Spacer(GlanceModifier.width(14.dp))
        Column(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            EventList(x, x.s.events, maxItems = 2, compact = true)
        }
    }
}

/** 1 column × 2+ rows — weekday, date, and up to 2–3 compact events. */
@Composable
private fun Narrow(x: Scope) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
        Text(CalendarFmt.weekdayShort(x.today, x.locale), style = style(x.c.accent, 11f, FontWeight.Bold), maxLines = 1)
        Text(x.today.dayOfMonth.toString(), style = style(x.c.text, 34f, FontWeight.Normal, display = true), maxLines = 1)
        Spacer(GlanceModifier.height(8.dp))
        EventList(x, x.s.events, maxItems = if (x.height >= 220) 3 else 2, compact = true)
    }
}

/** 2×2 — Fantastical / iOS small: bold date anchor + next 2–3 events. */
@Composable
private fun Small(x: Scope) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(PAD)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(CalendarFmt.weekdayFull(x.today, x.locale), style = style(x.c.accent, 11f, FontWeight.Bold), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            ColorDots(x.dayColors(x.today), dotSize = 4.dp)
        }
        Text(x.today.dayOfMonth.toString(), style = style(x.c.text, 36f, FontWeight.Normal, display = true), maxLines = 1)
        Spacer(GlanceModifier.height(8.dp))
        val rows = floor((x.height - 32f - 66f) / 42f).toInt().coerceIn(1, 3)
        EventList(x, x.s.events, maxItems = rows, compact = true)
    }
}

/** 2×3+ — date header + taller agenda list. */
@Composable
private fun Tall(x: Scope) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(PAD)) {
        Text(CalendarFmt.weekdayFull(x.today, x.locale), style = style(x.c.accent, 12f, FontWeight.Bold), maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(x.today.dayOfMonth.toString(), style = style(x.c.text, 44f, FontWeight.Normal, display = true), maxLines = 1)
            Spacer(GlanceModifier.width(8.dp))
            Text(CalendarFmt.monthName(x.today, x.locale), style = style(x.c.textVariant, 14f), maxLines = 1, modifier = GlanceModifier.padding(bottom = 8.dp))
        }
        Spacer(GlanceModifier.height(10.dp))
        val rows = floor((x.height - 32f - 88f) / 48f).toInt().coerceIn(2, 6)
        EventList(x, x.s.events, maxItems = rows, compact = false)
    }
}

/**
 * 4×2 — big date anchor on the left with an optional 3-day mini strip (and calendar-color event
 * dots neatly centered under each upcoming day), paired with top-aligned upcoming events on the right.
 */
@Composable
private fun Medium(x: Scope) {
    val innerW = x.width - 32f
    val leftW = (innerW * 0.34f).coerceIn(108f, 134f)
    val rows = floor((x.height - 32f) / 46f).toInt().coerceIn(2, 4)
    Row(modifier = GlanceModifier.fillMaxSize().padding(PAD)) {
        Column(modifier = GlanceModifier.width(leftW.dp).fillMaxHeight()) {
            Text(CalendarFmt.weekdayFull(x.today, x.locale), style = style(x.c.accent, 11f, FontWeight.Bold), maxLines = 1)
            Text(x.today.dayOfMonth.toString(), style = style(x.c.text, 48f, FontWeight.Normal, display = true), maxLines = 1)
            Text(CalendarFmt.monthName(x.today, x.locale), style = style(x.c.textVariant, 13f), maxLines = 1)
            if (x.cfg.showUpcomingDays) {
                Spacer(GlanceModifier.defaultWeight())
                // Clean 3-day upcoming mini strip with centered calendar-color dots underneath.
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    for (offset in 1..3) {
                        val d = x.today.plusDays(offset.toLong())
                        val colors = x.dayColors(d)
                        Column(
                            modifier = GlanceModifier.defaultWeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, x.locale).uppercase(x.locale),
                                style = style(x.c.textVariant, 10f, FontWeight.Medium, align = TextAlign.Center),
                                maxLines = 1,
                            )
                            Text(
                                d.dayOfMonth.toString(),
                                style = style(
                                    if (colors.isNotEmpty()) x.c.text else x.c.textVariant,
                                    13f,
                                    if (colors.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                                    align = TextAlign.Center,
                                ),
                                maxLines = 1,
                            )
                            Spacer(GlanceModifier.height(3.dp))
                            if (colors.isNotEmpty()) {
                                ColorDots(colors, dotSize = 3.5.dp, gap = 2.dp)
                            } else {
                                Spacer(GlanceModifier.height(3.5.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(GlanceModifier.width(14.dp))
        Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.Top) {
            EventList(x, x.s.events, maxItems = rows, compact = rows >= 4 && x.height < 220f)
        }
    }
}

/** 4×3+ — Month header + mini 7-column month grid with calendar-color dots + upcoming agenda below. */
@Composable
private fun Large(x: Scope) {
    val month = YearMonth.from(x.today)
    val firstDay = WeekFields.of(x.locale).firstDayOfWeek
    val weeks = CalendarFmt.monthGrid(month, firstDay)
    val headers = CalendarFmt.weekHeaders(x.locale)
    val sixWeeks = weeks.size >= 6
    val gridRowH = if (sixWeeks) 22f else 25f
    val dayBoxSize = if (sixWeeks) 18.dp else 20.dp
    val gridH = 16f + weeks.size * gridRowH
    val availForAgenda = x.height - 32f - 26f - gridH - 16f
    val agendaRows = floor(availForAgenda / 44f).toInt().coerceIn(1, 5)

    Column(modifier = GlanceModifier.fillMaxSize().padding(PAD)) {
        // Header: Month Year left, today weekday + day right.
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(CalendarFmt.monthYear(month, x.locale), style = style(x.c.text, 16f, FontWeight.Medium, display = true), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Text("${CalendarFmt.weekdayShort(x.today, x.locale)} ${x.today.dayOfMonth}", style = style(x.c.accent, 13f, FontWeight.Bold), maxLines = 1)
        }
        Spacer(GlanceModifier.height(6.dp))
        // Mini month grid in its own Column (1 header + up to 6 week rows = max 7 children).
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Row(modifier = GlanceModifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                headers.forEach { h ->
                    Text(h, style = style(x.c.textVariant, 11f, FontWeight.Medium, align = TextAlign.Center), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                }
            }
            weeks.forEach { week ->
                Row(modifier = GlanceModifier.fillMaxWidth().height(gridRowH.dp), verticalAlignment = Alignment.CenterVertically) {
                    week.forEach { day ->
                        val isToday = day == x.today.dayOfMonth
                        val colors = if (day != null) x.s.monthEventColors[day].orEmpty() else emptyList()
                        Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (day != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = if (isToday) GlanceModifier.size(dayBoxSize).cornerRadius(dayBoxSize / 2).background(x.c.accent)
                                        else GlanceModifier.size(dayBoxSize),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            day.toString(),
                                            style = style(
                                                if (isToday) x.c.bg else x.c.text,
                                                11f,
                                                if (isToday || colors.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                                align = TextAlign.Center,
                                            ),
                                            maxLines = 1,
                                        )
                                    }
                                    if (colors.isNotEmpty()) {
                                        ColorDots(colors, dotSize = 3.dp, gap = 1.5.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        EventList(x, x.s.events, maxItems = agendaRows, compact = agendaRows >= 3 || sixWeeks)
    }
}

// ---------------------------------------------------------------------------------------

@Composable
private fun Message(c: WColors, icon: Int, text: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(PAD),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(icon), null, GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(c.accent))
        Spacer(GlanceModifier.height(8.dp))
        Text(text, style = style(c.text, 14f, FontWeight.Medium, align = TextAlign.Center), maxLines = 2)
    }
}
