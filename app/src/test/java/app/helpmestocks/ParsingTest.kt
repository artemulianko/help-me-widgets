package app.helpmestocks

import app.helpmestocks.data.Http
import app.helpmestocks.data.YahooFinance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    private val spark = """
        {"AAPL":{"timestamp":[1794925800,1794926100,1794926400,1794926700],"end":1794949200,"symbol":"AAPL",
          "fulldayChange":1.41,"fulldayChangePercent":0.4,"fulldayPrice":354.19,"dataGranularity":300,
          "close":[352.399,null,352.815,354.19],"previousClose":352.78,"chartPreviousClose":352.78,"start":1794925800},
         "BTC-USD":{"timestamp":[1794873600,1794873900],"end":1794959940,"symbol":"BTC-USD","close":[64828.0,65005.07],
          "chartPreviousClose":64828.375,"start":1794873600}}
    """.trimIndent()

    @Test fun parsesSparkBatch() {
        val qs = YahooFinance.parseSpark(Http.json.parseToJsonElement(spark), now = 1L).associateBy { it.symbol }
        val a = qs.getValue("AAPL")
        assertEquals(354.19, a.price, 1e-9)
        assertEquals(1.41, a.change, 1e-9)
        assertEquals(0.4, a.changePct, 1e-9)
        assertEquals(352.78, a.previousClose!!, 1e-9)
        assertEquals(1794925800L, a.sessionStart)
        assertEquals(1794949200L, a.sessionEnd)
        // The null price is dropped together with its timestamp.
        assertEquals(listOf(1794925800L, 1794926400L, 1794926700L), a.times)
        assertEquals(3, a.prices.size)
    }

    @Test fun derivesChangeWhenProviderOmitsIt() {
        val b = YahooFinance.parseSpark(Http.json.parseToJsonElement(spark), now = 1L).first { it.symbol == "BTC-USD" }
        assertEquals(65005.07, b.price, 1e-9)
        assertEquals(65005.07 - 64828.375, b.change, 1e-6)
        assertEquals((65005.07 - 64828.375) / 64828.375 * 100, b.changePct, 1e-6)
    }

    @Test fun surfacesProviderErrors() {
        val err = """{"spark":{"result":null,"error":{"code":"Bad Request","description":"Number of symbols needs to be less than or equal to 20"}}}"""
        val e = assertThrows(java.io.IOException::class.java) { YahooFinance.parseSpark(Http.json.parseToJsonElement(err), 1L) }
        assertTrue(e.message!!.contains("20"))
    }

    @Test fun skipsMalformedEntries() {
        val bad = """{"X":{"symbol":"X"},"Y":"nope","Z":{"timestamp":[1,2],"close":[null,null]}}"""
        assertEquals(0, YahooFinance.parseSpark(Http.json.parseToJsonElement(bad), 1L).size)
    }

    @Test fun parsesSearchAndFiltersTypes() {
        val json = """{"quotes":[
            {"exchange":"NMS","shortname":"NVIDIA Corporation","quoteType":"EQUITY","symbol":"NVDA","longname":"NVIDIA Corporation","exchDisp":"NASDAQ"},
            {"exchange":"OPR","shortname":"NVDA Call","quoteType":"OPTION","symbol":"NVDA261218C00200000"},
            {"exchange":"SNP","shortname":"S&P 500","quoteType":"INDEX","symbol":"^GSPC","exchDisp":"SNP"},
            {"quoteType":"CURRENCY","symbol":"EURUSD=X","shortname":"EUR/USD"},
            {"quoteType":"CRYPTOCURRENCY","symbol":"BTC-USD","shortname":"Bitcoin USD"}
        ]}"""
        val r = YahooFinance.parseSearch(Http.json.parseToJsonElement(json))
        assertEquals(listOf("NVDA", "^GSPC", "EURUSD=X", "BTC-USD"), r.map { it.symbol })
        assertEquals(listOf("NVDA", "S&P 500", "EUR/USD", "BTC-USD"), r.map { it.label })
        assertEquals("NASDAQ", r[0].exchange)
        assertEquals(null, r[1].exchange) // indices have no listing exchange
    }

    @Test fun nonUsListingsDropTheExchangeSuffixFromTheLabel() {
        assertEquals("VWCE", YahooFinance.labelFor("VWCE.DE", "ETF", null))
        assertEquals("VOD", YahooFinance.labelFor("VOD.L", "EQUITY", null))
        assertEquals("BRK-B", YahooFinance.labelFor("BRK-B", "EQUITY", null))
        assertEquals("BTC-USD", YahooFinance.labelFor("BTC-USD", "CRYPTOCURRENCY", null))
        assertEquals("ES=F", YahooFinance.labelFor("ES=F", "FUTURE", null))
    }
}
