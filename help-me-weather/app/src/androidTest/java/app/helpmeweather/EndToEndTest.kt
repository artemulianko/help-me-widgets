package app.helpmeweather

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.helpmeweather.data.Locations
import app.helpmeweather.data.OpenMeteoSource
import app.helpmeweather.data.Place
import app.helpmeweather.data.Store
import app.helpmeweather.data.TempUnit
import app.helpmeweather.data.WeatherRepository
import app.helpmeweather.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hits the real network and drives the real app. Needs an emulator/device with internet. */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun openMeteoReturnsAFullForecast() = runBlocking {
        val w = OpenMeteoSource().fetch(Place("Kyiv", 50.45, 30.52))
        assertEquals("Europe/Kyiv", w.zoneId)
        assertTrue("hourly=${w.hourly.size}", w.hourly.size >= 24)
        assertTrue("daily=${w.daily.size}", w.daily.size >= 7)
        assertTrue(w.current.tempC in -60.0..60.0)
    }

    @Test fun citySearchFindsPlaces() = runBlocking {
        val r = Locations.search("Lviv")
        assertTrue(r.isNotEmpty())
        assertTrue(r.first().region.orEmpty().contains("Ukraine") || r.any { it.region.orEmpty().contains("Ukraine") })
    }

    @Test fun refreshFillsCacheForDevicePlace() = runBlocking {
        val store = Store(ctx)
        val place = Place("Mountain View", 37.42, -122.08)
        store.updateApp { it.copy(deviceLocation = place, deviceLocationAt = System.currentTimeMillis()) }
        val r = WeatherRepository.refresh(ctx, force = true)
        assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
        assertNotNull(store.weatherNow(place))
    }

    @Test fun appShowsLivePreviewAndUnitSwitchWorks() {
        runBlocking { Store(ctx).updateApp { it.copy(tempUnit = TempUnit.CELSIUS) } }
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("widget_preview")), 15_000))
        assertTrue(device.wait(Until.hasObject(By.text("Mountain View").pkg(ctx.packageName)), 10_000) || device.hasObject(By.textContains("°")))

        // Switch to °F from the Units group and confirm the preview's numbers change.
        // The preview is drawn off the main thread, a moment after the screen: wait for it.
        device.wait(Until.hasObject(By.textEndsWith("°").pkg(ctx.packageName)), 15_000)
        val before = device.findObjects(By.textEndsWith("°")).map { it.text }
        var unitF = device.findObject(By.res("unit_2"))
        repeat(10) {
            if (unitF == null || unitF!!.visibleBounds.bottom > device.displayHeight - 300) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 2, 15)
                unitF = device.findObject(By.res("unit_2"))
            }
        }
        assertTrue("°F button found", unitF != null)
        unitF!!.click()
        device.waitForIdle()
        // The setting is saved asynchronously; poll briefly.
        val savedBy = System.currentTimeMillis() + 5_000
        while (runBlocking { Store(ctx).appNow().tempUnit } != TempUnit.FAHRENHEIT && System.currentTimeMillis() < savedBy) Thread.sleep(200)
        assertEquals(TempUnit.FAHRENHEIT, runBlocking { Store(ctx).appNow().tempUnit })
        // Reopen at the top and read the preview again.
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("widget_preview")), 15_000))
        // Wait until the redrawn preview shows °F numbers (drawn off the main thread).
        val by = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < by && device.findObjects(By.textEndsWith("°")).map { it.text }.let { it.isEmpty() || it == before }) Thread.sleep(300)
        val after = device.findObjects(By.textEndsWith("°")).map { it.text }
        assertTrue("before=$before after=$after", before != after && after.isNotEmpty())
        runBlocking { Store(ctx).updateApp { it.copy(tempUnit = TempUnit.SYSTEM) } }
    }
}
