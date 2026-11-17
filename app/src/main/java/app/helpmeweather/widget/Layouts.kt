package app.helpmeweather.widget

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
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
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
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
import app.helpmeweather.R
import app.helpmeweather.data.Condition
import app.helpmeweather.data.Fmt
import app.helpmeweather.data.ForecastMode
import app.helpmeweather.data.IconStyle
import app.helpmeweather.data.IconTone
import app.helpmeweather.data.ThemeMode
import app.helpmeweather.data.WeatherData
import app.helpmeweather.data.WidgetStyle
import app.helpmeweather.data.googleIcon
import app.helpmeweather.data.symbolIcon
import app.helpmeweather.data.upcomingDays
import app.helpmeweather.data.upcomingHours
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Spacing from Android's canonical widget layouts.
private val PAD = 16.dp            // widget edge → content
private val GAP = 12.dp            // between larger blocks
private val TEXT_GAP = 4.dp        // between stacked text lines
private val TOUCH = 48.dp          // minimum touch target

/** System font families, resolved once: Google Sans Flex / Google Sans on Pixel, else Roboto. */
private object Fonts {
    private fun pick(vararg names: String): FontFamily =
        names.firstOrNull { Typeface.create(it, Typeface.NORMAL) != Typeface.DEFAULT }?.let { FontFamily(it) } ?: FontFamily.SansSerif
    val display: FontFamily by lazy { pick("google-sans-flex", "google-sans") }
    val text: FontFamily by lazy { pick("google-sans-flex", "google-sans-text", "google-sans") }
}

private fun style(c: ColorProvider, size: Float, weight: FontWeight = FontWeight.Normal, display: Boolean = false, align: TextAlign? = null) =
    TextStyle(color = c, fontSize = size.sp, fontWeight = weight, fontFamily = if (display) Fonts.display else Fonts.text, textAlign = align)

/** Everything a layout needs, resolved once per composition. */
private class Scope(val ctx: Context, val s: WidgetState, val c: WColors, val w: WeatherData, val f: Fmt, val width: Float, val height: Float) {
    val cfg get() = s.cfg
    val bold get() = cfg.style == WidgetStyle.BOLD
    private val symbols get() = cfg.iconStyle == IconStyle.SYMBOLS
    private val tone get() = c.iconTone ?: when (cfg.themeMode) {
        ThemeMode.SYSTEM -> IconTone.AUTO
        ThemeMode.LIGHT -> IconTone.LIGHT
        ThemeMode.DARK -> IconTone.DARK
    }

    @Composable
    fun Icon(cond: Condition, isDay: Boolean, size: Dp) {
        val desc = f.conditionName(cond)
        if (symbols) {
            Image(ImageProvider(cond.symbolIcon(isDay)), desc, GlanceModifier.size(size), colorFilter = ColorFilter.tint(c.accent))
        } else {
            Image(ImageProvider(cond.googleIcon(isDay, tone)), desc, GlanceModifier.size(size))
        }
    }

    @Composable
    fun NowIcon(size: Dp) = Icon(w.current.condition, w.current.isDay, size)

    val conditionText get() = f.condition(w.current)
    val temp get() = f.temp(w.current.tempC)
    val hiLo: String?
        get() = w.today?.let { t ->
            ctx.getString(R.string.hi_lo, f.temp(max(t.highC, w.current.tempC)), f.temp(min(t.lowC, w.current.tempC)))
        }
    val subLine: String?
        get() = buildList {
            if (cfg.showHighLow) hiLo?.let(::add)
            if (cfg.showFeelsLike) w.current.feelsLikeC?.let { add(ctx.getString(R.string.feels_like, f.temp(it))) }
        }.joinToString("  ").ifEmpty { null }
}

