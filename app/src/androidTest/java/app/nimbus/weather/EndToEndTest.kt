package app.nimbus.weather

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.nimbus.weather.data.Locations
import app.nimbus.weather.data.OpenMeteoSource
import app.nimbus.weather.data.Place
import app.nimbus.weather.data.Store
import app.nimbus.weather.data.TempUnit
import app.nimbus.weather.data.WeatherRepository
import app.nimbus.weather.ui.MainActivity
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
        val before = device.findObjects(By.textEndsWith("°")).map { it.text }
        device.findObject(By.scrollable(true))?.scrollUntil(androidx.test.uiautomator.Direction.DOWN, Until.findObject(By.res("unit_2")))
        device.findObject(By.res("unit_2"))?.click()
        device.waitForIdle()
        assertEquals(TempUnit.FAHRENHEIT, runBlocking { Store(ctx).appNow().tempUnit })
        // Reopen at the top and read the preview again.
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("widget_preview")), 15_000))
        Thread.sleep(1500) // preview renders after a short debounce
        val after = device.findObjects(By.textEndsWith("°")).map { it.text }
        assertTrue("before=$before after=$after", before != after && after.isNotEmpty())
        runBlocking { Store(ctx).updateApp { it.copy(tempUnit = TempUnit.SYSTEM) } }
    }
}
