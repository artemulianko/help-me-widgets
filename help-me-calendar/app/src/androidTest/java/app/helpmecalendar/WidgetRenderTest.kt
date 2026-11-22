package app.helpmecalendar

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.composeForPreview
import androidx.glance.appwidget.provideContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.helpmecalendar.data.BgTone
import app.helpmecalendar.data.Palette
import app.helpmecalendar.data.ThemeMode
import app.helpmecalendar.data.WidgetConfig
import app.helpmecalendar.widget.CalendarContent
import app.helpmecalendar.widget.SampleData
import app.helpmecalendar.widget.WidgetState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class WidgetRenderTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val sizes = listOf(
        "1x1" to DpSize(97.dp, 105.dp),
        "2x1" to DpSize(194.dp, 105.dp),
        "4x1" to DpSize(387.dp, 105.dp),
        "1x2" to DpSize(97.dp, 212.dp),
        "2x2" to DpSize(194.dp, 212.dp),
        "2x3" to DpSize(194.dp, 318.dp),
        "4x2" to DpSize(387.dp, 212.dp),
        "4x3" to DpSize(387.dp, 318.dp),
        "4x4" to DpSize(387.dp, 424.dp),
        "landscape" to DpSize(836.dp, 105.dp),
    )

    @Test fun everySizeAndStateRenders() = runBlocking {
        val dir = File(ctx.getExternalFilesDir(null), "renders").apply { mkdirs() }
        val configs = listOf(
            "light" to SampleData.state(WidgetConfig(palette = Palette.BLUE, themeMode = ThemeMode.LIGHT)),
            "dark" to SampleData.state(WidgetConfig(palette = Palette.BLUE, themeMode = ThemeMode.DARK)),
            "transparent" to SampleData.state(WidgetConfig(palette = Palette.DYNAMIC, bgTone = BgTone.SECONDARY, opacity = 60)),
        )
        for ((name, sz) in sizes) {
            for ((variant, st) in configs) {
                val bmp = render(st, sz)
                assertTrue("$name/$variant should draw pixels", hasNonUniformPixels(bmp))
                FileOutputStream(File(dir, "${name}_${variant}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        // Edge states: empty events (with peeking cat, light + dark) and missing permission.
        val emptyLight = render(WidgetState(WidgetConfig(palette = Palette.BLUE, themeMode = ThemeMode.LIGHT), hasPermission = true, events = emptyList(), monthEventColors = emptyMap(), appWidgetId = 0), DpSize(387.dp, 212.dp))
        assertTrue("empty light state should draw pixels", hasNonUniformPixels(emptyLight))
        FileOutputStream(File(dir, "4x2_empty_light.png")).use { emptyLight.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val emptyDark = render(WidgetState(WidgetConfig(palette = Palette.BLUE, themeMode = ThemeMode.DARK), hasPermission = true, events = emptyList(), monthEventColors = emptyMap(), appWidgetId = 0), DpSize(387.dp, 212.dp))
        assertTrue("empty dark state should draw pixels", hasNonUniformPixels(emptyDark))
        FileOutputStream(File(dir, "4x2_empty_dark.png")).use { emptyDark.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val noPerm = render(WidgetState(WidgetConfig(), hasPermission = false, events = emptyList(), monthEventColors = emptyMap(), appWidgetId = 0), DpSize(387.dp, 212.dp))
        assertTrue("no-permission state should draw pixels", hasNonUniformPixels(noPerm))
    }

    private suspend fun render(state: WidgetState, size: DpSize): Bitmap {
        val w = object : GlanceAppWidget() {
            override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { CalendarContent(state) }
            override suspend fun providePreview(context: Context, widgetCategory: Int) = provideContent {
                CompositionLocalProvider(LocalSize provides size) { CalendarContent(state) }
            }
        }
        val rv = w.composeForPreview(ctx, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
        val density = ctx.resources.displayMetrics.density
        val pxW = (size.width.value * density).toInt().coerceAtLeast(1)
        val pxH = (size.height.value * density).toInt().coerceAtLeast(1)
        lateinit var bmp: Bitmap
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val host = FrameLayout(ctx)
            val view = rv.apply(ctx, host)
            view.measure(View.MeasureSpec.makeMeasureSpec(pxW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(pxH, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, pxW, pxH)
            bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
        }
        return bmp
    }

    private fun hasNonUniformPixels(bmp: Bitmap): Boolean {
        val first = bmp.getPixel(0, 0)
        for (y in 0 until bmp.height step 8) {
            for (x in 0 until bmp.width step 8) {
                if (bmp.getPixel(x, y) != first) return true
            }
        }
        return false
    }
}
