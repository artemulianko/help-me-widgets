package app.nimbus.weather.widget

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
import app.nimbus.weather.R
import app.nimbus.weather.data.Fmt
import app.nimbus.weather.data.ForecastMode
import app.nimbus.weather.data.IconStyle
import app.nimbus.weather.data.Palette
import app.nimbus.weather.data.WeatherData
import app.nimbus.weather.data.WidgetStyle
import app.nimbus.weather.data.iconRes
import app.nimbus.weather.data.upcomingDays
import app.nimbus.weather.data.upcomingHours
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Google Sans on Pixel (falls back to the system sans elsewhere). */
private val DISPLAY = FontFamily("google-sans")
private val TEXT = FontFamily("google-sans-text")

private fun ts(c: ColorProvider, size: Float, weight: FontWeight = FontWeight.Normal, family: FontFamily = TEXT, align: TextAlign? = null) =
    TextStyle(color = c, fontSize = size.sp, fontWeight = weight, fontFamily = family, textAlign = align)

/** Everything a layout needs, resolved once per composition. */
private class Ctx(val ctx: android.content.Context, val s: WidgetState, val c: WColors, val w: WeatherData, val f: Fmt, val width: Float, val height: Float) {
    val cfg get() = s.cfg
    val themedIcons get() = cfg.iconStyle == IconStyle.THEMED && cfg.palette == Palette.DYNAMIC
    val bold get() = cfg.style == WidgetStyle.BOLD
    val nowIcon get() = w.current.condition.iconRes(w.current.isDay, themedIcons)
    val hiLo: String?
        get() = w.today?.let { t ->
            val hi = max(t.highC, w.current.tempC); val lo = min(t.lowC, w.current.tempC)
            ctx.getString(R.string.hi_lo, f.temp(hi), f.temp(lo))
        }
}

