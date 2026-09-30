package com.silentpulse.messenger.feature.stocks

import org.junit.Assert.*
import org.junit.Test

class StockWidgetPreferencesTest {
    @Test
    fun `symbols preserve user order and deduplicate after normalization`() {
        assertEquals(listOf("MSFT", "AAPL", "^DJI", "GC=F", "BTC-USD", "BRK-B", "0700.HK"),
            StockWidgetPreferences.parseSymbols(" msft, AAPL\n^dji gc=f, BTC-USD, aapl BRK-B 0700.HK "))
    }

    @Test
    fun `invalid tokens reject the entire input rather than silently dropping symbols`() {
        listOf("", "  ", "AAPL, https://example.com", "MSFT ?bad", "Apple Inc.").forEach {
            assertNull(StockWidgetPreferences.parseSymbols(it))
        }
    }

    @Test
    fun `each widget retains its own display mode watchlist and refresh rate`() {
        val prefs = StockTestStore().widgets
        val first = StockWidgetSettings(listOf("AAPL"), multiline = true, dark = true, refreshMinutes = 60)
        val second = StockWidgetSettings(listOf("MSFT", "GC=F"), charts = false, twoColumns = false,
            dense = true, transparent = true, showCurrency = false)
        prefs.save(1, first)
        prefs.save(2, second)
        assertEquals(first, prefs.load(1))
        assertEquals(second, prefs.load(2))
        prefs.remove(1)
        assertNull(prefs.load(1))
        assertEquals(second, prefs.load(2))
    }

    @Test
    fun `restore snapshots overlapping ids without overwriting another widget`() {
        val prefs = StockTestStore().widgets
        val first = StockWidgetSettings(listOf("AAPL"), dense = true, transparent = true, showCurrency = false)
        val second = StockWidgetSettings(listOf("MSFT"), multiline = true, charts = false)
        prefs.save(1, first)
        prefs.save(2, second)
        prefs.restore(intArrayOf(1, 2), intArrayOf(2, 3))
        assertNull(prefs.load(1))
        assertEquals(first, prefs.load(2))
        assertEquals(second, prefs.load(3))
    }

    @Test
    fun `invalid stored settings require reconfiguration`() {
        val store = StockTestStore()
        store.values["1.symbols"] = "https://untrusted.example"
        assertNull(store.widgets.load(1))
        store.values["1.symbols"] = "AAPL"
        store.values["1.refresh"] = 1
        assertNull(store.widgets.load(1))
    }

    @Test
    fun `old watchlists keep their original layout background and currency labels`() {
        val store = StockTestStore()
        store.values["1.symbols"] = "AAPL"
        store.values["1.multiline"] = true
        store.values["1.dark"] = true
        val settings = store.widgets.load(1)!!
        assertTrue(settings.multiline)
        assertTrue(settings.dark)
        assertFalse(settings.dense)
        assertFalse(settings.transparent)
        assertTrue(settings.showCurrency)
    }

    @Test
    fun `deleting a widget removes its display settings but remembers its watchlist`() {
        val store = StockTestStore()
        store.widgets.save(1, StockWidgetSettings(listOf("AAPL"),
            dense = true, transparent = true, showCurrency = false))
        store.widgets.remove(1)
        assertEquals(setOf("remembered_watchlist"), store.values.keys)
        assertEquals("AAPL", store.widgets.rememberedWatchlist())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a widget cannot be both dense and multiline`() {
        StockTestStore().widgets.save(1, StockWidgetSettings(listOf("AAPL"), multiline = true, dense = true))
    }

    @Test
    fun `removed grouped watchlist is available to a new widget without reviving the old widget`() {
        val prefs = StockTestStore().widgets
        val groups = listOf(StockGroup("Stocks", listOf("AAPL")), StockGroup("Commodities", listOf("GC=F", "CL=F")))
        prefs.save(1, StockWidgetSettings(StockWatchlist.symbols(groups), groups = groups,
            groupStyle = StockGroupStyle.BORDERS, followAppTheme = false))
        prefs.remove(1)
        assertNull(prefs.load(1))
        assertEquals(groups, StockWatchlist.parse(prefs.rememberedWatchlist()))
        assertNull(prefs.load(2))
    }

    @Test
    fun `groups decoration and app theme choice survive restore`() {
        val prefs = StockTestStore().widgets
        val groups = listOf(StockGroup("Bonds", listOf("BND", "ZN=F")))
        val settings = StockWidgetSettings(StockWatchlist.symbols(groups), groups = groups,
            groupStyle = StockGroupStyle.BORDERS, followAppTheme = false)
        prefs.save(1, settings)
        prefs.restore(intArrayOf(1), intArrayOf(2))
        assertEquals(settings, prefs.load(2))
    }

    @Test
    fun `existing literal ticker is not silently changed into a commodity alias`() {
        val store = StockTestStore()
        store.values["1.symbols"] = "CORN"
        assertEquals(listOf("CORN"), store.widgets.load(1)!!.symbols)
        store.widgets.remove(1)
        assertEquals(listOf(StockGroup("", listOf("CORN"))), StockWatchlist.parse(store.widgets.rememberedWatchlist()))
    }
}