@Composable
fun WidgetLayouts(s: WidgetState, c: WColors) {
    val context = LocalContext.current
    val size = LocalSize.current
    val w = s.weather
    when {
        s.place == null -> Message(c, R.drawable.ms_location_on, context.getString(R.string.tap_to_set_location))
        w == null -> if (s.refreshing) Loading(c) else Message(c, R.drawable.ms_refresh, context.getString(R.string.tap_to_load))
        else -> {
            val x = Scope(context, s, c, w, Fmt(context, s.app, w.zoneId), size.width.value, size.height.value)
            if (app.helpmeweather.BuildConfig.DEBUG) android.util.Log.d("HelpMeWeatherWidget", "layout for ${x.width}x${x.height}dp")
            // Breakpoints by grid span. Pixel cells are ~97dp wide and ~105dp tall, so
            // 1 row < 130dp, 2 rows < 280dp, 2 columns < 270dp.
            when {
                x.height < 130 -> when {
                    x.width < 150 -> Tiny(x)
                    x.width < 270 -> Compact(x)
                    else -> Strip(x)
                }
                x.height < 280 -> if (x.width < 270) Small(x) else Medium(x)
                else -> if (x.width < 270) Tall(x) else Large(x)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------

/** 48dp refresh target; the glyph is 20dp. Shows a spinner while a refresh runs. */
@Composable
private fun RefreshButton(x: Scope, target: Dp = TOUCH) {
    Box(
        modifier = GlanceModifier.size(target).cornerRadius(target / 2).clickable(actionRunCallback<RefreshAction>()),
        contentAlignment = Alignment.Center,
    ) {
        if (x.s.refreshing) {
            CircularProgressIndicator(modifier = GlanceModifier.size(18.dp), color = x.c.textVariant)
        } else {
            Image(
                ImageProvider(R.drawable.ms_refresh), x.ctx.getString(R.string.refresh),
                GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(x.c.textVariant),
            )
        }
    }
}

/** Refresh button pinned to the widget's top-end corner, its touch area overlapping the padding. */
@Composable
private fun CornerRefresh(x: Scope) {
    if (!x.cfg.showRefresh) return
    Box(modifier = GlanceModifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.TopEnd) { RefreshButton(x) }
}

@Composable
private fun LocationLabel(x: Scope, size: Float = 14f, modifier: GlanceModifier = GlanceModifier) {
    if (!x.cfg.showLocation) return
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (x.cfg.location == null) {
            Image(
                ImageProvider(R.drawable.ms_near_me), null,
                GlanceModifier.size((size - 1f).dp).padding(end = 2.dp), colorFilter = ColorFilter.tint(x.c.text),
            )
        }
        Text(x.s.place?.name ?: x.w.place.name, style = style(x.c.text, size, FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun UpdatedLabel(x: Scope) {
    if (!x.cfg.showUpdated) return
    val stale = System.currentTimeMillis() - x.w.fetchedAt > max(3L * 3_600_000L, x.s.app.refreshMinutes * 3L * 60_000L)
    Text(
        if (stale) x.ctx.getString(R.string.offline) else x.f.clock(x.w.fetchedAt),
        style = style(x.c.textVariant, 11f), maxLines = 1,
    )
}

@Composable
private fun Hero(x: Scope, size: Float) {
    Text(x.temp, style = style(x.c.text, size, FontWeight.Normal, display = true), maxLines = 1)
}

/** Condition + high/low, bottom of a temperature block. */
@Composable
private fun Summary(x: Scope, withIcon: Boolean, iconSize: Dp = 24.dp, condSize: Float = 14f, subSize: Float = 13f) {
    if (withIcon) {
        x.NowIcon(iconSize)
        Spacer(GlanceModifier.height(TEXT_GAP))
    }
    if (x.cfg.showCondition) Text(x.conditionText, style = style(x.c.text, condSize, FontWeight.Medium), maxLines = 1)
    x.subLine?.let { Text(it, style = style(x.c.textVariant, subSize), maxLines = 1) }
}

/** Location, big temperature, condition, H/L — classic (iOS) or big icon (One UI). */
@Composable
private fun TempBlock(x: Scope, modifier: GlanceModifier, heroSize: Float, trailing: (@Composable () -> Unit)? = null) {
    Column(modifier = modifier) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LocationLabel(x, modifier = GlanceModifier.defaultWeight())
            if (!x.cfg.showLocation) Spacer(GlanceModifier.defaultWeight())
            trailing?.invoke()
        }
        if (x.bold) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                x.NowIcon((heroSize * 0.95f).dp)
                Spacer(GlanceModifier.width(8.dp))
                Hero(x, heroSize * 0.9f)
            }
            Spacer(GlanceModifier.defaultWeight())
            Summary(x, withIcon = false)
        } else {
            Hero(x, heroSize)
            Spacer(GlanceModifier.defaultWeight())
            Summary(x, withIcon = true)
        }
    }
}

private class Cell(val label: String, val cond: Condition, val isDay: Boolean, val top: String, val bottom: String?, val pop: Int?, val now: Boolean)

@Composable
private fun RowScope.ForecastCell(x: Scope, cell: Cell, iconSize: Dp, showPop: Boolean, labelSize: Float, tempSize: Float) {
    Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            cell.label,
            style = style(if (cell.now) x.c.text else x.c.textVariant, labelSize, if (cell.now) FontWeight.Medium else FontWeight.Normal, align = TextAlign.Center),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(6.dp))
        x.Icon(cell.cond, cell.isDay, iconSize)
        if (showPop) {
            Text(
                if ((cell.pop ?: 0) >= 20) "${cell.pop}%" else " ",
                style = style(x.c.accent, 11f, FontWeight.Medium, align = TextAlign.Center), maxLines = 1,
            )
        } else {
            Spacer(GlanceModifier.height(6.dp))
        }
        // High and low share one line (low dimmed) so daily rows are as compact as hourly ones.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cell.top, style = style(x.c.text, tempSize, FontWeight.Medium), maxLines = 1)
            cell.bottom?.let {
                Spacer(GlanceModifier.width(4.dp))
                Text(it, style = style(x.c.textVariant, tempSize - 1f), maxLines = 1)
            }
        }
    }
}