@Composable
fun WidgetLayouts(s: WidgetState, c: WColors) {
    val context = LocalContext.current
    val size = LocalSize.current
    val w = s.weather
    when {
        s.place == null -> Message(c, R.drawable.ic_location, context.getString(R.string.tap_to_set_location))
        w == null -> if (s.refreshing) Loading(c) else Message(c, R.drawable.ic_refresh, context.getString(R.string.tap_to_load))
        else -> {
            val x = Ctx(context, s, c, w, Fmt(context, s.app, w.zoneId), size.width.value, size.height.value)
            when {
                x.height < 96 -> when {
                    x.width < 100 -> Tiny(x)
                    x.width < 230 -> Compact(x)
                    else -> Strip(x)
                }
                x.height < 190 -> if (x.width < 200) Small(x) else Medium(x)
                else -> if (x.width < 200) SmallTall(x) else Large(x)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------

@Composable
private fun WIcon(res: Int, size: Dp, desc: String?) {
    Image(ImageProvider(res), contentDescription = desc, modifier = GlanceModifier.size(size))
}

@Composable
private fun RefreshButton(x: Ctx, size: Dp = 28.dp) {
    if (!x.cfg.showRefresh) return
    Box(
        modifier = GlanceModifier.size(size).cornerRadius(size / 2).clickable(actionRunCallback<RefreshAction>()),
        contentAlignment = Alignment.Center,
    ) {
        if (x.s.refreshing) {
            CircularProgressIndicator(modifier = GlanceModifier.size(size * 0.55f), color = x.c.textVariant)
        } else {
            Image(
                ImageProvider(R.drawable.ic_refresh),
                contentDescription = x.ctx.getString(R.string.refresh),
                modifier = GlanceModifier.size(size * 0.62f),
                colorFilter = ColorFilter.tint(x.c.textVariant),
            )
        }
    }
}

@Composable
private fun LocationLabel(x: Ctx, size: Float = 13f, modifier: GlanceModifier = GlanceModifier) {
    if (!x.cfg.showLocation) return
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (x.cfg.location == null) {
            Image(
                ImageProvider(R.drawable.ic_near_me), contentDescription = null,
                modifier = GlanceModifier.size((size - 1).dp).padding(end = 3.dp),
                colorFilter = ColorFilter.tint(x.c.text),
            )
        }
        Text(x.s.place?.name ?: x.w.place.name, style = ts(x.c.text, size, FontWeight.Medium, DISPLAY), maxLines = 1)
    }
}

@Composable
private fun UpdatedLabel(x: Ctx) {
    if (!x.cfg.showUpdated) return
    val stale = System.currentTimeMillis() - x.w.fetchedAt > max(3, x.s.app.refreshMinutes / 20) * 60 * 60_000L
    val txt = if (stale) x.ctx.getString(R.string.offline) else x.f.clock(x.w.fetchedAt)
    Text(txt, style = ts(x.c.textVariant, 11f), maxLines = 1)
}

@Composable
private fun BigTemp(x: Ctx, size: Float) {
    Text(x.f.temp(x.w.current.tempC), style = ts(x.c.text, size, FontWeight.Normal, DISPLAY), maxLines = 1)
}

@Composable
private fun ConditionLine(x: Ctx, size: Float = 13f, showIcon: Boolean = true, iconSize: Dp = 18.dp) {
    if (!x.cfg.showCondition && !showIcon) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showIcon) {
            WIcon(x.nowIcon, iconSize, x.f.condition(x.w.current))
            if (x.cfg.showCondition) Spacer(GlanceModifier.width(4.dp))
        }
        if (x.cfg.showCondition) Text(x.f.condition(x.w.current), style = ts(x.c.text, size, FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun SubLine(x: Ctx, size: Float = 12f) {
    val parts = buildList {
        if (x.cfg.showHighLow) x.hiLo?.let(::add)
        if (x.cfg.showFeelsLike) x.w.current.feelsLikeC?.let { add(x.ctx.getString(R.string.feels_like, x.f.temp(it))) }
    }
    if (parts.isEmpty()) return
    Text(parts.joinToString("  ·  "), style = ts(x.c.textVariant, size), maxLines = 1)
}

/** Left-hand block: location, big temperature, condition, high/low (classic or bold). */
@Composable
private fun TempBlock(x: Ctx, modifier: GlanceModifier, tempSize: Float, compact: Boolean = false) {
    Column(modifier = modifier) {
        LocationLabel(x)
        if (x.bold) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WIcon(x.nowIcon, (tempSize * 1.05f).dp, x.f.condition(x.w.current))
                Spacer(GlanceModifier.width(6.dp))
                BigTemp(x, tempSize * 0.92f)
            }
            Spacer(GlanceModifier.defaultWeight())
            ConditionLine(x, showIcon = false)
        } else {
            BigTemp(x, tempSize)
            Spacer(GlanceModifier.defaultWeight())
            ConditionLine(x, showIcon = true, iconSize = if (compact) 18.dp else 22.dp)
        }
        SubLine(x)
    }
}

/** One column of the forecast strip. */
@Composable
private fun RowScope.ForecastCell(x: Ctx, label: String, icon: Int, desc: String, top: String, bottom: String?, pop: Int?, showPop: Boolean, iconSize: Dp, emphasize: Boolean) {
    Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = ts(if (emphasize) x.c.text else x.c.textVariant, 12f, if (emphasize) FontWeight.Medium else FontWeight.Normal, align = TextAlign.Center), maxLines = 1)
        Spacer(GlanceModifier.height(4.dp))
        WIcon(icon, iconSize, desc)
        if (showPop) {
            Text(if (pop != null && pop >= 20) "$pop%" else " ", style = ts(x.c.accent, 10f, FontWeight.Medium, align = TextAlign.Center), maxLines = 1)
        } else {
            Spacer(GlanceModifier.height(3.dp))
        }
        Text(top, style = ts(x.c.text, 14f, FontWeight.Medium, DISPLAY, TextAlign.Center), maxLines = 1)
        if (bottom != null) Text(bottom, style = ts(x.c.textVariant, 12f, align = TextAlign.Center), maxLines = 1)
    }
}

@Composable
private fun ForecastRow(x: Ctx, availWidth: Float, mode: ForecastMode, modifier: GlanceModifier = GlanceModifier, iconSize: Dp = 28.dp, minCell: Float = 46f) {
    val n = floor(availWidth / minCell).toInt().coerceIn(2, 7)
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        if (mode == ForecastMode.DAILY) {
            val days = x.w.upcomingDays().take(n)
            days.forEachIndexed { i, d ->
                ForecastCell(
                    x, x.f.day(d.epochSec, i), d.condition.iconRes(true, x.themedIcons), x.f.conditionName(d.condition),
                    x.f.temp(d.highC), x.f.temp(d.lowC), d.precipProb, false, iconSize, i == 0,
                )
            }
        } else {
            val hours = x.w.upcomingHours().drop(1).take(n - 1)
            val showPop = hours.any { (it.precipProb ?: 0) >= 20 }
            ForecastCell(
                x, x.ctx.getString(R.string.now), x.nowIcon, x.f.condition(x.w.current),
                x.f.temp(x.w.current.tempC), null, x.w.current.precipProb, showPop, iconSize, true,
            )
            hours.forEach { h ->
                ForecastCell(
                    x, x.f.hour(h.epochSec), h.condition.iconRes(h.isDay, x.themedIcons), x.f.conditionName(h.condition),
                    x.f.temp(h.tempC), null, h.precipProb, showPop, iconSize, false,
                )
            }
        }
    }
}

