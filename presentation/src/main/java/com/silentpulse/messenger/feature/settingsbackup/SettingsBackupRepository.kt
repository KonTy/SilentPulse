package com.silentpulse.messenger.feature.settingsbackup

import com.silentpulse.messenger.feature.stocks.StockWidgetSettings
import com.squareup.moshi.JsonClass

interface SettingsStore {
    fun read(section: String): Map<String, Any>
    /** Null removes only this key. Never clear a preference file. Must synchronously commit. */
    fun patch(section: String, values: Map<String, SettingValue?>): Boolean
    fun stocks(): List<StockWidgetSettings>
    fun clocks(): List<ClockPreset>
}

@JsonClass(generateAdapter = true)
data class SettingsRollback(
    val format: String = "silentpulse-settings-rollback",
    val changes: Map<String, Map<String, SettingValue?>>
)

interface SettingsRollbackFile {
    fun saveAndVerify(snapshot: SettingsRollback)
    fun read(): SettingsRollback
}

class SettingsImportException(val rollbackSucceeded: Boolean, cause: Exception) : Exception(cause)

class SettingsBackupRepository(
    private val store: SettingsStore,
    private val rollback: SettingsRollbackFile,
    private val defaults: Map<String, Map<String, Any>> = emptyMap()
) {
    fun export(): SettingsSnapshot = synchronized(lock) {
        val storedPresets = presets()
        SettingsSnapshot(
            stores = SettingsPolicy.rules.mapValues { (section, rules) ->
                (defaults[section].orEmpty() + store.read(section)).filterKeys(rules::containsKey)
                    .mapValues { SettingValue.pack(it.value) }
            },
            stocks = (store.stocks() + storedPresets.stocks.orEmpty()).distinct(),
            clocks = (store.clocks() + storedPresets.clocks.orEmpty()).distinct(),
            rememberedWatchlist = store.read(SettingsPolicy.STOCKS)[SettingsPolicy.REMEMBERED] as? String
        ).also(SettingsCodec::validate)
    }

    fun presets(): SettingsSnapshot = (store.read(SettingsPolicy.PRESETS)[SettingsPolicy.PRESET_KEY] as? String)
        ?.let(SettingsCodec::decode) ?: SettingsSnapshot()

    fun import(snapshot: SettingsSnapshot) = synchronized(lock) {
        SettingsCodec.validate(snapshot)
        val patches = snapshot.stores.mapValues {
            mutableMapOf<String, SettingValue?>().apply { putAll(it.value) }
        }.toMutableMap()
        snapshot.rememberedWatchlist?.let {
            patches[SettingsPolicy.STOCKS] = mutableMapOf(SettingsPolicy.REMEMBERED to SettingValue.pack(it))
        }
        if (snapshot.stocks != null || snapshot.clocks != null) {
            val current = presets()
            val merged = SettingsSnapshot(
                stocks = (current.stocks.orEmpty() + snapshot.stocks.orEmpty()).distinct(),
                clocks = (current.clocks.orEmpty() + snapshot.clocks.orEmpty()).distinct()
            )
            patches[SettingsPolicy.PRESETS] = mutableMapOf(
                SettingsPolicy.PRESET_KEY to SettingValue.pack(SettingsCodec.encode(merged))
            )
        }
        // Derived presentation flags must be committed before NightModeManager notifies activities.
        snapshot.stores[SettingsPolicy.APP]?.get("nightMode")?.unpack()?.let { mode ->
            patches.getValue(SettingsPolicy.APP)["black"] = SettingValue.pack(mode == 3)
            patches.getValue(SettingsPolicy.APP)["night"] = SettingValue.pack(mode == 2 || mode == 3)
        }
        transact(patches)
    }

    fun undo() = synchronized(lock) {
        val previous = rollback.read()
        validateRollback(previous)
        // Keep the original durable snapshot available if a recovery attempt itself fails.
        transact(previous.changes, saveBefore = false)
    }

    private fun transact(patches: Map<String, Map<String, SettingValue?>>, saveBefore: Boolean = true): Boolean {
        val changed = patches.mapValues { (section, entries) ->
            val existing = store.read(section)
            entries.filter { (key, value) ->
                if (value == null) existing.containsKey(key) else existing[key] != value.unpack()
            }
        }.filterValues { it.isNotEmpty() }
        // Empty or identical imports must not replace a useful recovery snapshot with an empty one.
        if (changed.isEmpty()) return false
        val before = SettingsRollback(changes = changed.mapValues { (section, entries) ->
            val existing = store.read(section)
            entries.mapValues { (key, _) -> existing[key]?.let(SettingValue::pack) }
        })
        validateRollback(before)
        if (saveBefore) rollback.saveAndVerify(before)
        val attempted = mutableListOf<String>()
        try {
            // App theme is written last, but the transaction is lifecycle-independent regardless.
            changed.keys.sortedBy { it == SettingsPolicy.APP }.forEach { section ->
                attempted += section
                check(writeVerified(section, changed.getValue(section))) { "Settings persistence failed" }
            }
        } catch (failure: Exception) {
            var restored = true
            attempted.asReversed().forEach { section ->
                try {
                    if (!writeVerified(section, before.changes.getValue(section))) restored = false
                } catch (_: Exception) {
                    restored = false
                }
            }
            throw SettingsImportException(restored, failure)
        }
        return true
    }

    private fun writeVerified(section: String, values: Map<String, SettingValue?>): Boolean {
        val committed = store.patch(section, values)
        val actual = store.read(section)
        return committed && values.all { (key, value) ->
            if (value == null) !actual.containsKey(key) else actual[key] == value.unpack()
        }
    }

    private fun validateRollback(snapshot: SettingsRollback) {
        require(snapshot.format == "silentpulse-settings-rollback")
        val rawChanges: Map<*, *> = snapshot.changes
        require(rawChanges.values.all { it is Map<*, *> })
        snapshot.changes.forEach { (section, entries) ->
            val rawEntries: Map<*, *> = entries
            require(rawEntries.values.all { it == null || it is SettingValue })
            entries.forEach { (key, value) ->
                val allowed = when (section) {
                    SettingsPolicy.APP -> key == "night" || SettingsPolicy.rules.getValue(section).containsKey(key)
                    SettingsPolicy.FAMILY -> SettingsPolicy.rules.getValue(section).containsKey(key)
                    SettingsPolicy.STOCKS -> key == SettingsPolicy.REMEMBERED
                    SettingsPolicy.PRESETS -> key == SettingsPolicy.PRESET_KEY
                    else -> false
                }
                require(allowed)
                if (value != null) {
                    val unpacked = value.unpack()
                    when (section) {
                        SettingsPolicy.APP, SettingsPolicy.FAMILY -> {
                            if (key == "night") require(unpacked is Boolean)
                            else require(SettingsPolicy.rules.getValue(section).getValue(key)(unpacked))
                        }
                        SettingsPolicy.STOCKS -> {
                            require(unpacked is String)
                            SettingsCodec.validate(SettingsSnapshot(rememberedWatchlist = unpacked))
                        }
                        SettingsPolicy.PRESETS -> {
                            require(unpacked is String)
                            SettingsCodec.decode(unpacked)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val lock = Any()
    }
}
