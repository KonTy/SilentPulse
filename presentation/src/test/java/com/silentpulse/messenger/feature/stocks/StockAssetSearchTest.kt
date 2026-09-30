package com.silentpulse.messenger.feature.stocks

import com.silentpulse.messenger.R
import org.junit.Assert.*
import org.junit.Test

class StockAssetSearchTest {
    private fun symbols(query: String) = StockAssets.search(query) { "" }.map { it.symbol }

    @Test
    fun `filter accepts names aliases partial names and literal ticker symbols`() {
        assertEquals(listOf("GC=F"), symbols("GOLD"))
        assertEquals(listOf("CL=F"), symbols("crude"))
        assertEquals(listOf("CL=F"), symbols("  wTi  "))
        assertEquals(listOf("NG=F"), symbols("gas"))
        assertEquals(listOf("ZN=F"), symbols("zn=f"))
        assertEquals(listOf("ZN=F"), symbols("\$ZN=F"))
        assertEquals(listOf("ZN=F"), symbols("treasury 10"))
    }

    @Test
    fun `filter also searches localized display labels`() {
        val results = StockAssets.search("oro") {
            if (it == R.string.stock_asset_gold) "Futuros de oro" else ""
        }
        assertEquals(listOf("GC=F"), results.map { it.symbol })
    }

    @Test
    fun `clearing the filter restores catalog order and unknown text has no matches`() {
        assertEquals(StockAssets.entries, StockAssets.search(" \n ") { "" })
        assertTrue(symbols("nonexistent instrument").isEmpty())
        assertTrue(symbols("https://example.com").isEmpty())
    }

    @Test
    fun `major index aliases and arbitrary returned tickers can be added accurately`() {
        assertEquals(listOf("^GSPC"), symbols("snp500"))
        assertEquals("^GSPC", StockAssets.resolve("s&p500"))
        assertEquals("^DJI", StockAssets.resolve("dow jones"))
        assertEquals("^IXIC", StockAssets.resolve("nasdaq"))
        for (symbol in listOf("FXAIX", "FZROX", "EURUSD=X", "CORN", "GOLD", "^GSPC")) {
            assertEquals(symbol, StockAssets.resolve(StockAssets.editorSymbol(symbol)))
        }
    }
}
