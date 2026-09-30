package com.silentpulse.messenger.feature.stocks

import org.junit.Assert.*
import org.junit.Test

class StockWidgetAppearanceTest {
    @Test
    fun `currency change persists immediately without changing the verified watchlist`() {
        val store = StockTestStore()
        val groups = listOf(StockGroup("Tech", listOf("AAPL")), StockGroup("Energy", listOf("CL=F")))
        val original = StockWidgetSettings(StockWatchlist.symbols(groups), groups = groups,
            dense = true, transparent = true, groupStyle = StockGroupStyle.BORDERS)
        store.widgets.save(1, original)
        val appearance = StockWidgetAppearance.from(original).copy(showCurrency = false)
        assertTrue(store.widgets.updateAppearance(1, appearance))

        val reopened = StockWidgetPreferences(store.preferences).load(1)!!
        assertEquals(original.copy(showCurrency = false), reopened)
        assertEquals(groups, StockWatchlist.parse(store.widgets.rememberedWatchlist()))
        assertFalse(store.widgets.updateAppearance(1, appearance))
    }

    @Test
    fun `appearance edits never create an unverified new widget`() {
        val store = StockTestStore()
        val appearance = StockWidgetAppearance.from(StockWidgetSettings(listOf("AAPL"))).copy(showCurrency = false)
        assertFalse(store.widgets.updateAppearance(7, appearance))
        assertNull(store.widgets.load(7))
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `restoring unchanged controls does not overwrite the last-used watchlist`() {
        val store = StockTestStore()
        val first = StockWidgetSettings(listOf("AAPL"), dense = true)
        store.widgets.save(1, first)
        store.widgets.save(2, StockWidgetSettings(listOf("MSFT")))
        assertFalse(store.widgets.updateAppearance(1, StockWidgetAppearance.from(first)))
        assertEquals("MSFT", store.widgets.rememberedWatchlist())
    }

    @Test
    fun `all display options can change independently of group content`() {
        val groups = listOf(StockGroup("Kept", listOf("AAPL", "MSFT")))
        val original = StockWidgetSettings(StockWatchlist.symbols(groups), groups = groups)
        val appearance = StockWidgetAppearance.from(original).copy(multiline = true, charts = false,
            twoColumns = false, dark = true, refreshMinutes = 60, transparent = true,
            showCurrency = false, groupStyle = StockGroupStyle.BORDERS, followAppTheme = false,
            fontScalePercent = 150)
        assertEquals(groups, appearance.applyTo(original).groups)
        assertEquals(original.symbols, appearance.applyTo(original).symbols)
        assertEquals(appearance, StockWidgetAppearance.from(appearance.applyTo(original)))
    }

    @Test
    fun `font size is saved immediately and restored per widget`() {
        val store = StockTestStore()
        val original = StockWidgetSettings(listOf("AAPL"))
        store.widgets.save(1, original)
        store.widgets.save(2, original)
        assertTrue(store.widgets.updateAppearance(1, StockWidgetAppearance.from(original).copy(fontScalePercent = 145)))
        assertEquals(145, StockWidgetPreferences(store.preferences).load(1)!!.fontScalePercent)
        assertEquals(100, store.widgets.load(2)!!.fontScalePercent)
        store.widgets.restore(intArrayOf(1), intArrayOf(3))
        assertEquals(145, store.widgets.load(3)!!.fontScalePercent)
    }
}
