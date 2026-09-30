package com.silentpulse.messenger.feature.settingsbackup

import android.content.Context
import android.content.SharedPreferences
import com.silentpulse.messenger.feature.stocks.StockGroup
import com.silentpulse.messenger.feature.stocks.StockGroupStyle
import com.silentpulse.messenger.feature.stocks.StockWidgetPreferences
import com.silentpulse.messenger.feature.stocks.StockWidgetSettings
import com.silentpulse.messenger.feature.stocks.StockWidgetAppearance
import com.silentpulse.messenger.feature.stocks.StockWatchlist
import com.silentpulse.messenger.feature.worldclock.WorldClockPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*

class AndroidSettingsStoreTest {
    @Test fun `stock fonts and raw separator groups survive export import re-export and explicit preset application`() {
        val source = PreferenceFixture()
        val sourcePreferences = StockWidgetPreferences(source.preferences("stock_widgets"))
        val groups = listOf(
            StockGroup("Technology", listOf("MSFT", "AAPL")),
            StockGroup("Commodities-", listOf("GC=F", "CL=F")),
            StockGroup("Other", listOf("CORN", "BTC-USD"))
        )
        val cards = StockWidgetSettings(
            StockWatchlist.symbols(groups), groups = groups, multiline = true, charts = false,
            twoColumns = false, dark = true, refreshMinutes = 60, transparent = true,
            showCurrency = false, groupStyle = StockGroupStyle.BORDERS, followAppTheme = false,
            fontScalePercent = 145
        )
        val dense = cards.copy(
            multiline = false, dense = true, charts = true, twoColumns = true, dark = false,
            refreshMinutes = 30, transparent = false, showCurrency = true,
            groupStyle = StockGroupStyle.NONE, followAppTheme = true, fontScalePercent = 180
        )
        val imported = cards.copy(multiline = false, refreshMinutes = 15,
            groupStyle = StockGroupStyle.LINES, fontScalePercent = 80)
        sourcePreferences.save(43, cards)
        sourcePreferences.save(77, dense)
        val sourceRepository = repository(source)
        sourceRepository.import(SettingsSnapshot(stocks = listOf(imported)))
        val exported = SettingsCodec.decode(SettingsCodec.encode(sourceRepository.export()))
        val expected = listOf(cards, dense, imported)
        assertEquals(expected, exported.stocks)
        assertEquals(StockWatchlist.format(groups), exported.rememberedWatchlist)

        val destination = PreferenceFixture()
        val destinationPreferences = StockWidgetPreferences(destination.preferences("stock_widgets"))
        val existing = StockWidgetSettings(listOf("IBM"), fontScalePercent = 110)
        val independent = StockWidgetSettings(listOf("BND"), dense = true, fontScalePercent = 125)
        destinationPreferences.save(43, existing)
        destinationPreferences.save(88, independent)
        val before = destination.values.getValue("stock_widgets").toMap()
        val destinationRepository = repository(destination)
        destinationRepository.import(exported)
        assertEquals(before.filterKeys { it != SettingsPolicy.REMEMBERED },
            destination.values.getValue("stock_widgets").filterKeys { it != SettingsPolicy.REMEMBERED })
        assertEquals(existing, destinationPreferences.load(43))
        assertEquals(independent, destinationPreferences.load(88))
        assertEquals(StockWatchlist.format(groups), destinationPreferences.rememberedWatchlist())
        assertEquals(expected, destinationRepository.presets().stocks)
        assertEquals(listOf(existing, independent) + expected,
            SettingsCodec.decode(SettingsCodec.encode(destinationRepository.export())).stocks)

        destinationRepository.presets().stocks!!.forEach { preset ->
            val editorGroups = StockWatchlist.parse(StockWatchlist.format(StockWatchlist.effectiveGroups(preset)))!!
            val reviewed = StockWidgetAppearance.from(preset).applyTo(StockWidgetSettings(
                StockWatchlist.symbols(editorGroups), groups = editorGroups
            ))
            destinationPreferences.save(43, reviewed)
            assertEquals(preset, destinationPreferences.load(43))
            assertEquals(independent, destinationPreferences.load(88))
            val marked = destinationPreferences.load(43)!!.groups[1]
            assertEquals("Commodities-", marked.name)
            assertEquals("Commodities", marked.displayName)
            assertTrue(marked.hasSeparator)
        }
        verify(destination.editors.getValue("stock_widgets"), never()).clear()
    }

