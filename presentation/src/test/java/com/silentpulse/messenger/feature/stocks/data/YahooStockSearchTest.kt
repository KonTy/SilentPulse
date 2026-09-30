package com.silentpulse.messenger.feature.stocks.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CancellationException

class YahooStockSearchTest {
    @Test
    fun `search supports mutual funds indices stocks and currencies with identity metadata`() {
        val results = YahooStockSearch { """
            {"quotes":[
              {"symbol":"FXAIX","shortname":"Fidelity 500","longname":"Fidelity 500 Index Fund","quoteType":"MUTUALFUND","exchDisp":"NASDAQ","isYahooFinance":true},
              {"symbol":"^GSPC","shortname":"S&P 500","typeDisp":"Index","exchange":"SNP"},
              {"symbol":"AAPL","shortname":"Apple Inc.","quoteType":"EQUITY"},
              {"symbol":"EURUSD=X","shortname":"EUR/USD","quoteType":"CURRENCY"}
            ]}
        """ }.search("Fidelity")
        assertEquals(listOf("FXAIX", "^GSPC", "AAPL", "EURUSD=X"), results.map { it.symbol })
        assertEquals("Fidelity 500 Index Fund", results[0].name)
        assertEquals("MUTUALFUND", results[0].type)
        assertEquals("NASDAQ", results[0].exchange)
        assertEquals("SNP", results[1].exchange)
    }

    @Test
    fun `lookup uses only the whitelisted HTTPS endpoint and encodes the query without requesting news`() {
        var address = ""
        YahooStockSearch { address = it; """{"quotes":[]}""" }.search("Fidelity & S&P 500")
        val uri = URI(address)
        assertEquals("https", uri.scheme)
        assertEquals("query1.finance.yahoo.com", uri.host)
        assertEquals("/v1/finance/search", uri.path)
        val fields = uri.rawQuery.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals("Fidelity & S&P 500", fields["q"])
        assertEquals("0", fields["newsCount"])
        assertEquals("0", fields["listsCount"])
        assertEquals("30", fields["quotesCount"])
    }

    @Test
    fun `unsupported results are filtered and duplicate symbols do not create duplicate rows`() {
        val results = YahooStockSearch { """
            {"quotes":[
              {"symbol":"FXAIX","shortname":"Fidelity 500"},
              {"symbol":"FXAIX","shortname":"Duplicate"},
              {"symbol":"https://untrusted.example","shortname":"Not a ticker"},
              {"symbol":"ARTICLE","isYahooFinance":false},
              null
            ]}
        """ }.search("Fidelity")
        assertEquals(listOf("FXAIX"), results.map { it.symbol })
    }

    @Test
    fun `invalid input does not make a request and missing result arrays are errors not no matches`() {
        listOf("", " ", "$", "x".repeat(121), "a\nb").forEach { query ->
            assertThrows(IllegalArgumentException::class.java) {
                YahooStockSearch { fail("must not request"); "" }.search(query)
            }
        }
        listOf("{}", """{"quotes":null}""", "broken", """{"quotes":[{"symbol":123}]}""").forEach { body ->
            assertThrows(StockResponseException::class.java) { YahooStockSearch { body }.search("fund") }
        }
        assertTrue(YahooStockSearch { """{"quotes":[]}""" }.search("unknown").isEmpty())
    }

    @Test
    fun `transport errors and cancellation are not hidden as an empty result`() {
        val io = IOException("unavailable")
        assertSame(io, assertThrows(IOException::class.java) { YahooStockSearch { throw io }.search("fund") })
        val cancellation = CancellationException()
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            YahooStockSearch { throw cancellation }.search("fund")
        })
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                YahooStockSearch { fail("must not request"); "" }.search("fund")
            }
        } finally {
            Thread.interrupted()
        }
    }
}
