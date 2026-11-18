package app.helpmestocks.widget

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
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.helpmestocks.R
import app.helpmestocks.data.ChangeStyle
import app.helpmestocks.data.Content
import app.helpmestocks.data.Fmt
import app.helpmestocks.data.Market
import app.helpmestocks.data.Quote
import app.helpmestocks.data.Ticker
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Spacing from Android's canonical widget layouts.
private val PAD = 16.dp            // widget edge → content
private val GAP = 12.dp            // between larger blocks
private val TOUCH = 48.dp          // minimum touch target
private const val MAX_ROWS = 9     // Glance: max 10 children per container (header + 9 rows)
private const val COMPACT_ROW = 30f // smallest row height: one line of text and a chip

/** System font families, resolved once: Google Sans Flex / Google Sans on Pixel, else Roboto. */
private object Fonts {
    private fun pick(vararg names: String): FontFamily =
        names.firstOrNull { Typeface.create(it, Typeface.NORMAL) != Typeface.DEFAULT }?.let { FontFamily(it) } ?: FontFamily.SansSerif
    val display: FontFamily by lazy { pick("google-sans-flex", "google-sans") }
    val text: FontFamily by lazy { pick("google-sans-flex", "google-sans-text", "google-sans") }
}

/**
 * Sizes here are fitted to the cell in dp. Drawn in sp, a larger system font size (or display
 * size) scaled the text past what the layout planned for, clipping and truncating it. So text
 * is drawn at the computed size × min(font scale, 1): unchanged at the default font size,
 * smaller fonts still honored, larger ones capped so nothing overflows.
 */
private val fontScale: Float get() = android.content.res.Resources.getSystem().configuration.fontScale.coerceAtLeast(0.5f)

private fun style(c: ColorProvider, size: Float, weight: FontWeight = FontWeight.Normal, display: Boolean = false, align: TextAlign? = null) =
    TextStyle(color = c, fontSize = (size * minOf(fontScale, 1f) / fontScale).sp, fontWeight = weight, fontFamily = if (display) Fonts.display else Fonts.text, textAlign = align)

/** Everything a layout needs, resolved once per composition. */
private class Scope(val ctx: Context, val s: WidgetState, val c: WColors, val f: Fmt, val width: Float, val height: Float) {
    val cfg get() = s.cfg
    val density = ctx.resources.displayMetrics.density
    val nowSec = System.currentTimeMillis() / 1000
    fun quote(t: Ticker): Quote? = s.quotes[t.symbol]
    fun dir(q: Quote?) = q?.let { Fmt.direction(it) } ?: 0
    fun changeText(q: Quote?) = q?.let { f.change(it, cfg.changeMode) } ?: "–"

    // Mixed markets (say S&P 500, Netflix and VWCE on XETRA): show each price's currency and each
    // listing's exchange, but only when the watchlist actually mixes them, so an all-US list
    // stays as clean as Apple's.
    private val listed = s.app.watchlist.filter { it.type != "INDEX" }
    val mixedCurrency = listed.mapNotNull { it.currency?.uppercase()?.take(3) }.distinct().size > 1
    val mixedExchange = listed.mapNotNull { it.exchange }.distinct().size > 1 || listed.any { it.label != it.symbol && it.symbol.contains('.') }
    fun priceText(t: Ticker, q: Quote?) = q?.let { if (mixedCurrency && t.type != "INDEX") f.price(it.price, t.currency) else f.price(it.price) } ?: "–"
    fun subtitle(t: Ticker): String? {
        val name = t.name.takeUnless { it.equals(t.label, ignoreCase = true) }
        val exch = t.exchange?.takeIf { mixedExchange && t.type != "INDEX" }
        return listOfNotNull(exch, name).joinToString(" · ").ifEmpty { null }
    }

    /** Spoken summary for a row: "Apple Inc., 354.17, up 0.62%". */
    fun describe(t: Ticker, q: Quote?): String {
        if (q == null) return t.name
        val d = dir(q)
        val ch = f.change(q, cfg.changeMode).trimStart('+', Fmt.MINUS)
        val word = ctx.getString(if (d > 0) R.string.up else if (d < 0) R.string.down else R.string.unchanged)
        return "${t.name}${t.exchange?.let { ", $it" } ?: ""}, ${priceText(t, q)}, $word $ch"
    }

