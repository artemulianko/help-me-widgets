package app.helpmeclock.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import app.helpmeclock.R
import app.helpmeclock.data.DateStyle
import app.helpmeclock.data.TapAction
import app.helpmeclock.data.Weight
import app.helpmeclock.data.alarmLabel
import app.helpmeclock.data.stripAmPm
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// Spacing from Android's canonical widget layouts.
private const val PAD = 16f          // widget edge → content
private const val PAD_TINY = 8f      // 1×1, same as Weather and Stocks: every dp goes to the digits
private const val GAP = 4f
private const val LABEL = 14f        // header / secondary line, sp
private const val LABEL_LARGE = 16f  // date and alarm on larger sizes, sp
private const val AMPM_RATIO = 0.28f // AM/PM size relative to the numerals
private const val BIG_DATE_MAX = 24f // date-led: a step above At a Glance, not a poster
private const val ALARM_BESIDE_DATE = 16f // sp: answers a 24 dp date without competing
private const val SOLO = 0.72f       // time alone: a calmer size than "fill the widget"

/**
 * Font metrics for fitting text to the cell. TextClock can't auto-size from a RemoteViews
 * (setting a size turns auto-size off), so we measure the widest possible text once per render
 * and pick the largest size that fits.
 */
private object Metrics {
    private val base: Typeface by lazy { Typeface.create("google-sans-flex", Typeface.NORMAL) }
    private fun paint(weight: Int, tnum: Boolean, spacing: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(base, weight, false)
        textSize = 100f
        letterSpacing = spacing
        if (tnum) fontFeatureSettings = "tnum"
    }

    class Unit(val width: Float, val height: Float, val descent: Float)

    // Cached: a render asks for the same measurements many times (every label checks the
    // widest weekday/month), and uncached that work starved the main thread into an ANR.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Unit>()
    private val worstCache = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /** Width, line height and descent of [text] at a text size of 1. */
    fun measure(text: String, weight: Int, tnum: Boolean = true, spacing: Float = -0.02f): Unit =
        cache.getOrPut("$text|$weight|$tnum|$spacing") {
            val p = paint(weight, tnum, spacing)
            val fm = p.fontMetrics
            Unit(p.measureText(text) / 100f, (fm.descent - fm.ascent) / 100f, fm.descent / 100f)
        }

    /** Cached [compute] of the widest text a date pattern can produce in [locale]. */
    fun worst(locale: Locale, pattern: String, weight: Int, spacing: Float, compute: () -> Float): Float =
        worstCache.getOrPut("$locale|$pattern|$weight|$spacing", compute)
}

/** Everything a layout needs, resolved once per composition. */
private class Scope(val ctx: Context, val s: WidgetState, val c: WColors, val width: Float, val height: Float) {
    val cfg get() = s.cfg
    val locale: Locale = Locale.getDefault()
    val is24 = DateFormat.is24HourFormat(ctx)
    // Labels follow the font-size setting, but capped so the numerals keep most of the space.
    // Capped at 1.15×: beyond that the labels outgrew the time at the largest font sizes.
    // The Text size setting scales labels here and the big numerals and dates in the fit functions.
    val textScale get() = cfg.textSize.factor
    val fontScale = min(ctx.resources.configuration.fontScale, 1.15f) * s.cfg.textSize.factor
    /** Smaller shrinks what would fill the space; Larger only raises caps (it can't exceed a fit). */
    private val shrink get() = min(textScale, 1f)
    fun pattern(skeleton: String): String = DateFormat.getBestDateTimePattern(locale, skeleton)

    val time12 = stripAmPm(pattern("hm"))
    val time24 = pattern("Hm")
    val timeSample = (if (is24) time24 else time12).replace(Regex("[hHkKm]+"), "00")

    val showDate get() = cfg.showDate
    val alarm = s.alarm?.takeIf { cfg.showAlarm }
    val alarmText = alarm?.let { alarmLabel(it.triggerAt, s.now, ZoneId.systemDefault(), locale, is24) }

