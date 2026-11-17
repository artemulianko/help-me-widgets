package app.helpmestocks

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.helpmestocks.data.AppLog
import app.helpmestocks.data.Store
import app.helpmestocks.data.WidgetConfig
import app.helpmestocks.ui.ConfigActivity
import app.helpmestocks.ui.LogsActivity
import app.helpmestocks.ui.MainActivity
import app.helpmestocks.widget.StocksWidgetReceiver
import app.helpmestocks.widget.WidgetRenderer
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end flows through the real launcher, widget, worker and network:
 * pin a widget → tap its refresh button → quotes arrive; offline → waits → recovers;
 * editor saves; editor rejects foreign widget ids; logs copy to the clipboard.
 *
 * Forced refreshes fetch whatever the market hours, so these run on nights and weekends too.
 */
@RunWith(AndroidJUnit4::class)
class WidgetFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val mgr = AppWidgetManager.getInstance(ctx)
    private val provider = ComponentName(ctx, StocksWidgetReceiver::class.java)

    /** A price as the widget prints it: "6,712.34", "$60.18", "166,54 €", "116.60p". */
    private val pricePattern = Pattern.compile(".*\\d[\\d,. ]*[.,]\\d{2}.*", Pattern.DOTALL)

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).close()
    private fun ids() = mgr.getAppWidgetIds(provider).toList()

    @Before fun setUp() {
        device.wakeUp()
    }

    @After fun tearDown() {
        shell("svc wifi enable"); shell("svc data enable")
    }

    /** Pins a widget through the launcher's real "Add to home screen" dialog if none exists. */
    private fun ensureWidget(): Int {
        ids().firstOrNull()?.let { return it }
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("main screen", device.wait(Until.hasObject(By.res("add_widget")), 15_000))
        device.findObject(By.res("add_widget")).click()
        // The confirm button lives in the launcher's dialog, not in our app (our button may have the
        // same label). Package visibility hides the launcher's name from us, so find it by elimination.
        // Retry: the sheet animates in, so a found button can go stale before the click.
        var clicked = false
        val until = System.currentTimeMillis() + 15_000
        while (!clicked && System.currentTimeMillis() < until) {
            runCatching {
                val b = device.findObjects(By.text("Add to home screen")).firstOrNull { it.applicationPackage != ctx.packageName }
                if (b != null) { b.click(); clicked = true }
            }
            if (!clicked) Thread.sleep(400)
        }
        assertTrue("launcher pin dialog", clicked)
        val deadline = System.currentTimeMillis() + 10_000
        while (ids().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(250)
        assertTrue("widget pinned", ids().isNotEmpty())
        return ids().first()
    }

    private fun waitForLog(since: Long, timeoutMs: Long, predicate: (AppLog.Entry) -> Boolean): AppLog.Entry? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            AppLog.read(ctx).firstOrNull { it.time >= since && predicate(it) }?.let { return it }
            Thread.sleep(500)
        }
        return null
    }

    private fun isQuotesFetched(e: AppLog.Entry) = e.tag == "Fetch" && Regex("""^\d+ quote\(s\) ·""").containsMatchIn(e.message)

    /**
     * Finds the widget's refresh button on whichever home-screen page the launcher put it.
     * Widget views report *our* package (RemoteViews belong to the app) even though the launcher
     * draws them; on the home screen none of our activities are showing, so no filter is needed.
     */
    private fun findWidgetRefresh(): UiObject2? = device.findObject(By.desc("Refresh"))

    private fun tapWidgetRefresh() {
        // Tests share the widget; make sure its refresh button is enabled and rendered.
        runBlocking { ids().forEach { id -> Store(ctx).updateWidget(id) { it.copy(showRefresh = true) } }; WidgetRenderer.renderAll(ctx) }
        device.pressHome(); device.waitForIdle(); Thread.sleep(1_000)
        var btn = findWidgetRefresh()
        repeat(4) {
            if (btn == null) {
                device.swipe(device.displayWidth * 9 / 10, device.displayHeight / 2, device.displayWidth / 10, device.displayHeight / 2, 20)
                Thread.sleep(1_200)
                btn = findWidgetRefresh()
            }
        }
        if (btn == null) shell("screencap -p /data/local/tmp/widgetflow_fail.png")
        assertTrue(
            "widget refresh button visible on home screen; saw: " +
                device.findObjects(By.descContains("")).map { "${it.contentDescription}@${it.applicationPackage}" }.take(40),
            btn != null,
        )
        btn!!.click()
    }

    @Test fun pinWidgetTapRefreshAndQuotesArrive() {
        ensureWidget()
        Thread.sleep(61_000) // the refresh button reuses quotes fetched in the last minute
        val t0 = System.currentTimeMillis()
        tapWidgetRefresh()
        assertTrue("tap logged", waitForLog(t0, 10_000) { it.message.contains("Refresh tapped") } != null)
        // Since 0.1.2 the tap fetches inline (no WorkManager job on the happy path).
        assertTrue(
            "refresh started inline",
            waitForLog(t0, 10_000) { it.tag == "Refresh" && it.message.contains("Started (refresh button, forced)") } != null,
        )
        assertTrue("quotes fetched after tap", waitForLog(t0, 45_000, ::isQuotesFetched) != null)
        val after = AppLog.read(ctx).filter { it.time >= t0 }
        assertTrue("no errors: ${after.filter { it.level == AppLog.Level.E }}", after.none { it.level == AppLog.Level.E })
        assertTrue("no background retry: ${after.map { it.message }}", after.none { it.message.contains("retrying in the background") })
        // Still on the widget's home page (pressing Home would jump back to page 1).
        val priced = device.wait(Until.findObjects(By.pkg(ctx.packageName).text(pricePattern)), 10_000).orEmpty() +
            device.findObjects(By.pkg(ctx.packageName).desc(pricePattern))
        assertTrue("widget shows a price", priced.isNotEmpty())
    }

    @Test fun offlineTapWaitsThenRecovers() {
        ensureWidget()
        Thread.sleep(61_000)
        shell("svc wifi disable"); shell("svc data disable")
        Thread.sleep(4_000)
        val t0 = System.currentTimeMillis()
        tapWidgetRefresh()
        assertTrue("offline noted", waitForLog(t0, 10_000) { it.message.contains("No network") } != null)
        shell("svc wifi enable"); shell("svc data enable")
        assertTrue(
            "job started once back online",
            waitForLog(t0, 90_000) { it.tag == "Job" && it.message.contains("refresh button (offline)") } != null,
        )
        assertTrue("refresh ran by itself once back online", waitForLog(t0, 90_000, ::isQuotesFetched) != null)
    }

    @Test fun editorSavesRefreshButtonToggle() {
        val id = ensureWidget()
        runBlocking { Store(ctx).updateWidget(id) { it.copy(showRefresh = true) } }
        ctx.startActivity(Intent(ctx, ConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("editor open", device.wait(Until.hasObject(By.res("save")), 15_000))
        val scroller = device.findObject(By.scrollable(true))
        val toggle = scroller.scrollUntil(Direction.DOWN, Until.findObject(By.res("sw_refresh")))
        assertTrue("toggle found", toggle != null)
        toggle.click()
        device.findObject(By.res("save")).click()
        device.wait(Until.gone(By.res("save")), 10_000)
        assertTrue(!runBlocking { Store(ctx).widgetNow(id).showRefresh })
        runBlocking { Store(ctx).updateWidget(id) { it.copy(showRefresh = true) }; WidgetRenderer.renderAll(ctx) }
    }

    @Test fun editorRejectsWidgetIdsThatAreNotOurs() {
        for (bad in listOf(999_999, -5, 0)) {
            val intent = Intent(ctx, ConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, bad)
            ActivityScenario.launchActivityForResult<ConfigActivity>(intent).use { s ->
                assertEquals("id $bad", Activity.RESULT_CANCELED, s.result.resultCode)
            }
        }
        val before = runBlocking { Store(ctx).widgetNow(999_999) }
        assertEquals(WidgetConfig(), before) // nothing was created for it
    }

    @Test fun logsCopyPutsFullLogOnClipboard() {
        AppLog.i(ctx, "Test", "copy marker ${System.nanoTime()}")
        ActivityScenario.launch(LogsActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.res("logs_copy")), 10_000))
            device.findObject(By.res("logs_copy")).click()
            Thread.sleep(500)
            var clip = ""
            inst.runOnMainSync {
                clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            assertTrue(clip.take(80), clip.startsWith("Help Me Stocks v"))
            assertTrue(clip.contains("[INFO] Test: copy marker"))
            assertTrue("no keys in logs", !clip.contains("AIza"))
        }
    }
}