    /**
     * Rough width of a change chip so all chips in a list share one width (as in Apple's widget):
     * Glance can't measure text, so estimate from the longest label at 13sp.
     */
    fun chipWidth(tickers: List<Ticker>): Float {
        val longest = tickers.maxOfOrNull { changeText(quote(it)).length } ?: 6
        return if (cfg.changeStyle == ChangeStyle.TEXT) longest * 7.4f else max(64f, longest * 7.4f + 16f)
    }

    /** "Market open · 3:42 PM" from the quotes shown. 24-hour markets (crypto) don't count. */
    val status: String
        get() {
            val shown = s.tickers.mapNotNull { quote(it) }
            val fetched = shown.maxOfOrNull { it.fetchedAt } ?: return ""
            val sessioned = shown.filter { it.sessionEnd - it.sessionStart < 20 * 3600 }
            val time = f.clock(fetched)
            val open = sessioned.count { Market.isOpen(it, nowSec) }
            return when {
                sessioned.isEmpty() -> ctx.getString(R.string.updated_at, time)
                open == sessioned.size -> ctx.getString(R.string.market_open_at, time)
                open > 0 -> ctx.getString(R.string.markets_some_open_at, time)
                else -> ctx.getString(R.string.market_closed_at, time)
            }
        }
}

