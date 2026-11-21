package app.helpmecalendar

import app.helpmecalendar.data.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLogTest {
    @Test fun redactsApiKeysAnywhere() {
        val fake = "AIza" + "SyD-ThisIsNotARealKey_1234567890ab"
        val msg = "GET https://example.com/v1/x?key=$fake&symbols=AAPL failed; raw $fake"
        val out = AppLog.redact(msg)
        assertFalse(out, out.contains("AIza"))
        assertFalse(out, out.contains("NotARealKey"))
        assertEquals(true, out.contains("key=•••"))
    }

    @Test fun leavesNormalTextAlone() {
        assertEquals("AAPL, MSFT: Yahoo · 2 quotes · 412 ms", AppLog.redact("AAPL, MSFT: Yahoo · 2 quotes · 412 ms"))
    }
}