private fun cells(x: Scope, mode: ForecastMode, n: Int): List<Cell> = if (mode == ForecastMode.DAILY) {
    x.w.upcomingDays().take(n).mapIndexed { i, d ->
        Cell(x.f.day(d.epochSec, i), d.condition, true, x.f.temp(d.highC), x.f.temp(d.lowC), d.precipProb, i == 0)
    }
} else {
    listOf(Cell(x.ctx.getString(R.string.now), x.w.current.condition, x.w.current.isDay, x.temp, null, x.w.current.precipProb, true)) +
        x.w.upcomingHours().drop(1).take(n - 1).map { h ->
            Cell(x.f.hour(h.epochSec), h.condition, h.isDay, x.f.temp(h.tempC), null, h.precipProb, false)
        }
}

/** Height a forecast row needs, so layouts can decide what fits. */
private fun forecastHeight(daily: Boolean, iconSize: Float, pop: Boolean) =
    16f + 6f + iconSize + (if (pop) 15f else 6f) + 20f

@Composable
private fun ForecastRow(x: Scope, width: Float, mode: ForecastMode, modifier: GlanceModifier, iconSize: Dp, allowPop: Boolean, minCell: Float = 44f, labelSize: Float = 12f, tempSize: Float = 15f) {
    val n = floor(width / minCell).toInt().coerceIn(2, 7)
    val list = cells(x, mode, n)
    val showPop = allowPop && mode == ForecastMode.HOURLY && list.any { (it.pop ?: 0) >= 20 }
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        list.forEach { ForecastCell(x, it, iconSize, showPop, labelSize, tempSize) }
    }
}

/** Daily list with a temperature-range bar scaled to the shown days' extremes. */
@Composable
private fun ColumnScope.DailyList(x: Scope, rows: Int, width: Float) {
    val days = x.w.upcomingDays().take(rows)
    if (days.isEmpty()) return
    val lo = days.minOf { it.lowC }
    val range = (days.maxOf { it.highC } - lo).coerceAtLeast(1.0)
    val showPop = days.any { (it.precipProb ?: 0) >= 20 }
    val dayW = 52f; val iconW = 32f; val popW = if (showPop) 40f else 0f; val tW = 40f
    val barW = (width - dayW - iconW - popW - 2 * tW).coerceAtLeast(24f)
    days.forEachIndexed { i, d ->
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                x.f.day(d.epochSec, i), style = style(x.c.text, 14f, FontWeight.Medium), maxLines = 1,
                modifier = GlanceModifier.width(dayW.dp),
            )
            Box(GlanceModifier.width(iconW.dp), contentAlignment = Alignment.CenterStart) { x.Icon(d.condition, true, 24.dp) }
            if (showPop) {
                Text(
                    if ((d.precipProb ?: 0) >= 20) "${d.precipProb}%" else "",
                    style = style(x.c.accent, 12f, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.width(popW.dp),
                )
            }
            Text(
                x.f.temp(d.lowC), style = style(x.c.textVariant, 14f, align = TextAlign.End), maxLines = 1,
                modifier = GlanceModifier.width(tW.dp).padding(end = 8.dp),
            )
            RangeBar(x, barW, ((d.lowC - lo) / range).toFloat(), ((d.highC - lo) / range).toFloat())
            Text(
                x.f.temp(d.highC), style = style(x.c.text, 14f, FontWeight.Medium, align = TextAlign.End), maxLines = 1,
                modifier = GlanceModifier.width(tW.dp),
            )
        }
    }
}