@Composable
fun WidgetLayouts(s: WidgetState, c: WColors) {
    val context = LocalContext.current
    val size = LocalSize.current
    when {
        s.tickers.isEmpty() -> Message(c, R.drawable.ms_add, context.getString(R.string.tap_to_add))
        s.quotes.quotes.isEmpty() -> if (s.refreshing) Loading(c) else Message(c, R.drawable.ms_refresh, context.getString(R.string.tap_to_load))
        else -> {
            val x = Scope(context, s, c, Fmt(context), size.width.value, size.height.value)
            if (app.helpmestocks.BuildConfig.DEBUG) android.util.Log.d("HelpMeStocksWidget", "layout for ${x.width}x${x.height}dp")
            // Breakpoints by grid span, same as Help Me Weather. Pixel cells are ~97dp wide and
            // ~105dp tall, so 1 row < 130dp and 2 columns < 270dp.
            when {
                x.height < 130 -> when {
                    x.width < 150 -> Tiny(x)
                    x.width < 270 -> Compact(x)
                    else -> if (s.cfg.content == Content.SINGLE) Compact(x) else Strip(x)
                }
                // One column, two or more rows (launcher grids reflow widgets to this).
                x.width < 150 -> Narrow(x)
                s.cfg.content == Content.SINGLE -> if (x.width < 270) DetailNarrow(x) else DetailWide(x)
                else -> Watchlist(x)
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

/** Gain/loss: a tonal or solid chip, or plain coloured text. */
@Composable
private fun ChangeChip(x: Scope, q: Quote?, width: Float? = null, size: Float = 13f) {
    val dir = x.dir(q)
    val text = x.changeText(q)
    val cc = x.c.change
    if (x.cfg.changeStyle == ChangeStyle.TEXT || q == null) {
        Text(text, style = style(if (q == null) x.c.textVariant else cc.line(dir), size, FontWeight.Medium, align = TextAlign.End), maxLines = 1)
        return
    }
    val solid = x.cfg.changeStyle == ChangeStyle.SOLID
    val mod = GlanceModifier.height((size + 11f).dp).cornerRadius(8.dp).background(cc.container(dir, solid)).padding(horizontal = 8.dp)
    Box(modifier = if (width != null) mod.width(width.dp) else mod, contentAlignment = Alignment.CenterEnd) {
        Text(text, style = style(cc.onContainer(dir, solid), size, FontWeight.Medium, align = TextAlign.End), maxLines = 1)
    }
}

/** Intraday chart with the previous-close baseline; both tinted at render time. */
@Composable
private fun Chart(x: Scope, q: Quote?, w: Float, h: Float, fill: Boolean) {
    val bmp = q?.let { Sparkline.draw(it, w, h, x.density, fill) }
    Box(modifier = GlanceModifier.width(w.dp).height(h.dp)) {
        if (bmp != null) {
            bmp.baseline?.let {
                Image(ImageProvider(it), null, GlanceModifier.width(w.dp).height(h.dp), contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(x.c.textVariant))
            }
            Image(ImageProvider(bmp.line), null, GlanceModifier.width(w.dp).height(h.dp), contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(x.c.change.line(x.dir(q))))
        }
    }
}

@Composable
private fun Label(x: Scope, t: Ticker, size: Float = 15f) =
    Text(t.label, style = style(x.c.text, size, FontWeight.Medium), maxLines = 1)

@Composable
private fun Name(x: Scope, t: Ticker, size: Float = 12f, show: Boolean = x.cfg.showName) {
    // Indices often have a name that just repeats the label ("S&P 500"); mixed markets lead with the exchange.
    if (show) x.subtitle(t)?.let { Text(it, style = style(x.c.textVariant, size), maxLines = 1) }
}

private fun GlanceModifier.tap(a: Action?) = if (a != null) clickable(a) else this

@Composable
private fun Header(x: Scope, modifier: GlanceModifier) {
    Row(modifier = modifier.padding(end = if (x.cfg.showRefresh) 32.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        if (x.cfg.showHeader) {
            Text(x.status, style = style(x.c.textVariant, 12f, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }
    }
}

// ---------------------------------------------------------------------------------------
// Layouts by size
// ---------------------------------------------------------------------------------------

/** 1x1: first symbol, stacked. */
@Composable
private fun Tiny(x: Scope) {
    val t = x.s.tickers.first()
    val q = x.quote(t)
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(8.dp).semantics { contentDescription = x.describe(t, q) }.tap(rowTap(x.ctx, x.s, t)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(x, t, 13f)
        Text(x.priceText(t, q), style = style(x.c.text, 15f, FontWeight.Medium, display = true), maxLines = 1)
        Text(x.changeText(q), style = style(x.c.change.line(x.dir(q)), 12f, FontWeight.Medium), maxLines = 1)
    }
}

/** 2x1 / 3x1 (and single-stock 4x1): one symbol in a row. */
/**
 * 1 column × 2+ rows (~97-130 dp wide). One stock: symbol, name, a price fitted to the width
 * and its change. Watchlist: symbol over a price coloured by direction, as many rows as fit,
 * so nothing is cut to "…".
 */
@Composable
private fun Narrow(x: Scope) {
    val pad = 12f
    val inner = x.width - 2 * pad
    fun fit(text: String, max: Float) = minOf(max, inner / (text.length * 0.58f))
    Box(modifier = GlanceModifier.fillMaxSize()) {
        if (x.cfg.content == Content.SINGLE) {
            val t = x.s.tickers.first()
            val q = x.quote(t)
            val price = x.priceText(t, q)
            Column(modifier = GlanceModifier.fillMaxSize().padding(pad.dp).semantics { contentDescription = x.describe(t, q) }.tap(rowTap(x.ctx, x.s, t))) {
                Row(modifier = GlanceModifier.fillMaxWidth().padding(end = if (x.cfg.showRefresh) 28.dp else 0.dp)) { Label(x, t, 13f) }
                Name(x, t, 12f)
                Spacer(GlanceModifier.defaultWeight())
                Text(price, style = style(x.c.text, fit(price, 24f), FontWeight.Normal, display = true), maxLines = 1)
                Spacer(GlanceModifier.height(4.dp))
                ChangeChip(x, q, size = 12f)
            }
        } else {
            val rows = ((x.height - 2 * pad) / 40f).toInt().coerceIn(1, 4)
            Column(modifier = GlanceModifier.fillMaxSize().padding(pad.dp)) {
                x.s.tickers.take(rows).forEachIndexed { i, t ->
                    val q = x.quote(t)
                    val price = x.priceText(t, q)
                    Column(
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                            .semantics { contentDescription = x.describe(t, q) }.tap(rowTap(x.ctx, x.s, t)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Only the first label makes room for the refresh button; prices get the full width.
                        Row(modifier = GlanceModifier.fillMaxWidth().padding(end = if (i == 0 && x.cfg.showRefresh) 28.dp else 0.dp)) { Label(x, t, 12f) }
                        Text(price, style = style(x.c.change.line(x.dir(q)), fit(price, 15f), FontWeight.Medium, display = true), maxLines = 1)
                    }
                }
            }
        }
        if (x.cfg.showRefresh) CornerRefresh(x)
    }
}

@Composable
private fun Compact(x: Scope) {
    val t = x.s.tickers.first()
    val q = x.quote(t)
    val chart = x.cfg.showChart && x.width >= 230f
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(start = PAD, end = if (x.cfg.showRefresh) 4.dp else PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight().semantics { contentDescription = x.describe(t, q) }.tap(rowTap(x.ctx, x.s, t)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Label(x, t, 16f)
                Name(x, t)
            }
            Spacer(GlanceModifier.width(8.dp))
            if (chart) {
                val w = ((x.width - 150f) * 0.45f).coerceIn(48f, 160f)
                Chart(x, q, w, 32f, fill = true)
                Spacer(GlanceModifier.width(GAP))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(x.priceText(t, q), style = style(x.c.text, 16f, FontWeight.Medium, align = TextAlign.End), maxLines = 1)
                Spacer(GlanceModifier.height(2.dp))
                ChangeChip(x, q, size = 12f)
            }
        }
        if (x.cfg.showRefresh) RefreshButton(x)
    }
}

/** 4x1 / 5x1: several symbols side by side. */
@Composable
private fun Strip(x: Scope) {
    val refreshW = if (x.cfg.showRefresh) 52f else PAD.value
    val inner = x.width - PAD.value - refreshW
    val n = floor(inner / 104f).toInt().coerceIn(1, min(6, x.s.tickers.size))
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(start = PAD, end = if (x.cfg.showRefresh) 4.dp else PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            x.s.tickers.take(n).forEachIndexed { i, t ->
                val q = x.quote(t)
                Column(
                    modifier = GlanceModifier.defaultWeight().padding(start = if (i > 0) 8.dp else 0.dp)
                        .semantics { contentDescription = x.describe(t, q) }.tap(rowTap(x.ctx, x.s, t)),
                ) {
                    Text(t.label, style = style(x.c.textVariant, 12f, FontWeight.Medium), maxLines = 1)
                    Text(x.priceText(t, q), style = style(x.c.text, 16f, FontWeight.Medium, display = true), maxLines = 1)
                    Text(x.changeText(q), style = style(x.c.change.line(x.dir(q)), 12f, FontWeight.Medium), maxLines = 1)
                }
            }
        }
        if (x.cfg.showRefresh) RefreshButton(x)
    }
}

/**
 * 2x2 and up: the watchlist as rows, laid out like Apple's Stocks widget: symbol and name,
 * the day's chart when there's room, then price over a change chip. Rows fill the height.
 * Wide widgets always fit at least three rows: on short grids (a 4x2 can be ~170dp tall) rows
 * go compact, with the name dropped and the chip beside the price, as on Apple's medium widget.
 */
@Composable
private fun Watchlist(x: Scope) {
    val innerW = x.width - 32f
    val innerH = x.height - 32f
    val headerH = if (x.cfg.showHeader || x.cfg.showRefresh) 24f else 0f
    val wide = innerW >= 270f
    val rowMin = if (x.cfg.showName) (if (wide) 48f else 46f) else 38f
    val avail = innerH - headerH
    // Glance renders at most 10 children per container: the header plus up to 9 rows.
    val fit = floor(avail / rowMin).toInt()
    val want = min(3, x.s.tickers.size)
    val n = (if (wide && fit < want && avail / want >= COMPACT_ROW) want else fit)
        .coerceIn(1, max(1, min(MAX_ROWS, x.s.tickers.size)))
    val tickers = x.s.tickers.take(n)
    val rowH = avail / n
    // Price stacked over the chip needs ~46dp; below that the chip moves beside the price.
    val inline = wide && rowH < 46f
    val showSub = x.cfg.showName && rowH >= 42f
    val chartW = if (!x.cfg.showChart || !wide) 0f
        else if (inline) (innerW * 0.2f).coerceIn(48f, 110f)   // the chip sits on this row too
        else (innerW * 0.27f).coerceIn(64f, 150f)
    val chartH = (rowH - 14f).coerceIn(if (inline) 16f else 20f, 36f)
    val chipW = x.chipWidth(tickers)
    Box(modifier = GlanceModifier.fillMaxSize()) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(PAD)) {
            if (headerH > 0f) Header(x, GlanceModifier.fillMaxWidth().height(headerH.dp))
            tickers.forEach { t ->
                val q = x.quote(t)
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                        .semantics { contentDescription = x.describe(t, q) }.tap(rowTap(x.ctx, x.s, t)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Label(x, t, if (wide) 16f else 15f)
                        Name(x, t, show = showSub)
                    }
                    Spacer(GlanceModifier.width(8.dp))
                    if (chartW > 0f) {
                        Chart(x, q, chartW, chartH, fill = false)
                        Spacer(GlanceModifier.width(GAP))
                    }
                    if (inline) {
                        Text(x.priceText(t, q), style = style(x.c.text, 16f, FontWeight.Medium, align = TextAlign.End), maxLines = 1)
                        Spacer(GlanceModifier.width(8.dp))
                        ChangeChip(x, q, chipW, size = 12f)
                    } else {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(x.priceText(t, q), style = style(x.c.text, if (wide) 16f else 15f, FontWeight.Medium, align = TextAlign.End), maxLines = 1)
                            Spacer(GlanceModifier.height(2.dp))
                            ChangeChip(x, q, chipW, size = 12f)
                        }
                    }
                }
            }
        }
        CornerRefresh(x)
    }
}

/** 2x2 / 2x3 single stock: symbol, chart filling the middle, price and change at the bottom. */
@Composable
private fun DetailNarrow(x: Scope) {
    val t = x.s.tickers.first()
    val q = x.quote(t)
    val innerW = x.width - 32f
    val innerH = x.height - 32f
    val topH = if (x.cfg.showName) 38f else 22f
    val bottomH = 34f + 26f
    val chartH = innerH - topH - bottomH - 16f
    Box(modifier = GlanceModifier.fillMaxSize()) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(PAD).semantics { contentDescription = x.describe(t, q) }) {
            Column(modifier = GlanceModifier.fillMaxWidth().padding(end = if (x.cfg.showRefresh) 28.dp else 0.dp)) {
                Label(x, t, 16f)
                Name(x, t)
            }
            Spacer(GlanceModifier.defaultWeight())
            if (x.cfg.showChart && chartH >= 24f) {
                Chart(x, q, innerW, chartH, fill = true)
                Spacer(GlanceModifier.defaultWeight())
            }
            Text(x.priceText(t, q), style = style(x.c.text, if (innerW >= 150f) 28f else 24f, display = true), maxLines = 1)
            Spacer(GlanceModifier.height(4.dp))
            ChangeChip(x, q, size = 13f)
        }
        CornerRefresh(x)
    }
}

