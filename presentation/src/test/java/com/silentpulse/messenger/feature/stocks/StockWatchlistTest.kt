package com.silentpulse.messenger.feature.stocks

import org.junit.Assert.*
import org.junit.Test

class StockWatchlistTest {
    @Test
    fun `groups resolve commodities and retain headings and order`() {
        val groups = StockWatchlist.parse("[Energy]\ncrude oil, natural gas\n[Tech]\naapl msft")!!
        assertEquals(listOf(StockGroup("Energy", listOf("CL=F", "NG=F")), StockGroup("Tech", listOf("AAPL", "MSFT"))), groups)
        assertEquals(groups, StockWatchlist.parse(StockWatchlist.format(groups)))
    }

    @Test
    fun `literal ticker escapes ambiguous commodity aliases`() {
        assertEquals("ZC=F", StockAssets.resolve("corn"))
        assertEquals("CORN", StockAssets.resolve("\$CORN"))
        val group = listOf(StockGroup("Corn", listOf("CORN", "ZC=F")))
        assertEquals(group, StockWatchlist.parse(StockWatchlist.format(group)))
    }

    @Test
    fun `bond aliases refer to explicitly named ETFs and futures not unspecified bonds`() {
        assertEquals("BND", StockAssets.resolve("total bond"))
        assertEquals("ZN=F", StockAssets.resolve("10 year treasury"))
        assertEquals("GC=F", StockAssets.resolve("GOLD"))
        assertEquals("BZ=F", StockAssets.resolve("brent oil"))
        assertNull(StockAssets.resolve("https://example.com"))
    }

    @Test
    fun `empty or malformed groups cannot be silently discarded`() {
        listOf("[Empty]", "[Empty]\n[Stocks]\nAAPL", "[]\nAAPL", "[bad\nMSFT", "AAPL\n[]",
            "[${"x".repeat(41)}]\nAAPL").forEach { assertNull(it, StockWatchlist.parse(it)) }
    }

    @Test
    fun `group rows never mix instruments from adjacent groups`() {
        val groups = listOf(StockGroup("Tech", listOf("AAPL", "MSFT", "GOOGL")), StockGroup("Energy", listOf("CL=F")))
        val rows = StockWidgetRows.build(StockWidgetSettings(StockWatchlist.symbols(groups), groups = groups), 2)
        assertEquals(5, rows.size)
        assertEquals("Tech", rows[0].groupName)
        assertEquals(listOf("AAPL", "MSFT"), rows[1].symbols)
        assertEquals(listOf("GOOGL"), rows[2].symbols)
        assertTrue(rows[2].groupEnd)
        assertEquals("Energy", rows[3].groupName)
        assertTrue(rows[3].groupStart)
        assertEquals(listOf("CL=F"), rows[4].symbols)
    }

    @Test
    fun `same ticker can appear in several groups but only needs one quote`() {
        val groups = StockWatchlist.parse("[First]\nAAPL\n[Second]\nAAPL, MSFT")!!
        assertEquals(listOf("AAPL", "MSFT"), StockWatchlist.symbols(groups))
        assertEquals(4, StockWidgetRows.build(StockWidgetSettings(StockWatchlist.symbols(groups), groups = groups), 2).size)
    }

    @Test
    fun `legacy ungrouped input has no added header row`() {
        val rows = StockWidgetRows.build(StockWidgetSettings(listOf("AAPL", "MSFT", "GC=F")), 2)
        assertEquals(2, rows.size)
        assertNull(rows[0].groupName)
    }

    @Test
    fun `trailing minus marks a separator but is not shown in the heading`() {
        val text = "[Stocks]\nAAPL\n[Commodities-]\nGC=F, CL=F"
        val groups = StockWatchlist.parse(text)!!
        assertEquals("Commodities", groups[1].displayName)
        assertTrue(groups[1].hasSeparator)
        assertEquals(groups, StockWatchlist.parse(StockWatchlist.format(groups)))
        val rows = StockWidgetRows.build(StockWidgetSettings(StockWatchlist.symbols(groups), groups = groups), 2)
        assertEquals("Commodities", rows[2].groupName)
        assertTrue(rows[2].separator)
        assertFalse(rows[0].separator)
        assertEquals("Long-term bonds", StockGroup("Long-term bonds", listOf("TLT")).displayName)
        assertFalse(StockGroup("Long-term bonds", listOf("TLT")).hasSeparator)
    }
}