    @Test fun `exports actual per-widget namespaces including legacy defaults without cache files`() {
        val fixture = PreferenceFixture()
        val stock = fixture.preferences("stock_widgets")
        val groups = listOf(StockGroup("Mixed", listOf("MSFT", "GC=F", "CORN")))
        val settings = StockWidgetSettings(
            groups.flatMap { it.symbols }, groups = groups, dense = true, dark = true,
            transparent = true, showCurrency = false, refreshMinutes = 30,
            charts = false, twoColumns = false, groupStyle = StockGroupStyle.NONE, followAppTheme = false
        )
        StockWidgetPreferences(stock).save(43, settings)
        fixture.values.getValue("stock_widgets")["77.symbols"] = "AAPL"
        val clockPrefs = WorldClockPreferences(fixture.preferences("world_clock_widgets"))
        clockPrefs.save(1, ClockPreset("Tokyo", "Asia/Tokyo", true).settings())
        clockPrefs.save(777, ClockPreset("Berlin", "Europe/Berlin", false).settings())
        fixture.values.getOrPut("stock_quote_cache") { mutableMapOf() }["private"] = "cached quote"
        fixture.values.getOrPut("world_clock_weather") { mutableMapOf() }["private"] = "cached city"
        val store = AndroidSettingsStore(fixture.context)
        assertEquals(listOf(settings, StockWidgetSettings(listOf("AAPL"))), store.stocks())
        assertEquals(listOf(ClockPreset("Tokyo", "Asia/Tokyo", true), ClockPreset("Berlin", "Europe/Berlin")), store.clocks())
        verify(fixture.context, never()).getSharedPreferences("stock_quote_cache", Context.MODE_PRIVATE)
        verify(fixture.context, never()).getSharedPreferences("world_clock_weather", Context.MODE_PRIVATE)
    }

    @Test fun `adapter preserves every Android preference type commits and only removes requested keys`() {
        val fixture = PreferenceFixture()
        val store = AndroidSettingsStore(fixture.context)
        val values = mapOf<String, Any>(
            "boolean" to true, "int" to Int.MIN_VALUE, "long" to Long.MAX_VALUE,
            "float" to 1.25f, "string" to "Hello", "set" to setOf("b", "a")
        )
        fixture.preferences("example_preferences")
        fixture.values.getValue("example_preferences")["untouched"] = "existing"
        assertTrue(store.patch("app", values.mapValues { SettingValue.pack(it.value) }))
        assertEquals(values + ("untouched" to "existing"), store.read("app"))
        assertTrue(store.patch("app", mapOf("string" to null)))
        assertFalse(store.read("app").containsKey("string"))
        assertEquals("existing", store.read("app")["untouched"])
        verify(fixture.editors.getValue("example_preferences"), times(2)).commit()
        verify(fixture.editors.getValue("example_preferences"), never()).apply()
        verify(fixture.editors.getValue("example_preferences"), never()).clear()
    }

    @Test fun `default sections read only hardcoded files and reject arbitrary names`() {
        val fixture = PreferenceFixture()
        val store = AndroidSettingsStore(fixture.context)
        assertTrue(store.read("app").isEmpty())
        assertTrue(store.read("family").isEmpty())
        assertTrue(store.read("presets").isEmpty())
        try {
            store.read("../secrets")
            fail("Expected rejection")
        } catch (_: IllegalStateException) { }
        assertEquals(setOf("example_preferences", "family_hub", "settings_backup_presets"), fixture.values.keys)
    }

    @Test fun `oversized local files reject without reading unbounded data`() {
        val oversized = ByteArray(SettingsCodec.MAX_BYTES + 1).inputStream()
        try {
            readSettingsText(oversized)
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `malformed UTF8 is rejected rather than altering backed-up text`() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            readSettingsText(byteArrayOf(0xc3.toByte(), 0x28).inputStream())
        }
    }

    private fun repository(fixture: PreferenceFixture) = SettingsBackupRepository(
        AndroidSettingsStore(fixture.context),
        object : SettingsRollbackFile {
            private var saved: SettingsRollback? = null
            override fun saveAndVerify(snapshot: SettingsRollback) { saved = snapshot }
            override fun read() = requireNotNull(saved)
        }
    )
}

private class PreferenceFixture {
    val values = mutableMapOf<String, MutableMap<String, Any>>()
    val editors = mutableMapOf<String, SharedPreferences.Editor>()
    private val files = mutableMapOf<String, SharedPreferences>()
    val context: Context = mock(Context::class.java)

    init {
        `when`(context.packageName).thenReturn("example")
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenAnswer {
            preferences(it.getArgument(0))
        }
    }

    fun preferences(name: String): SharedPreferences = files.getOrPut(name) {
        val entries = values.getOrPut(name) { mutableMapOf() }
        val pending = mutableMapOf<String, Any?>()
        val editor = mock(SharedPreferences.Editor::class.java) { invocation ->
            when (invocation.method.name) {
                "putString", "putBoolean", "putInt", "putLong", "putFloat", "putStringSet" -> {
                    pending[invocation.getArgument(0)] = invocation.getArgument(1)
                    invocation.mock
                }
                "remove" -> {
                    pending[invocation.getArgument(0)] = null
                    invocation.mock
                }
                "apply", "commit" -> {
                    pending.forEach { (key, value) -> if (value == null) entries.remove(key) else entries[key] = value }
                    pending.clear()
                    if (invocation.method.name == "commit") true else null
                }
                else -> null
            }
        }
        editors[name] = editor
        mock(SharedPreferences::class.java) { invocation ->
            when (invocation.method.name) {
                "getAll" -> entries.toMap()
                "getString", "getBoolean", "getInt", "getLong", "getFloat", "getStringSet" ->
                    entries[invocation.getArgument<String>(0)] ?: invocation.getArgument<Any?>(1)
                "contains" -> entries.containsKey(invocation.getArgument(0))
                "edit" -> editor
                else -> null
            }
        }
    }
}