/** iOS-style daily list with a temperature range bar scaled to the week's extremes. */
@Composable
private fun ColumnScope.DailyList(x: Ctx, rows: Int, width: Float) {
    val days = x.w.upcomingDays().take(rows)
    if (days.isEmpty()) return
    val lo = days.minOf { it.lowC }; val hi = days.maxOf { it.highC }
    val range = (hi - lo).coerceAtLeast(1.0)
    val showPop = days.any { (it.precipProb ?: 0) >= 20 }
    val labelW = 44f; val iconW = 26f; val popW = if (showPop) 34f else 0f; val tW = 38f
    val barW = (width - labelW - iconW - popW - tW * 2 - 12f).coerceAtLeast(24f)
    days.forEachIndexed { i, d ->
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(x.f.day(d.epochSec, i), style = ts(x.c.text, 13f, if (i == 0) FontWeight.Bold else FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.width(labelW.dp))
            WIcon(d.condition.iconRes(true, x.themedIcons), 22.dp, x.f.conditionName(d.condition))
            Spacer(GlanceModifier.width((iconW - 22f).dp))
            if (showPop) {
                Text(if ((d.precipProb ?: 0) >= 20) "${d.precipProb}%" else "", style = ts(x.c.accent, 11f, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.width(popW.dp))
            }
            Text(x.f.temp(d.lowC), style = ts(x.c.textVariant, 13f, align = TextAlign.End), maxLines = 1, modifier = GlanceModifier.width(tW.dp).padding(end = 6.dp))
            RangeBar(x, barW, ((d.lowC - lo) / range).toFloat(), ((d.highC - lo) / range).toFloat())
            Text(x.f.temp(d.highC), style = ts(x.c.text, 13f, FontWeight.Medium, align = TextAlign.Start), maxLines = 1, modifier = GlanceModifier.width(tW.dp).padding(start = 6.dp))
        }
    }
}

@Composable
private fun RangeBar(x: Ctx, width: Float, from: Float, to: Float) {
    val start = (from * width).coerceIn(0f, width - 6f)
    val len = ((to - from) * width).coerceAtLeast(6f).coerceAtMost(width - start)
    Box(modifier = GlanceModifier.width(width.dp).height(5.dp).cornerRadius(3.dp).background(x.c.track)) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            if (start > 0f) Spacer(GlanceModifier.width(start.dp))
            Box(modifier = GlanceModifier.width(len.dp).fillMaxHeight().cornerRadius(3.dp).background(x.c.accent)) {}
        }
    }
}

// ---------------------------------------------------------------------------------------
// Layouts
// ---------------------------------------------------------------------------------------

/** 1x1 */
@Composable
private fun Tiny(x: Ctx) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
        WIcon(x.nowIcon, (min(x.width, x.height) * 0.42f).dp, x.f.condition(x.w.current))
        Text(x.f.temp(x.w.current.tempC), style = ts(x.c.text, 15f, FontWeight.Medium, DISPLAY), maxLines = 1)
    }
}

/** 2x1 / 3x1 */
@Composable
private fun Compact(x: Ctx) {
    Row(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        WIcon(x.nowIcon, 38.dp, x.f.condition(x.w.current))
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            BigTemp(x, 26f)
            if (x.cfg.showLocation) LocationLabel(x, 11f)
            else if (x.cfg.showHighLow) SubLine(x, 11f)
        }
        RefreshButton(x, 26.dp)
    }
}

/** 4x1 / 5x1 */
@Composable
private fun Strip(x: Ctx) {
    Row(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        WIcon(x.nowIcon, 40.dp, x.f.condition(x.w.current))
        Spacer(GlanceModifier.width(8.dp))
        BigTemp(x, 32f)
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.width(min(140f, x.width * 0.32f).dp)) {
            if (x.cfg.showCondition) Text(x.f.condition(x.w.current), style = ts(x.c.text, 13f, FontWeight.Medium), maxLines = 1)
            if (x.cfg.showLocation) LocationLabel(x, 12f) else SubLine(x, 12f)
        }
        val rest = x.width - 32f - 40f - 8f - 80f - 10f - min(140f, x.width * 0.32f) - (if (x.cfg.showRefresh) 28f else 0f)
        if (x.cfg.forecast != ForecastMode.NONE && rest >= 80f) {
            ForecastRow(x, rest, x.cfg.forecast, GlanceModifier.defaultWeight(), iconSize = 18.dp, minCell = 40f)
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }
        RefreshButton(x, 26.dp)
    }
}

