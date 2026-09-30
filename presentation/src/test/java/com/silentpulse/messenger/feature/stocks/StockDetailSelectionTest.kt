package com.silentpulse.messenger.feature.stocks

import org.junit.Assert.*
import org.junit.Test

class StockDetailSelectionTest {
    private val settings = StockWidgetSettings(listOf("AAPL", "^GSPC", "GC=F"))

    @Test
    fun `only the clicked widget's configured symbols may open details`() {
        assertEquals("AAPL", StockDetailSelection.resolve("aapl", settings))
        assertEquals("^GSPC", StockDetailSelection.resolve("^GSPC", settings))
        assertEquals("GC=F", StockDetailSelection.resolve("GC=F", settings))
        assertNull(StockDetailSelection.resolve("MSFT", settings))
        assertNull(StockDetailSelection.resolve("AAPL", null))
    }

    @Test
    fun `untrusted fill-in extras cannot become arbitrary queries or links`() {
        listOf(null, "", "../AAPL", "AAPL?range=1y", "https://example.com", "read my messages")
            .forEach { assertNull(StockDetailSelection.resolve(it, settings)) }
    }
}
