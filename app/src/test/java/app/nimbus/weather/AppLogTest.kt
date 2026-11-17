package app.nimbus.weather

import app.nimbus.weather.data.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLogTest {
    @Test fun redactsApiKeysAnywhere() {
        val fake = "AIza" + "SyD-ThisIsNotARealKey_1234567890ab"
        val msg = "GET https://weather.googleapis.com/v1/x?key=$fake&location.latitude=1 failed; raw $fake"
        val out = AppLog.redact(msg)
        assertFalse(out, out.contains("AIza"))
        assertFalse(out, out.contains("NotARealKey"))
        assertEquals(true, out.contains("key=•••"))
    }

    @Test fun leavesNormalTextAlone() {
        assertEquals("Mountain View: Google · 13.6°C mostly clear · 412 ms", AppLog.redact("Mountain View: Google · 13.6°C mostly clear · 412 ms"))
    }
}