/** 2x2 — iOS "small". */
@Composable
private fun Small(x: Ctx) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LocationLabel(x, 13f, GlanceModifier.defaultWeight())
            if (!x.cfg.showLocation) Spacer(GlanceModifier.defaultWeight())
            RefreshButton(x, 24.dp)
        }
        if (x.bold) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WIcon(x.nowIcon, 40.dp, x.f.condition(x.w.current))
                Spacer(GlanceModifier.width(4.dp))
                BigTemp(x, 36f)
            }
        } else {
            BigTemp(x, 40f)
        }
        Spacer(GlanceModifier.defaultWeight())
        if (!x.bold) WIcon(x.nowIcon, 24.dp, x.f.condition(x.w.current))
        if (x.cfg.showCondition) Text(x.f.condition(x.w.current), style = ts(x.c.text, 12f, FontWeight.Medium), maxLines = 1)
        SubLine(x, 11f)
    }
}

/** 2x3+ — small + compact daily list. */
@Composable
private fun SmallTall(x: Ctx) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LocationLabel(x, 13f, GlanceModifier.defaultWeight())
            if (!x.cfg.showLocation) Spacer(GlanceModifier.defaultWeight())
            RefreshButton(x, 24.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigTemp(x, 38f)
            Spacer(GlanceModifier.width(6.dp))
            WIcon(x.nowIcon, 34.dp, x.f.condition(x.w.current))
        }
        ConditionLine(x, 12f, showIcon = false)
        SubLine(x, 11f)
        Spacer(GlanceModifier.height(8.dp))
        val rows = floor((x.height - 28f - 120f) / 26f).toInt().coerceIn(1, 7)
        x.w.upcomingDays().take(rows).forEachIndexed { i, d ->
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Text(x.f.day(d.epochSec, i), style = ts(x.c.text, 12f, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                WIcon(d.condition.iconRes(true, x.themedIcons), 20.dp, x.f.conditionName(d.condition))
                Text(x.f.temp(d.highC), style = ts(x.c.text, 12f, FontWeight.Medium, align = TextAlign.End), maxLines = 1, modifier = GlanceModifier.width(32.dp))
                Text(x.f.temp(d.lowC), style = ts(x.c.textVariant, 12f, align = TextAlign.End), maxLines = 1, modifier = GlanceModifier.width(30.dp))
            }
        }
    }
}

/** 4x2 — temperature on the left, forecast on the right. */
@Composable
private fun Medium(x: Ctx) {
    MediumBody(x, GlanceModifier.fillMaxSize().padding(16.dp), x.cfg.forecast, x.width - 32f)
}

@Composable
private fun MediumBody(x: Ctx, modifier: GlanceModifier, mode: ForecastMode, innerW: Float) {
    val leftW = if (mode == ForecastMode.NONE) innerW else (innerW * 0.36f).coerceIn(112f, 160f)
    Row(modifier = modifier) {
        TempBlock(x, GlanceModifier.width(leftW.dp).fillMaxHeight(), tempSize = if (x.height > 140) 50f else 44f)
        if (mode != ForecastMode.NONE) {
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(GlanceModifier.defaultWeight())
                    UpdatedLabel(x)
                    RefreshButton(x, 24.dp)
                }
                Spacer(GlanceModifier.defaultWeight())
                ForecastRow(x, innerW - leftW - 8f, mode, GlanceModifier.fillMaxWidth())
            }
        } else {
            Column(horizontalAlignment = Alignment.End) { UpdatedLabel(x); RefreshButton(x, 24.dp) }
        }
    }
}

/** 4x3+ — medium on top, daily list below. */
@Composable
private fun Large(x: Ctx) {
    val innerW = x.width - 32f
    Column(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
        val topMode = if (x.cfg.forecast == ForecastMode.NONE) ForecastMode.NONE else ForecastMode.HOURLY
        MediumBody(x, GlanceModifier.fillMaxWidth().height(if (topMode == ForecastMode.NONE) 96.dp else 118.dp), topMode, innerW)
        Spacer(GlanceModifier.height(10.dp))
        Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(x.c.track)) {}
        Spacer(GlanceModifier.height(4.dp))
        val listH = x.height - 32f - (if (topMode == ForecastMode.NONE) 96f else 118f) - 15f
        val rows = floor(listH / 28f).toInt().coerceIn(1, 7)
        DailyList(x, rows, innerW)
    }
}

// ---------------------------------------------------------------------------------------

@Composable
private fun Message(c: WColors, icon: Int, text: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(c.accent))
        Spacer(GlanceModifier.height(6.dp))
        Text(text, style = ts(c.text, 13f, FontWeight.Medium, align = TextAlign.Center), maxLines = 2)
    }
}

@Composable
private fun Loading(c: WColors) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = GlanceModifier.size(24.dp), color = c.accent)
    }
}
