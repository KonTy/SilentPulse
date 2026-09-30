package com.silentpulse.messenger.feature.settingsbackup

import com.silentpulse.messenger.feature.stocks.StockGroup
import com.silentpulse.messenger.feature.stocks.StockGroupStyle
import com.silentpulse.messenger.feature.stocks.StockWidgetSettings
import com.silentpulse.messenger.feature.stocks.StockWatchlist
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {
    private val groups = listOf(
        StockGroup("Technology", listOf("MSFT", "AAPL")),
        StockGroup("Commodities", listOf("GC=F", "CL=F")),
        StockGroup("Other", listOf("BTC-USD", "CORN"))
    )
    private val cards = StockWidgetSettings(
        StockWatchlist.symbols(groups), multiline = true, charts = false, twoColumns = false,
        dark = true, refreshMinutes = 60, transparent = true, showCurrency = false,
        groups = groups, groupStyle = StockGroupStyle.BORDERS, followAppTheme = false, fontScalePercent = 145
    )
    private val dense = cards.copy(multiline = false, dense = true, charts = true,
        twoColumns = true, transparent = false, groupStyle = StockGroupStyle.NONE, followAppTheme = true)
    private val clocks = listOf(
        ClockPreset("Tokyo", "Asia/Tokyo", true),
        ClockPreset("Seattle", "America/Los_Angeles", false)
    )

    @Test fun `all supported primitive types have lossless tagged round trips`() {
        val adapter = Moshi.Builder().build().adapter(SettingValue::class.java)
        listOf(true, false, Int.MIN_VALUE, Int.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE,
            Float.MIN_VALUE, Float.MAX_VALUE, -0.0f, "custom\ntext", setOf("z", "a"), emptySet<String>())
            .forEach { value ->
                val packed = SettingValue.pack(value)
                val decoded = adapter.fromJson(adapter.toJson(packed))!!
                assertEquals(value, decoded.unpack())
                if (value !is Set<*>) assertEquals(value.javaClass, decoded.unpack().javaClass)
            }
    }

    @Test fun `custom signed accent OLED and every stock field multiple clocks and remembered list round trip`() {
        val source = MemoryStore()
        source.values.getValue("app").putAll(mapOf(
            "theme" to 0xFFA020F0.toInt(), "nightMode" to 3, "black" to true,
            "systemFont" to true, "textSize" to 3, "autoColor" to false,
            "drive_mode_max_announcements" to 999
        ))
        source.stockConfigurations = listOf(cards, dense, StockWidgetSettings(listOf("^GSPC")))
        source.clockConfigurations = clocks
        source.values.getValue("stocks")["remembered_watchlist"] = StockWatchlist.format(groups)
        val exported = repository(source).export()
        val decoded = SettingsCodec.decode(SettingsCodec.encode(exported))
        assertEquals(exported, decoded)
        val destination = MemoryStore()
        repository(destination).import(decoded)
        assertEquals(0xFFA020F0.toInt(), destination.values.getValue("app")["theme"])
        assertEquals(3, destination.values.getValue("app")["nightMode"])
        assertEquals(true, destination.values.getValue("app")["black"])
        assertEquals(true, destination.values.getValue("app")["night"])
        assertEquals(listOf(cards, dense, StockWidgetSettings(listOf("^GSPC"))), repository(destination).presets().stocks)
        assertEquals(clocks, repository(destination).presets().clocks)
        assertEquals(StockWatchlist.format(groups), destination.values.getValue("stocks")["remembered_watchlist"])
    }

    @Test fun `old preference defaults and backups with no widgets remain supported`() {
        val store = MemoryStore()
        val defaults = AndroidSettingsStore.defaults()
        assertEquals(SettingsPolicy.rules.mapValues { it.value.keys }, defaults.mapValues { it.value.keys })
        val backup = SettingsBackupRepository(store, MemoryRollback(), defaults).export()
        assertEquals(3, backup.stores.getValue("app").getValue("nightMode").unpack())
        assertEquals(0xFF0097A7.toInt(), backup.stores.getValue("app").getValue("theme").unpack())
        assertEquals(emptyList<StockWidgetSettings>(), backup.stocks)
        assertEquals(emptyList<ClockPreset>(), backup.clocks)
        assertNull(backup.rememberedWatchlist)
        assertEquals(backup, SettingsCodec.decode(SettingsCodec.encode(backup)))
        val minimal = SettingsCodec.decode("""{"format":"silentpulse-settings","version":1}""")
        repository(store).import(minimal)
        assertTrue(store.values.values.all { it.isEmpty() })
    }

    @Test fun `stock presets from before the font slider default to normal size without stripping group markers`() {
        val snapshot = SettingsCodec.decode(
            """{"format":"silentpulse-settings","version":1,"stocks":[{"symbols":["GC=F"],"groups":[{"name":"Commodities-","symbols":["GC=F"]}]}]}"""
        )
        val stock = snapshot.stocks!!.single()
        assertEquals(100, stock.fontScalePercent)
        assertEquals("Commodities-", stock.groups.single().name)
        assertEquals("[Commodities-]\nGC=F", StockWatchlist.format(stock.groups))
        assertEquals(snapshot, SettingsCodec.decode(SettingsCodec.encode(snapshot)))
    }

    @Test fun `absent sections preserve current values imported presets merge and export again`() {
        val store = MemoryStore()
        store.values.getValue("app")["theme"] = -123456
        store.values.getValue("stocks")["remembered_watchlist"] = "MSFT"
        val repo = repository(store)
        repo.import(SettingsSnapshot(stocks = listOf(cards), clocks = clocks))
        repo.import(SettingsSnapshot(stocks = listOf(dense, cards)))
        assertEquals(-123456, store.values.getValue("app")["theme"])
        assertEquals("MSFT", store.values.getValue("stocks")["remembered_watchlist"])
        assertEquals(listOf(cards, dense), repo.export().stocks)
        assertEquals(clocks, repo.export().clocks)
    }

    @Test fun `unstored appearance defaults replace a differently configured install but absent fields do not`() {
        val source = MemoryStore()
        val exported = SettingsBackupRepository(source, MemoryRollback(), AndroidSettingsStore.defaults()).export()
        val destination = MemoryStore()
        destination.values.getValue("app").putAll(mapOf(
            "theme" to -123456, "nightMode" to 1, "black" to false,
            "textSize" to 3, "systemFont" to true, "autoColor" to false
        ))
        val repo = repository(destination)
        repo.import(SettingsSnapshot(stocks = listOf(cards)))
        assertEquals(-123456, destination.values.getValue("app")["theme"])
        assertEquals(1, destination.values.getValue("app")["nightMode"])
        assertEquals(3, destination.values.getValue("app")["textSize"])

        repo.import(SettingsCodec.decode(SettingsCodec.encode(exported)))
        assertEquals(0xFF0097A7.toInt(), destination.values.getValue("app")["theme"])
        assertEquals(3, destination.values.getValue("app")["nightMode"])
        assertEquals(true, destination.values.getValue("app")["black"])
        assertEquals(true, destination.values.getValue("app")["night"])
        assertEquals(1, destination.values.getValue("app")["textSize"])
        assertEquals(false, destination.values.getValue("app")["systemFont"])
        assertEquals(true, destination.values.getValue("app")["autoColor"])
        assertTrue(source.values.getValue("app").isEmpty())
    }

    @Test fun `numeric widget ID collisions and different IDs never overwrite unrelated launcher widgets`() {
        val store = MemoryStore()
        store.values.getValue("stocks")["1.symbols"] = "IBM"
        store.values.getValue("stocks")["99.currency"] = true
        store.values.getValue("clocks").putAll(mapOf("clock_1.city" to "London", "clock_1.zone" to "Europe/London"))
        val before = store.values.mapValues { it.value.toMap() }
        repository(store).import(SettingsSnapshot(stocks = listOf(cards, dense), clocks = clocks))
        assertEquals(before["stocks"], store.values["stocks"])
        assertEquals(before["clocks"], store.values["clocks"])
        assertEquals(listOf(cards, dense), repository(store).presets().stocks)
        assertEquals(clocks, repository(store).presets().clocks)
        assertFalse(SettingsCodec.encode(repository(store).export()).contains("clock_1"))
    }

    @Test fun `non OLED modes derive flags while preserving signed colors`() {
        for (mode in 0..3) {
            val store = MemoryStore()
            repository(store).import(SettingsSnapshot(stores = mapOf("app" to mapOf(
                "nightMode" to SettingValue.pack(mode), "theme" to SettingValue.pack(Int.MIN_VALUE)
            ))))
            assertEquals(mode == 3, store.values.getValue("app")["black"])
            assertEquals(mode >= 2, store.values.getValue("app")["night"])
            assertEquals(Int.MIN_VALUE, store.values.getValue("app")["theme"])
        }
    }

    @Test fun `secret cache runtime and contact-scoped keys never leave the app`() {
        val store = MemoryStore()
        store.values.getValue("app").putAll(mapOf(
            "theme" to -9, "theme_42" to -8, "drive_mode_enabled" to true,
            "drive_mode_wake_word" to true, "api_key" to "SECRET", "reply_nonce" to "SECRET",
            "ringtone" to "content://private/audio", "autoDelete" to 7, "logging" to true,
            "drivemode_vosk_model_path" to "/private/path", "version" to 20
        ))
        store.values.getValue("family").putAll(mapOf("share_my_location" to true, "tile_cache_to_disk" to false))
        store.values.getValue("stocks")["quote_cache"] = "PRIVATE QUOTES"
        val snapshot = repository(store).export()
        assertEquals(setOf("theme"), snapshot.stores.getValue("app").keys)
        assertEquals(setOf("tile_cache_to_disk"), snapshot.stores.getValue("family").keys)
        val json = SettingsCodec.encode(snapshot)
        listOf("SECRET", "PRIVATE", "content://", "/private", "theme_42").forEach { assertFalse(json.contains(it)) }
    }

    @Test fun `invalid versions stores keys types bounds and widget data fail before snapshot or writes`() {
        val bad = listOf(
            SettingsSnapshot().copy(version = 2),
            SettingsSnapshot().copy(format = "sms-backup"),
            SettingsSnapshot(stores = mapOf("../private" to emptyMap())),
            SettingsSnapshot(stores = mapOf("app" to mapOf("api_key" to SettingValue.pack("bad")))),
            SettingsSnapshot(stores = mapOf("app" to mapOf("theme" to SettingValue.pack(10L)))),
            SettingsSnapshot(stores = mapOf("app" to mapOf("nightMode" to SettingValue.pack(4)))),
            SettingsSnapshot(stores = mapOf("app" to mapOf("textSize" to SettingValue.pack(-1)))),
            SettingsSnapshot(stores = mapOf("app" to mapOf("mmsSize" to SettingValue.pack(42)))),
            SettingsSnapshot(stocks = listOf(cards.copy(symbols = listOf("https://bad")))),
            SettingsSnapshot(stocks = listOf(cards.copy(refreshMinutes = 1))),
            SettingsSnapshot(stocks = listOf(cards.copy(fontScalePercent = 79))),
            SettingsSnapshot(stocks = listOf(cards.copy(fontScalePercent = 81))),
            SettingsSnapshot(stocks = listOf(cards.copy(fontScalePercent = 181))),
            SettingsSnapshot(stocks = listOf(cards.copy(dense = true))),
            SettingsSnapshot(stocks = listOf(cards.copy(groups = listOf(StockGroup("Bad\nName", listOf("MSFT")))))),
            SettingsSnapshot(clocks = listOf(ClockPreset("", "Asia/Tokyo"))),
            SettingsSnapshot(clocks = listOf(ClockPreset("Bad", "Mars/Phobos"))),
            SettingsSnapshot(clocks = listOf(ClockPreset("Lafayette, Indiana", "America/Chicago"))),
            SettingsSnapshot(rememberedWatchlist = "[empty]")
        )
        bad.forEach { snapshot ->
            val store = MemoryStore()
            val rollback = MemoryRollback()
            expectFailure { SettingsBackupRepository(store, rollback).import(snapshot) }
            assertEquals(0, store.writes)
            assertNull(rollback.saved)
        }
    }

    @Test fun `corrupt untagged unknown and oversized documents reject`() {
        listOf("", "{}", "null", "[]", """{"format":"silentpulse-settings"}""",
            """{"format":"silentpulse-settings","version":1,"messages":[]}""",
            """{"format":"silentpulse-settings","version":1,"stocks":[{"symbols":["MSFT"],"dark":"yes"}]}""",
            " ".repeat(SettingsCodec.MAX_BYTES + 1)
        ).forEach { json -> expectFailure { SettingsCodec.decode(json) } }
        listOf(SettingValue("boolean", "yes"), SettingValue("int", "2147483648"),
            SettingValue("float", "NaN"), SettingValue("path", "/private"),
            SettingValue("stringSet", items = listOf("a", "a")),
            SettingValue("long", "1", items = emptyList())
        ).forEach { expectFailure { it.unpack() } }
    }

    @Test fun `coerced scalar types and null nested entries are rejected before mutation`() {
        listOf(
            """{"format":"silentpulse-settings","version":"1"}""",
            """{"format":"silentpulse-settings","version":1,"stores":{"app":{"theme":{"type":"int","value":42}}}}""",
            """{"format":"silentpulse-settings","version":1,"stocks":[null]}""",
            """{"format":"silentpulse-settings","version":1,"clocks":[null]}""",
            """{"format":"silentpulse-settings","version":1,"stores":{"app":null}}""",
            """{"format":"silentpulse-settings","version":1,"stores":{"app":{"theme":null}}}""",
            """{"format":"silentpulse-settings","version":1,"stocks":[{"symbols":[null]}]}""",
            """{"format":"silentpulse-settings","version":1,"stocks":[{"symbols":["AAPL"],"groups":[null]}]}"""
        ).forEach { expectFailure { SettingsCodec.decode(it) } }
    }

    @Test fun `empty and identical imports preserve the previous useful recovery snapshot`() {
        val store = MemoryStore()
        store.values.getValue("app")["theme"] = -1
        val rollback = MemoryRollback()
        val repo = SettingsBackupRepository(store, rollback)
        val incoming = SettingsSnapshot(stores = mapOf("app" to mapOf("theme" to SettingValue.pack(-2))))
        assertTrue(repo.import(incoming))
        val previous = rollback.saved
        val writes = store.writes
        assertFalse(repo.import(SettingsSnapshot()))
        assertFalse(repo.import(incoming))
        assertEquals(previous, rollback.saved)
        assertEquals(writes, store.writes)
        repo.undo()
        assertEquals(-1, store.values.getValue("app")["theme"])
    }

    @Test fun `group separator markers and large existing watchlists remain exportable`() {
        val symbols = (1..501).map { "T$it" }
        val marked = StockWidgetSettings(symbols, groups = listOf(StockGroup("Commodities-", symbols)))
        val decoded = SettingsCodec.decode(SettingsCodec.encode(SettingsSnapshot(stocks = listOf(marked))))
        assertEquals(marked, decoded.stocks!!.single())
        assertEquals("Commodities", decoded.stocks.single().groups.single().displayName)
        assertTrue(decoded.stocks.single().groups.single().hasSeparator)
    }

    @Test fun `verified pre-import snapshot is mandatory and rollback restores only touched keys`() {
        val store = MemoryStore()
        store.values.getValue("app").putAll(mapOf("theme" to -1, "signature" to "Keep", "secret" to "Never copy"))
        val undo = MemoryRollback()
        undo.failSave = true
        val repo = SettingsBackupRepository(store, undo)
        val incoming = SettingsSnapshot(stores = mapOf("app" to mapOf("theme" to SettingValue.pack(-2))))
        expectFailure { repo.import(incoming) }
        assertEquals(0, store.writes)
        undo.failSave = false
        repo.import(incoming)
        assertEquals(setOf("theme"), undo.saved!!.changes.getValue("app").keys)
        store.values.getValue("app")["signature"] = "Changed later"
        repo.undo()
        assertEquals(-1, store.values.getValue("app")["theme"])
        assertEquals("Changed later", store.values.getValue("app")["signature"])
        assertEquals("Never copy", store.values.getValue("app")["secret"])
    }

    @Test fun `partial commit failure rolls back earlier files and absent keys without false success`() {
        val store = MemoryStore()
        store.values.getValue("app")["theme"] = -1
        store.failOnWrites = setOf(2)
        val failure = expectFailure {
            repository(store).import(SettingsSnapshot(
                stores = mapOf("app" to mapOf("theme" to SettingValue.pack(-2))),
                stocks = listOf(cards)
            ))
        }
        assertTrue(failure is SettingsImportException && failure.rollbackSucceeded)
        assertEquals(-1, store.values.getValue("app")["theme"])
        assertTrue(store.values.getValue("presets").isEmpty())
        assertEquals(4, store.writes)
    }

    @Test fun `rollback persistence failures are surfaced explicitly`() {
        val store = MemoryStore()
        store.failOnWrites = setOf(1, 2)
        val failure = expectFailure {
            repository(store).import(SettingsSnapshot(stores = mapOf("app" to mapOf("theme" to SettingValue.pack(-2)))))
        }
        assertTrue(failure is SettingsImportException && !failure.rollbackSucceeded)
    }

    @Test fun `failed manual recovery retains original durable snapshot for retry`() {
        val store = MemoryStore()
        store.values.getValue("app")["theme"] = -1
        val rollback = MemoryRollback()
        val repo = SettingsBackupRepository(store, rollback)
        repo.import(SettingsSnapshot(stores = mapOf("app" to mapOf("theme" to SettingValue.pack(-2)))))
        val original = rollback.saved
        store.failOnWrites = setOf(2, 3)
        expectFailure { repo.undo() }
        assertEquals(original, rollback.saved)
        store.failOnWrites = emptySet()
        repo.undo()
        assertEquals(-1, store.values.getValue("app")["theme"])
    }

    @Test fun `commit true with mismatched readback is a failed import`() {
        val store = MemoryStore()
        store.dropOnWrite = 1
        val failure = expectFailure {
            repository(store).import(SettingsSnapshot(stores = mapOf("app" to mapOf("theme" to SettingValue.pack(-2)))))
        }
        assertTrue(failure is SettingsImportException && failure.rollbackSucceeded)
    }

    private fun repository(store: MemoryStore) = SettingsBackupRepository(store, MemoryRollback())

    private fun expectFailure(block: () -> Unit): Exception {
        try { block() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected rejection")
    }
}

private class MemoryStore : SettingsStore {
    val values = listOf("app", "stocks", "clocks", "presets", "family")
        .associateWith { mutableMapOf<String, Any>() }
    var stockConfigurations = emptyList<StockWidgetSettings>()
    var clockConfigurations = emptyList<ClockPreset>()
    var writes = 0
    var failOnWrites = emptySet<Int>()
    var dropOnWrite = -1
    override fun read(section: String): Map<String, Any> = values.getValue(section).toMap()
    override fun patch(section: String, values: Map<String, SettingValue?>): Boolean {
        writes++
        if (writes != dropOnWrite) values.forEach { (key, value) ->
            if (value == null) this.values.getValue(section).remove(key)
            else this.values.getValue(section)[key] = value.unpack()
        }
        return writes !in failOnWrites
    }
    override fun stocks() = stockConfigurations
    override fun clocks() = clockConfigurations
}

private class MemoryRollback : SettingsRollbackFile {
    var saved: SettingsRollback? = null
    var failSave = false
    override fun saveAndVerify(snapshot: SettingsRollback) {
        if (failSave) error("Disk unavailable")
        saved = snapshot
    }
    override fun read() = requireNotNull(saved)
}
