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
import app.helpmestocks.data.QuoteSet
import app.helpmestocks.data.Ticker
import app.helpmestocks.data.WidgetConfig
import app.helpmestocks.data.YahooFinance
import app.helpmestocks.widget.StocksContent
import app.helpmestocks.widget.WidgetState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Live network: a watchlist that mixes markets and currencies (an index, US stocks, and an ETF
 * on XETRA in EUR) is searched, looked up, fetched in one batch and rendered.
 */
@RunWith(AndroidJUnit4::class)
class LiveMarketsTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private class Fixed(private val s: WidgetState) : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { StocksContent(s) }
    }

    @Test fun mixedMarketsWatchlist() = runBlocking {
        val vwce = YahooFinance.search("VWCE").first { it.symbol == "VWCE.DE" }
        assertEquals("VWCE", vwce.label)
        assertEquals("XETRA", vwce.exchange)
        val nflx = YahooFinance.search("Netflix").first { it.symbol == "NFLX" }

        val list = listOf(
            Ticker("^GSPC", "S&P 500", "S&P 500", "INDEX"),
            YahooFinance.withDetails(nflx),
            YahooFinance.withDetails(Ticker("GOOGL", "Alphabet Inc.", "GOOGL", "EQUITY", "NASDAQ")),
            YahooFinance.withDetails(vwce),
        )
        assertEquals("USD", list[1].currency)
        assertEquals("EUR", list[3].currency)

        val quotes = YahooFinance.quotes(list.map { it.symbol })
        assertEquals(list.map { it.symbol }.toSet(), quotes.map { it.symbol }.toSet())
        quotes.forEach { assertTrue("${it.symbol} price", it.price > 0) }
        // Each market keeps its own session: XETRA closes hours before New York.
        val x = quotes.first { it.symbol == "VWCE.DE" }; val n = quotes.first { it.symbol == "NFLX" }
        // Compare closing times of day (UTC): before New York opens, Yahoo still reports Netflix's
        // previous day, so comparing dates failed mid-day in Europe.
        assertTrue(x.sessionEnd % 86_400 < n.sessionEnd % 86_400)

        val state = WidgetState(AppSettings(watchlist = list), WidgetConfig(), QuoteSet(quotes.associateBy { it.symbol }), false, 0)
        val out = File(ctx.getExternalFilesDir(null), "renders").apply { mkdirs() }
        listOf("4x2" to DpSize(387.dp, 212.dp), "2x2" to DpSize(190.dp, 210.dp), "4x3" to DpSize(387.dp, 316.dp)).forEach { (n, size) ->
            val rv = Fixed(state).compose(ctx, size = size)
            val view = rv.apply(ctx, FrameLayout(ctx))
            val d = ctx.resources.displayMetrics.density
            val w = (size.width.value * d).toInt(); val h = (size.height.value * d).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            File(out, "live_mixed_$n.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