    private val label1 = Metrics.measure("Ag", 400, tnum = false, spacing = 0f)
    /** Height in dp of one label line at [sp]. */
    fun labelHeight(sp: Float) = label1.height * sp * fontScale
    fun labelWidth(text: String, sp: Float) = Metrics.measure(text, 400, tnum = true, spacing = 0f).width * sp * fontScale

    // ---- dates ----

    /** Day before or after month, as in the chosen style. */
    private val dayFirst get() = cfg.dateStyle != DateStyle.MONTH_DAY
    /** Month and day without the weekday, in the chosen order. */
    fun dayMonth(longMonth: Boolean) = when {
        dayFirst && longMonth -> "d MMMM"
        dayFirst -> "d MMM"
        longMonth -> "MMMM d"
        else -> "MMM d"
    }

    /**
     * Widest text a date pattern can produce: TextClock rolls the date over at midnight without
     * a re-render, so a label must fit every weekday and month, not just today's.
     */
    fun worst(pattern: String, weight: Int = 400, spacing: Float = 0f): Float = Metrics.worst(locale, pattern, weight, spacing) {
        val fmt = java.text.SimpleDateFormat(pattern, locale)
        val cal = java.util.Calendar.getInstance()
        (0 until 12).flatMap { m -> (0 until 7).map { d -> cal.apply { set(2026, m, 22 + d) }.time } }
            .map { fmt.format(it) }.distinct()
            .maxOf { Metrics.measure(it, weight, tnum = true, spacing = spacing).width }
    }
    fun dateWidth(pattern: String, sp: Float) = worst(pattern) * sp * fontScale + 2f

    /** The chosen date style, or a shorter one when it can't fit [maxW] at [sp]. Never truncated. */
    fun dateLine(maxW: Float, sp: Float): String =
        listOf(cfg.dateStyle.pattern, "EEE, " + dayMonth(false), "EEE d", "d")
            .distinct().firstOrNull { dateWidth(it, sp) <= maxW } ?: "d"

    fun alarmWidth(sp: Float) = alarmText?.let { sp * fontScale + 4f + labelWidth(it, sp) + 2f } ?: 0f

    // ---- RemoteViews builders (TextClock isn't available as a Glance composable) ----

    private fun bigLayout() = when (cfg.weight) {
        Weight.LIGHT -> R.layout.clock_time_300
        Weight.REGULAR -> R.layout.clock_time_400
        Weight.MEDIUM -> R.layout.clock_time_500
    }

    fun clockRv(layout: Int, fmt12: String, fmt24: String, size: Float, unit: Int, color: WColor) =
        RemoteViews(ctx.packageName, layout).apply {
            setCharSequence(R.id.clock, "setFormat12Hour", fmt12)
            setCharSequence(R.id.clock, "setFormat24Hour", fmt24)
            setTextViewTextSize(R.id.clock, unit, size)
            color.apply(this, R.id.clock, "setTextColor")
        }

    private fun timeAmPmLayout() = when (cfg.weight) {
        Weight.LIGHT -> R.layout.clock_time_ampm_300
        Weight.REGULAR -> R.layout.clock_time_ampm_400
        Weight.MEDIUM -> R.layout.clock_time_ampm_500
    }

    /** Time digits at [sizeDp] with AM/PM at [AMPM_RATIO] of that, in the quieter tone. */
    fun timeWithAmPm(sizeDp: Float) = RemoteViews(ctx.packageName, timeAmPmLayout()).apply {
        setCharSequence(R.id.clock, "setFormat12Hour", time12)
        setCharSequence(R.id.clock, "setFormat24Hour", time24)
        setTextViewTextSize(R.id.clock, TypedValue.COMPLEX_UNIT_DIP, sizeDp)
        c.text.apply(this, R.id.clock, "setTextColor")
        setTextViewTextSize(R.id.ampm, TypedValue.COMPLEX_UNIT_DIP, sizeDp * AMPM_RATIO)
        c.textVariant.apply(this, R.id.ampm, "setTextColor")
    }

