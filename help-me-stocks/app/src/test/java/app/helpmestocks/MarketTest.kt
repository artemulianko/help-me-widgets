package app.helpmestocks

import app.helpmestocks.data.Market
import app.helpmestocks.data.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneOffset

class MarketTest {
    private val maxAge = 22 * 60_000L
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toEpochSecond()

    // NYSE regular session, Friday 2026-11-13, 14:30–21:00 UTC.
    private val friOpen = utc(2026, 11, 13, 14, 30)
    private val friClose = utc(2026, 11, 13, 21, 0)
    private fun nyse(fetchedSec: Long, start: Long = friOpen, end: Long = friClose) =
        Quote("AAPL", 1.0, 0.0, 0.0, 1.0, start, end, fetchedAt = fetchedSec * 1000)

    @Test fun refreshesWhileOpenOnlyWhenStale() {
        val q = nyse(utc(2026, 11, 13, 16, 0))
        assertFalse(Market.needsFetch(q, utc(2026, 11, 13, 16, 10) * 1000, maxAge))
        assertTrue(Market.needsFetch(q, utc(2026, 11, 13, 16, 30) * 1000, maxAge))
    }

    @Test fun fetchesOnceMoreAfterCloseForFinalPrices() {
        val q = nyse(utc(2026, 11, 13, 20, 50))
        assertTrue(Market.needsFetch(q, utc(2026, 11, 13, 21, 15) * 1000, maxAge))
    }

    @Test fun noNetworkOvernightOrOverTheWeekend() {
        val q = nyse(utc(2026, 11, 13, 21, 30)) // final numbers captured
        assertFalse(Market.needsFetch(q, utc(2026, 11, 14, 2, 0) * 1000, maxAge))   // Friday night
        assertFalse(Market.needsFetch(q, utc(2026, 11, 14, 15, 0) * 1000, maxAge))  // Saturday
        assertFalse(Market.needsFetch(q, utc(2026, 11, 15, 15, 0) * 1000, maxAge))  // Sunday
        assertFalse(Market.needsFetch(q, utc(2026, 11, 16, 13, 10) * 1000, maxAge)) // Monday, before 13:20
        assertTrue(Market.needsFetch(q, utc(2026, 11, 16, 13, 21) * 1000, maxAge))  // Monday, from 70 min before open
        assertEquals(utc(2026, 11, 16, 14, 30), Market.nextExpectedOpen(q))
    }

    @Test fun keepsCheckingIfNewSessionIsLateThenGivesUp() {
        // Fetched just before Monday's open but still got Friday's session (pre-open / holiday).
        val q = nyse(utc(2026, 11, 16, 14, 25))
        assertTrue(Market.needsFetch(q, utc(2026, 11, 16, 15, 0) * 1000, maxAge))
        // Holiday: after the grace window it waits for Tuesday.
        val late = nyse(utc(2026, 11, 16, 20, 45))
        assertEquals(utc(2026, 11, 17, 14, 30), Market.nextExpectedOpen(late))
        assertFalse(Market.needsFetch(late, utc(2026, 11, 16, 22, 0) * 1000, maxAge))
    }

    @Test fun haltedSymbolWithAWeekOldSessionStillSleepsAtNight() {
        // Last session Friday 13 Nov, still the latest one when fetched Wednesday 25 Nov (halted,
        // delisted or a long holiday). It used to compute a next open in the past and fetch 24/7.
        val q = nyse(utc(2026, 11, 25, 22, 0))
        assertEquals(utc(2026, 11, 26, 14, 30), Market.nextExpectedOpen(q))
        assertFalse(Market.needsFetch(q, utc(2026, 11, 26, 3, 0) * 1000, maxAge)) // Thursday night
        assertTrue(Market.needsFetch(q, utc(2026, 11, 26, 13, 30) * 1000, maxAge)) // shortly before the open
    }

    @Test fun tokyoAndSydneyWeekdaysUseExchangeDate() {
        // Tokyo Monday session 00:00–06:30 UTC; fetched after Friday's close → next is Monday.
        val tFri = Quote("7203.T", 1.0, 0.0, 0.0, 1.0, utc(2026, 11, 13, 0, 0), utc(2026, 11, 13, 6, 30), fetchedAt = utc(2026, 11, 13, 7, 0) * 1000)
        assertEquals(utc(2026, 11, 16, 0, 0), Market.nextExpectedOpen(tFri))
        // Sydney Friday session starts Thursday 23:00 UTC; next is Sunday 23:00 UTC (Monday local).
        val sFri = Quote("BHP.AX", 1.0, 0.0, 0.0, 1.0, utc(2026, 11, 12, 23, 0), utc(2026, 11, 13, 5, 0), fetchedAt = utc(2026, 11, 13, 6, 0) * 1000)
        assertEquals(utc(2026, 11, 15, 23, 0), Market.nextExpectedOpen(sFri))
    }

    @Test fun cryptoIsAlwaysOpen() {
        val start = utc(2026, 11, 14, 0, 0)
        val q = Quote("BTC-USD", 1.0, 0.0, 0.0, 1.0, start, start + 86_340, fetchedAt = (start + 3600) * 1000)
        assertTrue(Market.isOpen(q, start + 7200))
        assertTrue(Market.needsFetch(q, (start + 3600) * 1000 + maxAge, maxAge))
    }

    @Test fun daylightSavingShiftIsCaught() {
        // Friday session cached before US spring-forward; Monday's real open is 13:30 UTC, an hour
        // earlier than Friday's 14:30. Checking starts at 13:20 UTC, so the new session is seen.
        val q = nyse(utc(2026, 3, 6, 21, 30), start = utc(2026, 3, 6, 14, 30), end = utc(2026, 3, 6, 21, 0))
        assertTrue(Market.needsFetch(q, utc(2026, 3, 9, 13, 25) * 1000, maxAge))
    }

    @Test fun staleCryptoAlwaysRefreshesEvenOnWeekends() {
        val start = utc(2026, 11, 13, 0, 0) // Friday's UTC day
        val q = Quote("BTC-USD", 1.0, 0.0, 0.0, 1.0, start, start + 86_340, fetchedAt = (start + 86_400 + 3600) * 1000)
        assertTrue(Market.needsFetch(q, utc(2026, 11, 14, 12, 0) * 1000, maxAge)) // Saturday
    }

    @Test fun missingQuoteAlwaysFetches() = assertTrue(Market.needsFetch(null, 0, maxAge))
}