@Composable
private fun RangeBar(x: Scope, width: Float, from: Float, to: Float) {
    val minLen = 8f
    val start = (from * width).coerceIn(0f, width - minLen)
    val len = ((to - from) * width).coerceIn(minLen, width - start)
    Box(modifier = GlanceModifier.width(width.dp).height(4.dp).cornerRadius(2.dp).background(x.c.track)) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            if (start > 0f) Spacer(GlanceModifier.width(start.dp))
            Box(modifier = GlanceModifier.width(len.dp).fillMaxHeight().cornerRadius(2.dp).background(x.c.accent)) {}
        }
    }
}

// ---------------------------------------------------------------------------------------
// Layouts by size
// ---------------------------------------------------------------------------------------

/** 1x1 */
@Composable
private fun Tiny(x: Scope) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
    ) {
        x.NowIcon((min(x.width, x.height) * 0.45f).coerceAtMost(40f).dp)
        Text(x.temp, style = style(x.c.text, 16f, FontWeight.Medium, display = true), maxLines = 1)
    }
}

/** 2x1 / 3x1 */
@Composable
private fun Compact(x: Scope) {
    Row(modifier = GlanceModifier.fillMaxSize().padding(start = PAD, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        x.NowIcon(36.dp)
        Spacer(GlanceModifier.width(GAP))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Hero(x, 28f)
            if (x.cfg.showLocation) LocationLabel(x, 12f)
            else x.subLine?.let { Text(it, style = style(x.c.textVariant, 12f), maxLines = 1) }
        }
        if (x.cfg.showRefresh) RefreshButton(x) else Spacer(GlanceModifier.width(PAD - 4.dp))
    }
}

/** 4x1 / 5x1 — current conditions, then a compact hourly strip when there's room. */
@Composable
private fun Strip(x: Scope) {
    val withForecast = x.cfg.forecast != ForecastMode.NONE && x.width >= 300f
    Row(modifier = GlanceModifier.fillMaxSize().padding(start = PAD, end = if (x.cfg.showRefresh) 4.dp else PAD), verticalAlignment = Alignment.CenterVertically) {
        x.NowIcon(40.dp)
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = if (withForecast) GlanceModifier else GlanceModifier.defaultWeight()) {
            Hero(x, 30f)
            val sub = when {
                x.cfg.showLocation -> null
                withForecast -> x.subLine
                else -> listOfNotNull(if (x.cfg.showCondition) x.conditionText else null, x.subLine).joinToString("  ").ifEmpty { null }
            }
            if (x.cfg.showLocation) LocationLabel(x, 12f)
            sub?.let { Text(it, style = style(x.c.textVariant, 12f), maxLines = 1) }
        }
        if (withForecast) {
            Spacer(GlanceModifier.width(GAP))
            val rest = x.width - 16f - 50f - 96f - 12f - (if (x.cfg.showRefresh) 52f else 16f)
            ForecastRow(x, rest, x.cfg.forecast, GlanceModifier.defaultWeight(), 22.dp, allowPop = false, minCell = 44f, labelSize = 11f, tempSize = 13f)
        }
        if (x.cfg.showRefresh) RefreshButton(x)
    }
}