    fun big(fmt12: String, fmt24: String, sizeDp: Float, color: WColor = c.text) =
        clockRv(bigLayout(), fmt12, fmt24, sizeDp, TypedValue.COMPLEX_UNIT_DIP, color)

    // Labels are drawn in dp at exactly the size they were measured at (font scale applied,
    // capped at 1.3×). Drawn in sp, a 2× font setting outgrew its measured box and truncated.
    fun dateRv(pattern: String, sp: Float, color: WColor) =
        clockRv(R.layout.clock_text, pattern, pattern, sp * fontScale, TypedValue.COMPLEX_UNIT_DIP, color)

    fun alarmRv(sp: Float): RemoteViews? {
        val a = alarm ?: return null
        val label = alarmText ?: return null
        return RemoteViews(ctx.packageName, R.layout.alarm_label).apply {
            setTextViewText(R.id.alarm_text, label)
            setTextViewTextSize(R.id.alarm_text, TypedValue.COMPLEX_UNIT_DIP, sp * fontScale)
            // Same color as the date: gray read as "disabled". Only the icon stays quieter.
            c.text.apply(this, R.id.alarm_text, "setTextColor")
            c.textVariant.apply(this, R.id.alarm_icon, "setImageTintList")
            val icon = sp * fontScale
            setViewLayoutWidth(R.id.alarm_icon, icon, TypedValue.COMPLEX_UNIT_DIP)
            setViewLayoutHeight(R.id.alarm_icon, icon, TypedValue.COMPLEX_UNIT_DIP)
            setContentDescription(R.id.alarm, ctx.getString(R.string.next_alarm, label))
            if (cfg.tapAction != TapAction.NONE) {
                // The clock app's own intent opens exactly this alarm; else its alarm list.
                val pi = a.showIntent ?: PendingIntent.getActivity(
                    ctx, 0, Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
                )
                setOnClickPendingIntent(R.id.alarm, pi)
            }
        }
    }

    // ---- fitting ----

    private val timeUnit get() = Metrics.measure(timeSample, cfg.weight.value)
    private val ampmUnit get() = Metrics.measure(" PM", 400, tnum = false, spacing = 0f)

    /** Largest numeral size (dp) whose time (+ AM/PM in 12-hour mode) fits [maxW] × [maxH]. */
    fun fitTime(maxW: Float, maxH: Float): Float {
        val t = timeUnit
        val perUnitW = t.width + if (is24) 0f else AMPM_RATIO * ampmUnit.width
        val fixedW = if (is24) 0f else GAP
        return max(8f, min((maxW - fixedW) * 0.97f / perUnitW, maxH / t.height) * shrink)
    }

    /** Largest size (dp) for one line of [sample] digits. */
    fun fitDigits(sample: String, maxW: Float, maxH: Float): Float {
        val t = Metrics.measure(sample, cfg.weight.value)
        return max(8f, min(maxW * 0.97f / t.width, maxH / t.height) * shrink)
    }

    /** Largest size (dp) at which every date the pattern can show fits. */
    fun fitDate(pattern: String, maxW: Float, maxH: Float): Float =
        max(8f, min(maxW * 0.97f / worst(pattern, cfg.weight.value, -0.02f), maxH / timeUnit.height) * shrink)

    fun bigLineHeight(size: Float): Float = timeUnit.height * size
    /** Empty space under digits (they have no descenders) at [size]. */
    fun digitDescent(size: Float): Float = timeUnit.descent * size
    fun timeWidth(size: Float): Float = timeUnit.width * size + 2f
    fun ampmWidth(size: Float): Float = ampmUnit.width * size + 2f
    fun digitsWidth(sample: String, size: Float): Float = Metrics.measure(sample, cfg.weight.value).width * size + 2f
    fun bigDateWidth(pattern: String, size: Float): Float = worst(pattern, cfg.weight.value, -0.02f) * size + 2f

}

