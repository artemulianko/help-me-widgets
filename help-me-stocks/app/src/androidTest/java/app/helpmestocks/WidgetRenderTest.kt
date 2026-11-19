package app.helpmestocks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.helpmestocks.data.AppSettings
import app.helpmestocks.data.BgTone
import app.helpmestocks.data.ChangeMode
import app.helpmestocks.data.ChangeStyle
import app.helpmestocks.data.Content
import app.helpmestocks.data.Palette
import app.helpmestocks.data.ThemeMode
import app.helpmestocks.data.UpColor
import app.helpmestocks.data.WidgetConfig
import app.helpmestocks.widget.SampleData
import app.helpmestocks.widget.StocksContent
import app.helpmestocks.widget.WidgetState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the real widget RemoteViews at every launcher size (incl. landscape) and a matrix of
 * configurations, inflates them like a launcher would, and writes PNGs for visual review:
 *   adb pull /sdcard/Android/data/app.helpmestocks/files/renders
 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(ctx.getExternalFilesDir(null), "renders").apply { mkdirs() }

    private class Fixed(private val s: WidgetState) : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { StocksContent(s) }
    }

    private val sizes = mapOf(
        "1x1" to DpSize(90.dp, 105.dp),
        "2x1" to DpSize(190.dp, 105.dp),
        "3x1" to DpSize(290.dp, 105.dp),
        "2x2" to DpSize(190.dp, 210.dp),
        "3x2" to DpSize(290.dp, 210.dp),
        "4x1" to DpSize(387.dp, 105.dp),
        "4x2" to DpSize(387.dp, 212.dp),
        "4x2_short" to DpSize(340.dp, 170.dp),
        "4x2_pixel_short" to DpSize(387.dp, 150.dp),
        "2x3" to DpSize(190.dp, 316.dp),
        "4x3" to DpSize(387.dp, 316.dp),
        "4x4" to DpSize(387.dp, 420.dp),
        "4x2_landscape" to DpSize(836.dp, 105.dp),
        // 1-column and other launcher grids (Layout: Large/XL reflows widgets to one column).
        "1x2" to DpSize(97.dp, 212.dp),
        "grid3_1x2" to DpSize(117.dp, 171.dp),
        "grid5_4x1" to DpSize(310.dp, 100.dp),
        "grid4x4_4x2" to DpSize(387.dp, 300.dp),
    )

    private fun render(name: String, state: WidgetState, size: DpSize) = runBlocking {
        val rv = Fixed(state).compose(ctx, size = size)
        val host = FrameLayout(ctx)
        val view = rv.apply(ctx, host)
        val d = ctx.resources.displayMetrics.density
        val w = (size.width.value * d).toInt(); val h = (size.height.value * d).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val drawn = (0 until 40).sumOf { x -> (0 until 40).count { y -> bmp.getPixel(w * x / 40, h * y / 40) != 0 } }
        assertTrue("$name rendered nothing", drawn > 10)
    }

    private fun render(name: String, cfg: WidgetConfig, size: DpSize) = render(name, SampleData.state(cfg), size)

    @Test fun everySizeRenders() {
        sizes.forEach { (n, s) -> render("size_$n", WidgetConfig(), s) }
        sizes.forEach { (n, s) -> render("single_$n", WidgetConfig(content = Content.SINGLE, single = "GOOGL"), s) }
    }

    @Test fun configurationMatrixRenders() {
        val base = WidgetConfig()
        val variants = mapOf(
            "price_change" to base.copy(changeMode = ChangeMode.PRICE),
            "solid" to base.copy(changeStyle = ChangeStyle.SOLID),
            "text" to base.copy(changeStyle = ChangeStyle.TEXT),
            "red_up" to base.copy(upColor = UpColor.RED_UP),
            "dark_vibrant" to base.copy(themeMode = ThemeMode.DARK, bgTone = BgTone.PRIMARY),
            "light_tonal" to base.copy(themeMode = ThemeMode.LIGHT, bgTone = BgTone.SECONDARY),
            "dark" to base.copy(themeMode = ThemeMode.DARK),
            "transparent" to base.copy(opacity = 0),
            "palette_teal" to base.copy(palette = Palette.TEAL),
            "palette_mono_dark" to base.copy(palette = Palette.MONO, themeMode = ThemeMode.DARK),
            "minimal" to base.copy(showName = false, showChart = false, showHeader = false, showRefresh = false),
            "no_header_with_refresh" to base.copy(showHeader = false, showRefresh = true),
        )
        variants.forEach { (n, c) ->
            render("cfg_${n}_4x2", c, sizes.getValue("4x2"))
            render("cfg_${n}_2x2", c, sizes.getValue("2x2"))
            render("cfg_${n}_4x3", c, sizes.getValue("4x3"))
        }
    }

    /** A manual refresh re-renders with the spinner first; every layout must survive it. */
    @Test fun refreshingStateRenders() {
        val base = WidgetConfig()
        val cfgs = mapOf(
            "default" to base,
            "single" to base.copy(content = Content.SINGLE, single = "GOOGL"),
            "no_header_with_refresh" to base.copy(showHeader = false, showRefresh = true),
            "dynamic" to base.copy(palette = Palette.DYNAMIC),
            "transparent" to base.copy(opacity = 0),
        )
        cfgs.forEach { (cn, c) ->
            sizes.forEach { (sn, s) -> render("refreshing_${cn}_$sn", SampleData.state(c).copy(refreshing = true), s) }
        }
    }

    @Test fun edgeStatesRender() {
        render("empty_watchlist", WidgetState(AppSettings(watchlist = emptyList()), WidgetConfig(), SampleData.quotes(emptyList()), false, 0), sizes.getValue("4x2"))
        render("no_quotes", WidgetState(AppSettings(), WidgetConfig(), app.helpmestocks.data.QuoteSet(), false, 0), sizes.getValue("4x2"))
        render("loading", WidgetState(AppSettings(), WidgetConfig(), app.helpmestocks.data.QuoteSet(), true, 0), sizes.getValue("2x2"))
        // Long names, tiny and huge prices must not break the layout.
        val odd = AppSettings(watchlist = listOf(
            app.helpmestocks.data.Ticker("BRK-B", "Berkshire Hathaway Inc. New Class B Common Stock", "BRK-B"),
            app.helpmestocks.data.Ticker("SHIB-USD", "Shiba Inu USD", "SHIB-USD", "CRYPTOCURRENCY"),
            app.helpmestocks.data.Ticker("BTC-USD", "Bitcoin USD", "BTC-USD", "CRYPTOCURRENCY"),
        ))
        val q = SampleData.quotes(odd.watchlist.map { it.symbol }).quotes.toMutableMap()
        q["SHIB-USD"] = q.getValue("SHIB-USD").copy(price = 0.00001234, change = -0.00000012, changePct = -0.96)
        q["BTC-USD"] = q.getValue("BTC-USD").copy(price = 123456.78, change = 2345.67, changePct = 12.34)
        val s = WidgetState(odd, WidgetConfig(), app.helpmestocks.data.QuoteSet(q), false, 0)
        listOf("2x2", "4x2", "4x1", "1x1").forEach { render("odd_$it", s, sizes.getValue(it)) }
        render("odd_price_4x2", s.copy(cfg = WidgetConfig(changeMode = ChangeMode.PRICE)), sizes.getValue("4x2"))
    }
    /**
     * A refresh redraws twice (spinner on, then off). The launcher only updates a widget in place
     * when the new RemoteViews share the old layout; otherwise it throws the views away and
     * inflates new ones, which shows as a flash on a real phone. reapply() fails exactly then.
     */
    @Test fun refreshRedrawUpdatesInPlace() = runBlocking {
        val base = SampleData.state(WidgetConfig())
        val bad = mutableListOf<String>()
        for ((n, s) in sizes) {
            val spinning = Fixed(base.copy(refreshing = true)).compose(ctx, size = s)
            val view = spinning.apply(ctx, FrameLayout(ctx))
            for ((label, next) in listOf(
                "spinner off" to base.copy(refreshing = false),
                "spinner on again" to base.copy(refreshing = true),
            )) {
                val rv = Fixed(next).compose(ctx, size = s)
                runCatching { rv.reapply(ctx, view) }.onFailure { bad += "$n ($label): ${it.javaClass.simpleName} ${it.message?.take(90)}" }
            }
        }
        assertTrue("re-inflated instead of updated in place:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * New quotes must also update in place. A first refresh after pinning redrew the whole widget
     * on a Pixel 11 Pro: if a data change alters the view structure (a chart appearing, a baseline
     * missing, a row changing shape), the launcher rebuilds everything.
     */
    @Test fun newQuotesUpdateInPlace() = runBlocking {
        val base = SampleData.state(WidgetConfig())
        fun edit(f: (app.helpmestocks.data.Quote) -> app.helpmestocks.data.Quote) =
            base.copy(quotes = base.quotes.copy(quotes = base.quotes.quotes.mapValues { (_, q) -> f(q) }))
        val variants = listOf(
            "prices moved" to edit { q -> q.copy(price = q.price * 1.01, prices = q.prices.map { it * 1.01 }) },
            "direction flipped" to edit { q -> q.copy(change = -q.change, changePct = -q.changePct) },
            "one point (no chart)" to edit { q -> q.copy(times = q.times.take(1), prices = q.prices.take(1)) },
            "no intraday data" to edit { q -> q.copy(times = emptyList(), prices = emptyList()) },
            "no previous close" to edit { q -> q.copy(previousClose = null) },
            "more points" to edit { q -> q.copy(times = q.times + (q.times.last() + 300), prices = q.prices + q.prices.last()) },
        )
        val bad = mutableListOf<String>()
        for ((n, s) in sizes) for ((label, next) in variants) {
            val view = Fixed(base).compose(ctx, size = s).apply(ctx, FrameLayout(ctx))
            runCatching { Fixed(next).compose(ctx, size = s).reapply(ctx, view) }
                .onFailure { bad += "$n ($label): ${it.javaClass.simpleName} ${it.message?.take(80)}" }
        }
        assertTrue("re-inflated instead of updated in place:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

}
