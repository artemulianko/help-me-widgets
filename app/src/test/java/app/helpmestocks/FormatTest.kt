package app.helpmestocks

import app.helpmestocks.data.ChangeMode
import app.helpmestocks.data.Fmt
import app.helpmestocks.data.Quote
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {
    private val f = Fmt(null, Locale.US)
    private fun q(price: Double, change: Double, pct: Double) = Quote("X", price, change, pct, null, 0, 1, fetchedAt = 0)

    @Test fun pricesUseGroupingAndPrecisionByMagnitude() {
        assertEquals("47,210.55", f.price(47210.55))
        assertEquals("354.17", f.price(354.165))
        assertEquals("0.4521", f.price(0.45213))
        assertEquals("0.00001230", f.price(0.0000123))
    }

    @Test fun changesUseSignsAndTypographicMinus() {
        assertEquals("+0.40%", f.change(q(354.19, 1.41, 0.4), ChangeMode.PERCENT))
        assertEquals("−1.62%", f.change(q(509.62, -8.37, -1.616), ChangeMode.PERCENT))
        assertEquals("−8.37", f.change(q(509.62, -8.37, -1.616), ChangeMode.PRICE))
        assertEquals("0.00%", f.change(q(10.0, 0.0, 0.0), ChangeMode.PERCENT))
    }

    @Test fun tinyPriceChangesKeepSignificantDigits() {
        assertEquals("\u22120.00000012", f.change(q(0.00001234, -0.00000012, -0.96), ChangeMode.PRICE))
        assertEquals("0.00%", f.change(q(10.0, 0.0001, 0.001), ChangeMode.PERCENT))
    }

    @Test fun directionTreatsTinyMovesAsFlat() {
        assertEquals(1, Fmt.direction(q(1.0, 0.5, 1.0)))
        assertEquals(-1, Fmt.direction(q(1.0, -0.5, -1.0)))
        assertEquals(0, Fmt.direction(q(1.0, 0.001, 0.001)))
    }

    @Test fun pricesWithCurrencyFollowLocale() {
        assertEquals("€166.54", f.price(166.54, "EUR"))
        assertEquals("$60.18", f.price(60.18, "USD"))
        assertEquals("116.60p", f.price(116.6, "GBp"))
        assertEquals("166,54\u00a0€", Fmt(null, Locale.GERMANY).price(166.54, "EUR"))
        assertEquals("1.00 XYZ1", f.price(1.0, "XYZ1"))
        assertEquals("60.18", f.price(60.18, null))
    }

    @Test fun signsSurviveNonAsciiDigits() {
        val ar = Fmt(null, Locale.forLanguageTag("ar-EG"))
        assertEquals(true, ar.percent(1.5).startsWith("+"))
        assertEquals(true, ar.percent(-1.5).startsWith("\u2212"))
    }

    @Test fun localeDecimalSeparators() {
        assertEquals("1.234,50", Fmt(null, Locale.GERMANY).price(1234.5))
    }
}