@Composable
fun WidgetLayouts(s: WidgetState, c: WColors) {
    val size = LocalSize.current
    val sc = Scope(LocalContext.current, s, c, size.width.value, size.height.value)
    val oneRow = isOneRow(sc.width, sc.height)
    // Time off + date on: the date takes the lead (for when the status bar already shows the
    // time). Both off can't be saved, but if it ever happens the time comes back.
    if (!sc.cfg.showTime && sc.showDate) return when {
        sc.width < 150f -> DateStacked(sc)
        oneRow && sc.width < 300f -> DateCompact(sc)
        oneRow -> DateWide(sc)
        sc.width < 300f -> DateSquare(sc)
        else -> DateLarge(sc)
    }
    when {
        sc.width < 150f -> Stacked(sc)
        oneRow && sc.width < 300f -> Compact(sc)
        oneRow -> Wide(sc)
        sc.width < 300f -> Square(sc)
        else -> Large(sc)
    }
}

/**
 * One-row layouts by shape, not just height: launcher grids differ (4×4 rows are taller than
 * 5×5 ones), so a 4×1 can be ~140 dp tall. Short, or clearly wider than tall and not yet two
 * rows high, is a strip.
 */
internal fun isOneRow(w: Float, h: Float) = h < 130f || (h < 180f && w / h >= 1.25f)

// Every live view gets an explicit size. Glance measures a wrap_content AndroidRemoteViews by
// its narrowest sibling (labels were clipped) or as zero next to a weighted Spacer (the alarm
// vanished), so sizes are measured here and parts are placed with aligned Box overlays.

/** A date label sized for its widest weekday/month, aligned start or end within that size. */
@Composable
private fun DateLabel(sc: Scope, pattern: String, sp: Float, color: WColor, end: Boolean = false) {
    Box(
        GlanceModifier.width(sc.dateWidth(pattern, sp).dp).height(sc.labelHeight(sp).dp),
        contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart,
    ) { AndroidRemoteViews(sc.dateRv(pattern, sp, color)) }
}

@Composable
private fun Alarm(sc: Scope, sp: Float = LABEL) {
    val rv = sc.alarmRv(sp) ?: return
    Box(GlanceModifier.width(sc.alarmWidth(sp).dp).height(max(sc.labelHeight(sp), sp * sc.fontScale).dp), contentAlignment = Alignment.CenterStart) {
        AndroidRemoteViews(rv)
    }
}

/** A big live text (time digits, date, day number) at [size] dp, with its exact line height. */
@Composable
private fun Big(sc: Scope, fmt12: String, fmt24: String, size: Float, width: Float, color: WColor = sc.c.text) {
    Box(GlanceModifier.width(width.dp).height(sc.bigLineHeight(size).dp), contentAlignment = Alignment.BottomStart) {
        AndroidRemoteViews(sc.big(fmt12, fmt24, size, color))
    }
}

/** A big date line in the chosen style, fitted to [maxW] × [maxH], at most [cap] dp. */
@Composable
private fun BigDate(sc: Scope, maxW: Float, maxH: Float, cap: Float = Float.MAX_VALUE) {
    // Prefer the chosen style; drop to a shorter one only if it would end up label-sized.
    val p = listOf(sc.cfg.dateStyle.pattern, "EEE, " + sc.dayMonth(false), "EEE d")
        .firstOrNull { sc.fitDate(it, maxW, maxH) >= LABEL_LARGE * 1.25f } ?: "EEE d"
    val size = min(cap * sc.textScale, sc.fitDate(p, maxW, maxH))
    Big(sc, p, p, size, sc.bigDateWidth(p, size))
}

