package com.silentpulse.messenger.feature.stocks

import org.junit.Assert.*
import org.junit.Test

class StockSettingsWidgetListTest {
    @Test
    fun `no installed widgets stays empty without reviving saved settings or clearing remembered groups`() {
        val store = StockTestStore()
        val groups = listOf(StockGroup("Commodities-", listOf("GC=F")))
        store.widgets.save(42, StockWidgetSettings(listOf("GC=F"), groups = groups))
        val before = store.values.toMap()
        val list = StockSettingsWidgetList({ intArrayOf() }, { true }, store.widgets::load)

        assertTrue(list.load().isEmpty())
        assertEquals(before, store.values)
        assertEquals(groups, StockWatchlist.parse(store.widgets.rememberedWatchlist()))
    }

    @Test
    fun `installed widgets retain independent settings and only owned positive IDs are read`() {
        val first = StockWidgetSettings(listOf("AAPL"), fontScalePercent = 145)
        val second = StockWidgetSettings(listOf("MSFT"), dense = true, fontScalePercent = 80)
        val readIds = mutableListOf<Int>()
        val list = StockSettingsWidgetList(
            { intArrayOf(8, 2, 8, 0, -1, 99) },
            { it != 99 },
            { id -> readIds += id; mapOf(2 to first, 8 to second)[id] }
        )

        assertEquals(listOf(
            StockSettingsWidgetList.Entry(2, first),
            StockSettingsWidgetList.Entry(8, second)
        ), list.load())
        assertEquals(listOf(2, 8), readIds)
        assertFalse(list.canConfigure(0))
        assertFalse(list.canConfigure(-1))
        assertFalse(list.canConfigure(99))
    }

    @Test
    fun `owned unconfigured widgets remain available for explicit configuration without a save`() {
        val store = StockTestStore()
        val list = StockSettingsWidgetList({ intArrayOf(7) }, { it == 7 }, store.widgets::load)

        assertEquals(listOf(StockSettingsWidgetList.Entry(7, null)), list.load())
        assertTrue(list.canConfigure(7))
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `returning to hub rereads settings and stale selection rechecks current ownership`() {
        val store = StockTestStore()
        val first = StockWidgetSettings(listOf("AAPL"))
        store.widgets.save(2, first)
        var installed = intArrayOf(2)
        var owned = setOf(2)
        val list = StockSettingsWidgetList({ installed }, { it in owned }, store.widgets::load)
        val selected = list.load().single()
        store.widgets.save(2, first.copy(fontScalePercent = 180))
        assertEquals(180, list.load().single().settings!!.fontScalePercent)

        owned = emptySet()
        assertFalse(list.canConfigure(selected.widgetId))
        assertTrue(list.load().isEmpty())
        installed = intArrayOf()
        assertTrue(list.load().isEmpty())
        assertNotNull(store.widgets.load(2))
        assertEquals("AAPL", store.widgets.rememberedWatchlist())
    }
}
