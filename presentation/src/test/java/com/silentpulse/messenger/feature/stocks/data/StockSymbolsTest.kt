package com.silentpulse.messenger.feature.stocks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class StockSymbolsTest {
    @Test
    fun `supported ticker forms normalize independently of default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            listOf(" aapl ", "brk-b", "btc-usd", "^dji", "gc=f", "0700.hk", "vod.l", "f", "fxaix", "eurusd=x")
                .forEach { raw ->
                    assertEquals(raw.trim().uppercase(Locale.ROOT), StockSymbols.normalize(raw))
                }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `URLs separators whitespace punctuation unicode and unbounded values are rejected`() {
        listOf(
            "", " ", "AAPL/MSFT", "../AAPL", "AAPL..L", "AAPL?range=5d", "AAPL#fragment",
            "AAPL&interval=1d", "AAPL%2FMSFT", "user@host", "https://example.com", "AAPL\\MSFT",
            "AA PL", "AA\nPL", "-AAPL", "AAPL-", ".AAPL", "AAPL.", "^^DJI", "DJ^I", "=",
            "GC=F=F", "GC=FOO", "İBM", "ß", "ＡＡＰＬ", "A".repeat(25), "AAPL\u0000"
        ).forEach { raw ->
            assertNull(raw, StockSymbols.normalize(raw))
        }
    }
}