/** A full-width line with [start] content at the start and [end] content at the end. */
@Composable
private fun SplitLine(height: Float, start: @Composable () -> Unit, end: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxWidth().height(height.dp)) {
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { start() }
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) { end() }
    }
}

/**
 * The time with a small AM/PM after it in 12-hour mode: one native row, so AM/PM hugs the
 * digits ("1:00 AM" has no gap) and sits on their baseline. The box is sized for the widest
 * time so the layout never shifts as the minutes tick.
 */
@Composable
private fun TimeRow(sc: Scope, maxW: Float, maxH: Float) {
    val size = sc.fitTime(maxW, maxH)
    val w = sc.timeWidth(size) + if (sc.is24) 0f else GAP + sc.ampmWidth(size * AMPM_RATIO)
    Box(GlanceModifier.width(w.dp).height(sc.bigLineHeight(size).dp), contentAlignment = Alignment.BottomStart) {
        AndroidRemoteViews(sc.timeWithAmPm(size))
    }
}

/** The time alone: centered, at a calmer size than the widget could hold. */
@Composable
private fun CenteredTime(sc: Scope, pad: Float = PAD) {
    Box(GlanceModifier.fillMaxSize().padding(pad.dp), contentAlignment = Alignment.Center) {
        TimeRow(sc, (sc.width - 2 * pad) * SOLO, (sc.height - 2 * pad) * SOLO)
    }
}

/** 1×1 and 1-column: hours over minutes, minutes in the quieter tone (One UI style). */
@Composable
private fun Stacked(sc: Scope) {
    val pad = if (sc.width < 120f) PAD_TINY else PAD
    val maxW = sc.width - 2 * pad
    val maxH = (sc.height - 2 * pad) / 2f
    val hours12 = stripAmPm(sc.pattern("h"))
    val hours24 = sc.pattern("H").let { if (it.length == 1) "HH" else it }
    val size = sc.fitDigits("00", maxW, min(maxH, maxW))
    val w = sc.digitsWidth("00", size)
    Box(GlanceModifier.fillMaxSize().padding(pad.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Big(sc, hours12, hours24, size, w)
            Big(sc, "mm", "mm", size, w, sc.c.textVariant)
        }
    }
}

/** 2×1: a quiet header (date · alarm) over a big time. */
@Composable
private fun Compact(sc: Scope) {
    if (!sc.showDate && sc.alarm == null) return CenteredTime(sc)
    val inner = sc.width - 2 * PAD
    // Narrow 2×1s (5-column grids): rather than cut the date to "18", step both labels down.
    val sp = if (!sc.showDate || sc.dateWidth("EEE d", LABEL) + sc.alarmWidth(LABEL) + 8f <= inner) LABEL else LABEL - 2f
    val header = max(sc.labelHeight(sp), sp * sc.fontScale)
    val dateMax = inner - sc.alarmWidth(sp) - 8f
    Box(GlanceModifier.fillMaxSize().padding(PAD.dp)) {
        SplitLine(header, { if (sc.showDate) DateLabel(sc, sc.dateLine(dateMax, sp), sp, sc.c.textVariant) }, { Alarm(sc, sp) })
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            TimeRow(sc, sc.width - 2 * PAD, sc.height - 2 * PAD - header - GAP)
        }
    }
}

/**
 * 4×1 (the default) and landscape strips, the At a Glance replacement: the date over the time
 * at the start, the next alarm at the end.
 */
