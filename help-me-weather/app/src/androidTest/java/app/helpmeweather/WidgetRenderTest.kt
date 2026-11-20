package app.helpmeweather

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
import app.helpmeweather.data.BgTone
import app.helpmeweather.data.ForecastMode
import app.helpmeweather.data.IconStyle
import app.helpmeweather.data.Palette
import app.helpmeweather.data.ThemeMode
import app.helpmeweather.data.WidgetConfig
import app.helpmeweather.data.WidgetStyle
import app.helpmeweather.widget.SampleData
import app.helpmeweather.widget.WeatherContent
import app.helpmeweather.widget.WidgetState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the real widget RemoteViews at every launcher size (incl. landscape) and a matrix of
 * configurations, inflates them like a launcher would, and writes PNGs for visual review:
 *   adb pull /sdcard/Android/data/app.helpmeweather/files/renders
 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(ctx.getExternalFilesDir(null), "renders").apply { mkdirs() }

    private class Fixed(private val s: WidgetState) : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { WeatherContent(s) }
    }

    private val sizes = mapOf(
        "1x1" to DpSize(90.dp, 105.dp),
        "2x1" to DpSize(190.dp, 105.dp),
        "2x2" to DpSize(190.dp, 210.dp),
        "3x2" to DpSize(290.dp, 210.dp),
        "4x1" to DpSize(387.dp, 105.dp),
        "4x2" to DpSize(387.dp, 212.dp),
        "4x2_short" to DpSize(340.dp, 170.dp),
        "2x3" to DpSize(190.dp, 316.dp),
        "4x3" to DpSize(387.dp, 316.dp),
        "4x4" to DpSize(387.dp, 420.dp),
        "4x2_landscape" to DpSize(836.dp, 105.dp),
        // 1-column and other launcher grids (Layout: Large/XL reflows widgets to one column).
        "1x2" to DpSize(97.dp, 212.dp),
        "grid3_1x2" to DpSize(117.dp, 171.dp),
        "grid5_4x1" to DpSize(310.dp, 100.dp),
        "grid4x4_4x2" to DpSize(387.dp, 300.dp),
        // Tall sizes: up to 7 daily rows (once past Glance's 10-children-per-column limit).
        "2x4" to DpSize(190.dp, 420.dp),
        "2x5" to DpSize(190.dp, 525.dp),
        "4x5" to DpSize(387.dp, 525.dp),
    )

    private fun render(name: String, cfg: WidgetConfig, size: DpSize) = runBlocking {
        val state = SampleData.state(ctx, cfg)
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

    @Test fun everySizeRenders() {
        val cfg = WidgetConfig(location = SampleData.weather().place)
        sizes.forEach { (n, s) -> render("size_$n", cfg, s) }
    }

    /** The tallest layouts with every daily row and no hourly row (the 4×4 path that had 11 children). */
    @Test fun tallLayoutsWithManyRowsRender() {
        val base = WidgetConfig(location = SampleData.weather().place)
        for ((n, c) in mapOf("no_forecast" to base.copy(forecast = ForecastMode.NONE), "daily" to base.copy(forecast = ForecastMode.DAILY), "hourly" to base)) {
            for (size in listOf("2x4", "2x5", "4x4", "4x5")) render("tall_${n}_$size", c, sizes.getValue(size))
        }
    }

    @Test fun configurationMatrixRenders() {
        val base = WidgetConfig(location = SampleData.weather().place)
        val variants = mapOf(
            "bold" to base.copy(style = WidgetStyle.BOLD),
            "daily" to base.copy(forecast = ForecastMode.DAILY),
            "no_forecast" to base.copy(forecast = ForecastMode.NONE),
            "dark_vibrant" to base.copy(themeMode = ThemeMode.DARK, bgTone = BgTone.PRIMARY),
            "light_tonal" to base.copy(themeMode = ThemeMode.LIGHT, bgTone = BgTone.SECONDARY),
            "transparent" to base.copy(opacity = 0),
            "half_opacity" to base.copy(opacity = 50),
            "palette_teal" to base.copy(palette = Palette.TEAL),
            "palette_mono_dark" to base.copy(palette = Palette.MONO, themeMode = ThemeMode.DARK),
            "symbols" to base.copy(iconStyle = IconStyle.SYMBOLS),
            "minimal" to base.copy(showLocation = false, showCondition = false, showHighLow = false, showRefresh = false),
            "everything" to base.copy(showFeelsLike = true, showUpdated = true),
            "updated_time" to base.copy(showUpdated = true),
            "updated_time_dark" to base.copy(showUpdated = true, themeMode = ThemeMode.DARK),
            "updated_no_location" to base.copy(showUpdated = true, showLocation = false),
            "no_location_with_refresh" to base.copy(showLocation = false, showRefresh = true),
            "daily_bold" to base.copy(forecast = ForecastMode.DAILY, style = WidgetStyle.BOLD),
        )
        variants.forEach { (n, c) ->
            render("cfg_${n}_4x2", c, sizes.getValue("4x2"))
            render("cfg_${n}_2x2", c, sizes.getValue("2x2"))
            render("cfg_${n}_4x3", c, sizes.getValue("4x3"))
        }
    }
    /**
     * A refresh redraws twice (spinner on, then off). The launcher only updates a widget in place
     * when the new RemoteViews share the old layout; otherwise it throws the views away and
     * inflates new ones, which shows as a flash on a real phone. reapply() fails exactly then.
     */
    @Test fun refreshRedrawUpdatesInPlace() = runBlocking {
        val base = SampleData.state(ctx)
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

}