/** 2x2 — iOS-style small. */
@Composable
private fun Small(x: Scope) {
    Box(modifier = GlanceModifier.fillMaxSize()) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(PAD)) {
            Row(modifier = GlanceModifier.fillMaxWidth().padding(end = if (x.cfg.showRefresh) 28.dp else 0.dp)) {
                LocationLabel(x)
            }
            if (x.bold) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    x.NowIcon(40.dp)
                    Spacer(GlanceModifier.width(6.dp))
                    Hero(x, 38f)
                }
            } else {
                Hero(x, if (x.height >= 150) 52f else 40f)
            }
            Spacer(GlanceModifier.defaultWeight())
            Summary(x, withIcon = !x.bold && x.height >= 150, iconSize = 32.dp, condSize = 14f, subSize = 13f)
        }
        CornerRefresh(x)
    }
}

/** 2x3+ — small on top, compact daily list below. */
@Composable
private fun Tall(x: Scope) {
    Box(modifier = GlanceModifier.fillMaxSize()) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(PAD)) {
            Row(modifier = GlanceModifier.fillMaxWidth().padding(end = if (x.cfg.showRefresh) 28.dp else 0.dp)) { LocationLabel(x) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hero(x, 44f)
                Spacer(GlanceModifier.width(8.dp))
                x.NowIcon(36.dp)
            }
            if (x.cfg.showCondition) Text(x.conditionText, style = style(x.c.text, 13f, FontWeight.Medium), maxLines = 1)
            x.subLine?.let { Text(it, style = style(x.c.textVariant, 12f), maxLines = 1) }
            Spacer(GlanceModifier.height(GAP))
            val rows = floor((x.height - 32f - 130f) / 30f).toInt().coerceIn(1, 7)
            x.w.upcomingDays().take(rows).forEachIndexed { i, d ->
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    Text(x.f.day(d.epochSec, i), style = style(x.c.text, 13f, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    x.Icon(d.condition, true, 22.dp)
                    Text(x.f.temp(d.highC), style = style(x.c.text, 13f, FontWeight.Medium, align = TextAlign.End), maxLines = 1, modifier = GlanceModifier.width(36.dp))
                    Text(x.f.temp(d.lowC), style = style(x.c.textVariant, 13f, align = TextAlign.End), maxLines = 1, modifier = GlanceModifier.width(34.dp))
                }
            }
        }
        CornerRefresh(x)
    }
}

/**
 * 4x2. With enough height (Pixel 4x2 is ~210dp tall) it's laid out like iOS / One UI:
 * temperature left, condition right, and a full-width evenly spaced hourly row at the bottom.
 * Short grids fall back to temperature-left / forecast-right.
 */
@Composable
private fun Medium(x: Scope) {
    val innerW = x.width - 32f
    val innerH = x.height - 32f
    Box(modifier = GlanceModifier.fillMaxSize()) {
        if (x.cfg.forecast != ForecastMode.NONE && innerH >= StackedMinHeight) {
            Stacked(x, GlanceModifier.fillMaxSize().padding(PAD), x.cfg.forecast, innerW, innerH)
            CornerRefresh(x)
        } else {
            val inline = MediumBody(x, GlanceModifier.fillMaxSize().padding(PAD), x.cfg.forecast, innerW, innerH)
            if (!inline) CornerRefresh(x)
        }
    }
}

private const val StackedMinHeight = 150f

/** Header (location) · hero temp ⟷ condition block · full-width forecast row. */
@Composable
private fun Stacked(x: Scope, modifier: GlanceModifier, mode: ForecastMode, innerW: Float, innerH: Float) {
    val daily = mode == ForecastMode.DAILY
    val roomy = innerH >= 170f
    val iconSize = if (roomy) 30f else 26f
    val heroSize = if (roomy) 54f else 46f
    Column(modifier = modifier) {
        // Header: location, leaving room for the corner refresh button.
        Row(modifier = GlanceModifier.fillMaxWidth().padding(end = if (x.cfg.showRefresh) 32.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            LocationLabel(x, modifier = GlanceModifier.defaultWeight())
            if (!x.cfg.showLocation) Spacer(GlanceModifier.defaultWeight())
            UpdatedLabel(x)
        }
        // Hero row: condition block bottom-aligned with the temperature, clear of the corner button.
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (x.bold) {
                x.NowIcon((heroSize * 0.9f).dp)
                Spacer(GlanceModifier.width(8.dp))
            }
            Hero(x, heroSize)
            Spacer(GlanceModifier.defaultWeight())
            Column(modifier = GlanceModifier.padding(bottom = 8.dp), horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!x.bold) {
                        x.NowIcon(24.dp)
                        if (x.cfg.showCondition) Spacer(GlanceModifier.width(6.dp))
                    }
                    if (x.cfg.showCondition) Text(x.conditionText, style = style(x.c.text, 14f, FontWeight.Medium), maxLines = 1)
                }
                x.subLine?.let { Text(it, style = style(x.c.textVariant, 13f, align = TextAlign.End), maxLines = 1) }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        val pop = innerH - 20f - heroSize * 1.25f - forecastHeight(daily, iconSize, true) >= 0f
        ForecastRow(x, innerW, mode, GlanceModifier.fillMaxWidth(), iconSize.dp, allowPop = pop, minCell = 52f)
    }
}