/** 3x2+ single stock: price block on the left, the day's chart on the right; day stats when tall. */
@Composable
private fun DetailWide(x: Scope) {
    val t = x.s.tickers.first()
    val q = x.quote(t)
    val innerW = x.width - 32f
    val innerH = x.height - 32f
    val statsQ = q?.takeIf { innerH >= 250f }
    val stats = statsQ != null
    val statsH = if (stats) 52f else 0f
    val leftW = (innerW * 0.38f).coerceIn(128f, 190f)
    val chartTop = if (x.cfg.showRefresh) 30f else 0f
    val chartW = innerW - leftW - GAP.value
    val chartH = innerH - statsH - chartTop
    Box(modifier = GlanceModifier.fillMaxSize()) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(PAD).semantics { contentDescription = x.describe(t, q) }) {
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                Column(modifier = GlanceModifier.width(leftW.dp).fillMaxHeight()) {
                    Label(x, t, 18f)
                    Name(x, t, 13f)
                    if (x.cfg.showHeader) {
                        Spacer(GlanceModifier.height(2.dp))
                        Text(x.status, style = style(x.c.textVariant, 11f), maxLines = 1)
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Text(x.priceText(t, q), style = style(x.c.text, if (innerH >= 150f) 36f else 30f, display = true), maxLines = 1)
                    Spacer(GlanceModifier.height(6.dp))
                    ChangeChip(x, q, size = 13f)
                }
                Spacer(GlanceModifier.width(GAP))
                if (x.cfg.showChart && chartW >= 64f && chartH >= 40f) {
                    Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                        Spacer(GlanceModifier.height(chartTop.dp))
                        Chart(x, q, chartW, chartH - 4f, fill = true)
                    }
                }
            }
            if (statsQ != null) {
                val q = statsQ
                Spacer(GlanceModifier.height(GAP))
                Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(x.c.track)) {}
                Spacer(GlanceModifier.height(8.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    val items = listOfNotNull(
                        q.prices.firstOrNull()?.let { R.string.stat_open to it },
                        q.prices.maxOrNull()?.let { R.string.stat_high to it },
                        q.prices.minOrNull()?.let { R.string.stat_low to it },
                        q.previousClose?.let { R.string.stat_prev to it },
                    )
                    items.forEach { (label, v) ->
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(x.ctx.getString(label), style = style(x.c.textVariant, 11f), maxLines = 1)
                            Text(x.f.price(v), style = style(x.c.text, 13f, FontWeight.Medium), maxLines = 1)
                        }
                    }
                }
            }
        }
        CornerRefresh(x)
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