@Composable
private fun Wide(sc: Scope) {
    if (!sc.showDate && sc.alarm == null) return CenteredTime(sc)
    val vPad = 12f
    val alarmW = sc.alarmWidth(LABEL_LARGE)
    val leftW = sc.width - 2 * PAD - (if (alarmW > 0f) alarmW + 24f else 0f)
    val dateH = if (sc.showDate) sc.labelHeight(LABEL_LARGE) + GAP else 0f
    // Capped: the time shouldn't swallow the row; date and alarm share the stage.
    val timeW = min(leftW, (sc.width - 2 * PAD) * 0.6f)
    val timeH = sc.height - 2 * vPad - dateH
    // Digits leave their descent empty, so the block looks high next to the centered alarm.
    // A top inset of half that space moves the block's center down by a quarter of it, which
    // measured right on renders: both sides on one optical center line.
    val nudge = if (sc.showDate) sc.digitDescent(sc.fitTime(timeW, timeH)) / 2f else 0f
    Box(GlanceModifier.fillMaxSize().padding(horizontal = PAD.dp, vertical = vPad.dp)) {
        Box(GlanceModifier.fillMaxSize().padding(top = nudge.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                if (sc.showDate) {
                    DateLabel(sc, sc.dateLine(leftW, LABEL_LARGE), LABEL_LARGE, sc.c.text)
                    Spacer(GlanceModifier.height(GAP.dp))
                }
                TimeRow(sc, timeW, timeH)
            }
        }
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) { Alarm(sc, LABEL_LARGE) }
    }
}

/** 2×2: weekday and date on top, a large time, the alarm under it. */
@Composable
private fun Square(sc: Scope) {
    val dateH = if (sc.showDate) sc.labelHeight(LABEL_LARGE) + GAP + sc.labelHeight(LABEL) else 0f
    // The alarm line keeps its space when no alarm is set, so the time never jumps.
    val alarmH = if (sc.cfg.showAlarm) sc.labelHeight(LABEL) + 8f else 0f
    if (dateH == 0f && alarmH == 0f) return CenteredTime(sc)
    val longMonth = sc.dateWidth(sc.dayMonth(true), LABEL) <= sc.width - 2 * PAD
    Box(GlanceModifier.fillMaxSize().padding(PAD.dp)) {
        if (sc.showDate) {
            Column {
                DateLabel(sc, "EEEE", LABEL_LARGE, sc.c.text)
                Spacer(GlanceModifier.height(GAP.dp))
                DateLabel(sc, sc.dayMonth(longMonth), LABEL, sc.c.textVariant)
            }
        }
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Column {
                TimeRow(sc, sc.width - 2 * PAD, sc.height - 2 * PAD - dateH - alarmH - 2 * GAP)
                if (sc.cfg.showAlarm) {
                    Box(GlanceModifier.fillMaxWidth().height(alarmH.dp), contentAlignment = Alignment.BottomStart) { Alarm(sc) }
                }
            }
        }
    }
}

/** 4×2 and up: one header line (date · alarm) and a very large time. */
@Composable
private fun Large(sc: Scope) {
    if (!sc.showDate && sc.alarm == null) return CenteredTime(sc)
    val header = max(sc.labelHeight(LABEL_LARGE), LABEL_LARGE * sc.fontScale)
    val dateMax = sc.width - 2 * PAD - sc.alarmWidth(LABEL_LARGE) - 16f
    Box(GlanceModifier.fillMaxSize().padding(PAD.dp)) {
        SplitLine(header, { if (sc.showDate) DateLabel(sc, sc.dateLine(dateMax, LABEL_LARGE), LABEL_LARGE, sc.c.text) }, { Alarm(sc, LABEL_LARGE) })
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            TimeRow(sc, sc.width - 2 * PAD, sc.height - 2 * PAD - header - GAP)
        }
    }
}

// ---- time off, date on: the date leads, the alarm stays beside it ---------------------------

/** 1×1: weekday over a big day number, like a calendar icon. */
@Composable
private fun DateStacked(sc: Scope) {
    val pad = if (sc.width < 120f) PAD_TINY else PAD
    val labelH = sc.labelHeight(LABEL)
    val alarmH = if (sc.alarm != null && sc.height >= 130f) sc.labelHeight(LABEL) + 8f else 0f
    val maxW = sc.width - 2 * pad
    val size = sc.fitDigits("00", maxW, sc.height - 2 * pad - labelH - alarmH)
    Box(GlanceModifier.fillMaxSize().padding(pad.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DateLabel(sc, "EEE", LABEL, sc.c.textVariant)
            val d = sc.pattern("d")
            Big(sc, d, d, size, sc.digitsWidth("00", size))
            if (alarmH > 0f) {
                Spacer(GlanceModifier.height(8.dp))
                Alarm(sc)
            }
        }
    }
}