/**
 * Returns true when the refresh button had to go inline (not enough room above the forecast
 * for the corner button without overlapping it).
 */
@Composable
private fun MediumBody(x: Scope, modifier: GlanceModifier, mode: ForecastMode, innerW: Float, innerH: Float): Boolean {
    val daily = mode == ForecastMode.DAILY
    // Pick the richest forecast that still leaves room for the corner refresh button.
    val (iconSize, pop) = listOf(36f to true, 32f to true, 28f to true, 28f to false, 24f to false)
        .filter { (i, _) -> i <= 32f || innerH >= 160f }
        .firstOrNull { (i, p) -> innerH - forecastHeight(daily, i, p) >= 36f } ?: (24f to false)
    val inline = x.cfg.showRefresh && innerH - forecastHeight(daily, iconSize, pop) < 36f
    val heroSize = when {
        innerH >= 150 -> 60f
        innerH >= 110 -> 52f
        else -> 44f
    }
    val leftW = if (mode == ForecastMode.NONE) innerW else (innerW * 0.38f).coerceIn(120f, 170f)
    Row(modifier = modifier) {
        TempBlock(
            x, GlanceModifier.width(leftW.dp).fillMaxHeight(), heroSize,
            trailing = if (inline || (mode == ForecastMode.NONE && x.cfg.showRefresh)) { { RefreshButton(x, 32.dp) } } else null,
        )
        if (mode != ForecastMode.NONE) {
            Spacer(GlanceModifier.width(GAP))
            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight(), horizontalAlignment = Alignment.End) {
                Row(modifier = GlanceModifier.padding(end = if (x.cfg.showRefresh && !inline) 40.dp else 0.dp, top = 2.dp)) { UpdatedLabel(x) }
                Spacer(GlanceModifier.defaultWeight())
                ForecastRow(x, innerW - leftW - 12f, mode, GlanceModifier.fillMaxWidth(), iconSize.dp, pop)
            }
        }
    }
    return inline || mode == ForecastMode.NONE
}

/** 4x3+ — the stacked 4x2 on top, daily list with range bars below. */
@Composable
private fun Large(x: Scope) {
    val innerW = x.width - 32f
    val hasHourly = x.cfg.forecast != ForecastMode.NONE
    val topH = if (hasHourly) 176f else 96f
    Box(modifier = GlanceModifier.fillMaxSize()) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(PAD)) {
            if (hasHourly) {
                Stacked(x, GlanceModifier.fillMaxWidth().height(topH.dp), ForecastMode.HOURLY, innerW, topH)
            } else {
                MediumBody(x, GlanceModifier.fillMaxWidth().height(topH.dp), ForecastMode.NONE, innerW, topH)
            }
            Spacer(GlanceModifier.height(GAP))
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(x.c.track)) {}
            Spacer(GlanceModifier.height(TEXT_GAP))
            val rows = floor((x.height - 32f - topH - GAP.value - 5f) / 34f).toInt().coerceIn(1, 7)
            DailyList(x, rows, innerW)
        }
        if (hasHourly) CornerRefresh(x)
    }
}

// ---------------------------------------------------------------------------------------

@Composable
private fun Message(c: WColors, icon: Int, text: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(PAD),
        horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(icon), null, GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(c.accent))
        Spacer(GlanceModifier.height(8.dp))
        Text(text, style = style(c.text, 14f, FontWeight.Medium, align = TextAlign.Center), maxLines = 2)
    }
}

@Composable
private fun Loading(c: WColors) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = GlanceModifier.size(24.dp), color = c.accent)
    }
}
