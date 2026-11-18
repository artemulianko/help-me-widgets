package app.helpmeclock

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
import app.helpmeclock.data.AppLog
import app.helpmeclock.data.Store
import app.helpmeclock.data.WidgetConfig
import app.helpmeclock.ui.ConfigActivity
import app.helpmeclock.ui.LogsActivity
import app.helpmeclock.ui.MainActivity
import app.helpmeclock.widget.ClockWidgetReceiver
import app.helpmeclock.widget.WidgetRenderer
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end flows through the real launcher, widget and clock app:
 * pin a widget → set an alarm in the clock app → the widget shows it → tapping it opens the
 * clock app; tapping the time opens the clock app; editor saves; editor rejects foreign widget
 * ids; logs copy to the clipboard.
 *
 * Leaves one enabled alarm in the emulator's clock app (labelled "HelpMeClockTest", reused
 * across runs) at 4:44.
 */
@RunWith(AndroidJUnit4::class)
class WidgetFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val mgr = AppWidgetManager.getInstance(ctx)
    private val provider = ComponentName(ctx, ClockWidgetReceiver::class.java)

    /** The time as TextClock prints it: "11:42", "23:42", "9.05". */
    private val timePattern = Pattern.compile("\\d{1,2}[:.]\\d{2}")

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).close()
    private fun ids() = mgr.getAppWidgetIds(provider).toList()

    @Before fun setUp() {
        device.wakeUp()
    }

    @After fun tearDown() {
        device.pressHome()
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

    /** Goes home and swipes through pages until [find] sees the widget. */
    private fun onHomeFind(find: () -> UiObject2?): UiObject2? {
        device.pressHome(); device.waitForIdle(); Thread.sleep(1_500)
        var o = find()
        repeat(4) {
            if (o == null) {
                device.swipe(device.displayWidth * 9 / 10, device.displayHeight / 2, device.displayWidth / 10, device.displayHeight / 2, 20)
                Thread.sleep(1_500)
                o = find()
            }
        }
        if (o == null) shell("screencap -p /data/local/tmp/widgetflow_fail.png")
        return o
    }

    private fun foregroundPackage(): String = device.currentPackageName.orEmpty()

    /**
     * The alarm is set through the clock app's public SET_ALARM intent, exactly like a user's
     * alarm. Sent from the shell: the app itself deliberately lacks the SET_ALARM permission.
     */
    private fun setTestAlarm() {
        shell("am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR 4 " +
            "--ei android.intent.extra.alarm.MINUTES 44 --es android.intent.extra.alarm.MESSAGE HelpMeClockTest " +
            "--ez android.intent.extra.alarm.SKIP_UI true")
    }

    @Test fun alarmFromClockAppAppearsAndOpensIt() {
        ensureWidget()
        val t0 = System.currentTimeMillis()
        setTestAlarm()
        // The system wakes the app with NEXT_ALARM_CLOCK_CHANGED; the receiver logs and re-renders.
        // (If 4:44 was already the next alarm, nothing changes; then the app-open render covers it.)
        val logged = waitForLog(t0, 15_000) { it.tag == "Alarm" && it.message.contains("(shown)") }
        if (logged == null) runBlocking { WidgetRenderer.renderAll(ctx) }
        val alarm = onHomeFind { device.findObject(By.pkg(ctx.packageName).descStartsWith("Next alarm")) }
        assertTrue("widget shows the next alarm", alarm != null)
        alarm!!.click()
        assertTrue(
            "tapping the alarm opens the clock app, got ${foregroundPackage()}",
            device.wait(Until.hasObject(By.pkg(app.helpmeclock.data.Alarms.clockPackage(ctx)!!)), 10_000),
        )
    }

    @Test fun tappingTheTimeOpensTheClockApp() {
        val id = ensureWidget()
        runBlocking { Store(ctx).updateWidget(id) { it.copy(tapAction = app.helpmeclock.data.TapAction.CLOCK) }; WidgetRenderer.renderAll(ctx) }
        val time = onHomeFind { device.findObject(By.pkg(ctx.packageName).text(timePattern)) }
        assertTrue("widget shows the time", time != null)
        time!!.click()
        assertTrue(
            "tapping the time opens the clock app, got ${foregroundPackage()}",
            device.wait(Until.hasObject(By.pkg(app.helpmeclock.data.Alarms.clockPackage(ctx)!!)), 10_000),
        )
    }

    @Test fun editorSavesDateToggle() {
        val id = ensureWidget()
        runBlocking { Store(ctx).updateWidget(id) { it.copy(showDate = true) } }
        ctx.startActivity(Intent(ctx, ConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("editor open", device.wait(Until.hasObject(By.res("save")), 15_000))
        var toggle = device.findObject(By.res("sw_date"))
        repeat(8) {
            if (toggle == null) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight * 2 / 5, 20)
                Thread.sleep(300)
                toggle = device.findObject(By.res("sw_date"))
            }
        }
        assertTrue("toggle found", toggle != null)
        toggle.click()
        device.findObject(By.res("save")).click()
        device.wait(Until.gone(By.res("save")), 10_000)
        assertTrue(!runBlocking { Store(ctx).widgetNow(id).showDate })
        runBlocking { Store(ctx).updateWidget(id) { it.copy(showDate = true) }; WidgetRenderer.renderAll(ctx) }
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
            assertTrue(clip.take(80), clip.startsWith("Help Me Clock v"))
            assertTrue(clip.contains("[INFO] Test: copy marker"))
            assertTrue("no keys in logs", !clip.contains("AIza"))
        }
    }
}