/** 2×1: the date large, the alarm on the line under it. */
@Composable
private fun DateCompact(sc: Scope) {
    val line = if (sc.alarm != null) max(sc.labelHeight(LABEL), LABEL * sc.fontScale) + GAP else 0f
    Box(GlanceModifier.fillMaxSize().padding(PAD.dp), contentAlignment = if (sc.alarm == null) Alignment.CenterStart else Alignment.TopStart) {
        BigDate(sc, sc.width - 2 * PAD, sc.height - 2 * PAD - line, BIG_DATE_MAX)
        if (sc.alarm != null) Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) { Alarm(sc) }
    }
}

/**
 * 4×1: At a Glance, with your alarm: "Wed, 18 Nov ··· ⏰ 7:30 AM". The date leads at the start,
 * the alarm answers at the end, a step smaller and quieter, both on one center line.
 */
@Composable
private fun DateWide(sc: Scope) {
    val alarmSp = ALARM_BESIDE_DATE
    val alarmW = sc.alarmWidth(alarmSp)
    Box(GlanceModifier.fillMaxSize().padding(horizontal = PAD.dp, vertical = 12.dp)) {
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            BigDate(sc, sc.width - 2 * PAD - (if (alarmW > 0f) alarmW + 24f else 0f), sc.height - 24f, BIG_DATE_MAX)
        }
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) { Alarm(sc, alarmSp) }
    }
}

/** 2×2: weekday and month on top, a big day number, the alarm under it. */
@Composable
private fun DateSquare(sc: Scope) {
    val topH = sc.labelHeight(LABEL_LARGE) + GAP + sc.labelHeight(LABEL)
    val alarmH = if (sc.cfg.showAlarm) sc.labelHeight(LABEL) + 8f else 0f
    val size = sc.fitDigits("00", sc.width - 2 * PAD, sc.height - 2 * PAD - topH - alarmH - 2 * GAP)
    Box(GlanceModifier.fillMaxSize().padding(PAD.dp)) {
        Column {
            DateLabel(sc, "EEEE", LABEL_LARGE, sc.c.text)
            Spacer(GlanceModifier.height(GAP.dp))
            DateLabel(sc, "MMMM", LABEL, sc.c.textVariant)
        }
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Column {
                val d = sc.pattern("d")
                Big(sc, d, d, size, sc.digitsWidth("00", size))
                if (sc.cfg.showAlarm) {
                    Box(GlanceModifier.fillMaxWidth().height(alarmH.dp), contentAlignment = Alignment.BottomStart) { Alarm(sc) }
                }
            }
        }
    }
}

/** 4×2 and up: the alarm on top, the date very large. */
@Composable
private fun DateLarge(sc: Scope) {
    val header = if (sc.alarm != null) max(sc.labelHeight(LABEL_LARGE), LABEL_LARGE * sc.fontScale) + GAP else 0f
    Box(GlanceModifier.fillMaxSize().padding(PAD.dp)) {
        if (sc.alarm != null) SplitLine(header, { }, { Alarm(sc, LABEL_LARGE) })
        // Alone, the date sits in the middle; under an alarm it anchors the bottom.
        Box(GlanceModifier.fillMaxSize(), contentAlignment = if (sc.alarm == null) Alignment.CenterStart else Alignment.BottomStart) {
            BigDate(sc, sc.width - 2 * PAD, sc.height - 2 * PAD - header, BIG_DATE_MAX * 1.6f)
        }
    }
}
