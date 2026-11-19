package app.helpmeclock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextClock
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.helpmeclock.data.BgTone
import app.helpmeclock.data.Palette
import app.helpmeclock.data.TapAction
import app.helpmeclock.data.ThemeMode
import app.helpmeclock.data.Weight
import app.helpmeclock.data.WidgetConfig
import app.helpmeclock.widget.ClockContent
import app.helpmeclock.widget.SampleData
import app.helpmeclock.widget.WidgetState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the real widget RemoteViews at every launcher size (incl. landscape) and a matrix of
 * configurations, in 12- and 24-hour mode, inflates them like a launcher would, and writes PNGs
 * for visual review:
 *   adb pull /sdcard/Android/data/app.helpmeclock/files/renders
 * Also asserts that the numerals fit: no TextClock is wider or taller than its widget.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext
    private val out = File(ctx.getExternalFilesDir(null), "renders").apply { mkdirs() }
    private var saved12_24: String = ""

    private class Fixed(private val s: WidgetState) : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { ClockContent(s) }
    }

    private val sizes = mapOf(
        "1x1" to DpSize(97.dp, 105.dp),
        "1x1_small" to DpSize(73.dp, 84.dp),
        "2x1" to DpSize(194.dp, 105.dp),
        "3x1" to DpSize(290.dp, 105.dp),
        "1x2" to DpSize(97.dp, 212.dp),
        "2x2" to DpSize(194.dp, 212.dp),
        "3x2" to DpSize(290.dp, 212.dp),
        "4x1" to DpSize(387.dp, 105.dp),
        "4x2" to DpSize(387.dp, 212.dp),
        "2x3" to DpSize(194.dp, 316.dp),
        "4x3" to DpSize(387.dp, 316.dp),
        "4x4" to DpSize(387.dp, 420.dp),
        "4x1_landscape" to DpSize(836.dp, 105.dp),
        // Other launcher grids: 5-column cells are narrower, 4×4 rows taller.
        "grid5_4x1" to DpSize(310.dp, 100.dp),
        "grid5_2x1" to DpSize(150.dp, 100.dp),
        "grid5_1x1" to DpSize(70.dp, 100.dp),
        "grid4x4_4x1" to DpSize(387.dp, 145.dp),
        "grid4x4_2x1" to DpSize(194.dp, 145.dp),
        "grid4x4_4x2" to DpSize(387.dp, 300.dp),
        "grid3_3x1" to DpSize(387.dp, 120.dp),
    )

    private fun shell(cmd: String): String =
        inst.uiAutomation.executeShellCommand(cmd).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText().trim()
        }

    @Before fun saveTimeFormat() { saved12_24 = shell("settings get system time_12_24") }
    @After fun restoreTimeFormat() {
        if (saved12_24 == "null" || saved12_24.isEmpty()) shell("settings delete system time_12_24")
        else shell("settings put system time_12_24 $saved12_24")
    }

    private fun clocks(v: View): List<TextClock> = when (v) {
        is TextClock -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { clocks(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun render(name: String, state: WidgetState, size: DpSize) = runBlocking {
        val rv = Fixed(state).compose(ctx, size = size)
        val host = FrameLayout(ctx)
        val view = rv.apply(ctx, host)
        // TextClock fills in its text when attached to a window; off-screen we ask it directly.
        clocks(view).forEach { it.refreshTime() }
        val d = ctx.resources.displayMetrics.density
        val w = (size.width.value * d).toInt(); val h = (size.height.value * d).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val drawn = (0 until 40).sumOf { x -> (0 until 40).count { y -> bmp.getPixel(w * x / 40, h * y / 40) != 0 } }
        assertTrue("$name rendered nothing", drawn > 10)
        // Every clock shows text, and its text fits inside the view it was given (no clipping).
        clocks(view).filter { it.text.isNotEmpty() }.forEach { c ->
            val textW = c.paint.measureText(c.text.toString())
            assertTrue("$name: '${c.text}' clipped (${textW.toInt()} > ${c.width})", textW <= c.width + 2)
            assertTrue("$name: '${c.text}' taller than its line", c.height >= c.lineHeight - 2)
            assertTrue("$name: '${c.text}' outside the widget", c.right <= w + 1 && c.bottom <= h + 1)
        }
        assertTrue("$name: no time or date shown", clocks(view).any { it.text.isNotBlank() })
    }

    private fun render(name: String, cfg: WidgetConfig, size: DpSize, alarm: Boolean = true) =
        render(name, SampleData.state(cfg).let { if (alarm) it else it.copy(alarm = null) }, size)

    private fun allSizes(prefix: String) {
        sizes.forEach { (n, s) -> render("${prefix}_$n", WidgetConfig(), s) }
        sizes.forEach { (n, s) -> render("${prefix}_noalarm_$n", WidgetConfig(), s, alarm = false) }
        sizes.forEach { (n, s) -> render("${prefix}_timeonly_$n", WidgetConfig(showDate = false, showAlarm = false), s) }
        // Time off, date on: the status bar already shows the time.
        sizes.forEach { (n, s) -> render("${prefix}_dateonly_$n", WidgetConfig(showTime = false), s) }
        sizes.forEach { (n, s) -> render("${prefix}_dateonly_noalarm_$n", WidgetConfig(showTime = false), s, alarm = false) }
    }

    @Test fun everySizeRenders12h() { shell("settings put system time_12_24 12"); allSizes("h12") }

    @Test fun everySizeRenders24h() { shell("settings put system time_12_24 24"); allSizes("h24") }

    @Test fun configurationMatrixRenders() {
        val base = WidgetConfig()
        val variants = mapOf(
            "light" to base.copy(weight = Weight.LIGHT),
            "medium" to base.copy(weight = Weight.MEDIUM),
            "text_smaller" to base.copy(textSize = app.helpmeclock.data.TextSize.SMALLER),
            "text_larger" to base.copy(textSize = app.helpmeclock.data.TextSize.LARGER),
            "dateonly_text_smaller" to base.copy(showTime = false, textSize = app.helpmeclock.data.TextSize.SMALLER),
            "dateonly_long_text_smaller" to base.copy(showTime = false, dateStyle = app.helpmeclock.data.DateStyle.LONG, textSize = app.helpmeclock.data.TextSize.SMALLER),
            "dateonly_text_larger" to base.copy(showTime = false, textSize = app.helpmeclock.data.TextSize.LARGER),
            "dark_vibrant" to base.copy(themeMode = ThemeMode.DARK, bgTone = BgTone.PRIMARY),
            "light_tonal" to base.copy(themeMode = ThemeMode.LIGHT, bgTone = BgTone.SECONDARY),
            "dark" to base.copy(themeMode = ThemeMode.DARK),
            "transparent" to base.copy(opacity = 0),
            "palette_teal" to base.copy(palette = Palette.TEAL),
            "palette_mono_dark" to base.copy(palette = Palette.MONO, themeMode = ThemeMode.DARK),
            "no_date" to base.copy(showDate = false),
            "no_alarm" to base.copy(showAlarm = false),
            "dateonly_light_dark" to base.copy(showTime = false, weight = Weight.LIGHT, themeMode = ThemeMode.DARK),
            "dateonly_medium" to base.copy(showTime = false, weight = Weight.MEDIUM),
            "fmt_month_day" to base.copy(dateStyle = app.helpmeclock.data.DateStyle.MONTH_DAY),
            "fmt_long" to base.copy(dateStyle = app.helpmeclock.data.DateStyle.LONG),
            "dateonly_fmt_long" to base.copy(showTime = false, dateStyle = app.helpmeclock.data.DateStyle.LONG),
            "tap_nothing" to base.copy(tapAction = TapAction.NONE),
        )
        variants.forEach { (n, c) ->
            listOf("2x1", "1x1", "2x2", "4x1", "4x2").forEach { render("cfg_${n}_$it", c, sizes.getValue(it)) }
        }
    }

    /** An alarm more than a day away gets its weekday ("Thu 7:30 AM"), the longest label. */
    @Test fun farAlarmRenders() {
        val s = SampleData.state().let { it.copy(alarm = it.alarm!!.copy(triggerAt = it.now + 3 * 24 * 3600_000L)) }
        listOf("2x1", "2x2", "4x1", "4x2").forEach { render("far_alarm_$it", s, sizes.getValue(it)) }
        val d = s.copy(cfg = WidgetConfig(showTime = false))
        listOf("1x2", "2x1", "2x2", "4x1", "4x2").forEach { render("far_alarm_dateonly_$it", d, sizes.getValue(it)) }
    }
}
