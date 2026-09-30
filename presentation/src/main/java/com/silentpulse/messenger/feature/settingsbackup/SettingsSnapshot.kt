package com.silentpulse.messenger.feature.settingsbackup

import com.silentpulse.messenger.common.util.CityTimeZone
import com.silentpulse.messenger.common.util.CityTimeZones
import com.silentpulse.messenger.common.util.CityLocationCatalog
import com.silentpulse.messenger.feature.stocks.StockWidgetPreferences
import com.silentpulse.messenger.feature.stocks.StockWidgetSettings
import com.silentpulse.messenger.feature.stocks.StockWatchlist
import com.silentpulse.messenger.feature.worldclock.WorldClockSettings
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/** Numeric payloads are strings: no JSON Double conversion can truncate a Long or signed ARGB. */
@JsonClass(generateAdapter = true)
data class SettingValue(val type: String, val value: String? = null, val items: List<String>? = null) {
    fun unpack(): Any {
        require((type == "stringSet") == (items != null))
        if (type == "stringSet") {
            val strings = requireNotNull(items)
            val rawStrings: List<*> = strings
            require(rawStrings.all { it is String })
            require(value == null && strings.size <= 500 && strings.distinct() == strings)
            require(strings.all { it.length <= 4096 })
            return strings.toSet()
        }
        val text = requireNotNull(value)
        require(text.length <= SettingsCodec.MAX_BYTES)
        return when (type) {
            "boolean" -> when (text) { "true" -> true; "false" -> false; else -> error("Invalid boolean") }
            "int" -> text.toInt()
            "long" -> text.toLong()
            "float" -> text.toFloat().also { require(it.isFinite()) }
            "string" -> text
            else -> error("Unsupported preference type")
        }
    }

    companion object {
        fun pack(value: Any): SettingValue = when (value) {
            is Boolean -> SettingValue("boolean", value.toString())
            is Int -> SettingValue("int", value.toString())
            is Long -> SettingValue("long", value.toString())
            is Float -> SettingValue("float", value.toString()).also { require(value.isFinite()) }
            is String -> SettingValue("string", value)
            is Set<*> -> {
                require(value.all { it is String })
                SettingValue("stringSet", items = value.filterIsInstance<String>().sorted())
            }
            else -> error("Unsupported preference type")
        }
    }
}

@JsonClass(generateAdapter = true)
data class ClockPreset(val city: String, val zone: String, val darkText: Boolean = false) {
    fun validate() {
        require(city.isNotBlank() && city.length <= 200 && city.none(Char::isISOControl))
        require(CityTimeZones.isValidZoneId(zone))
        require(CityLocationCatalog.isConsistent(CityTimeZone(city, zone)))
    }
    fun settings() = WorldClockSettings(CityTimeZone(city, zone), darkText)

    companion object {
        fun from(settings: WorldClockSettings) =
            ClockPreset(settings.city.city, settings.city.zoneId, settings.darkText)
    }
}

@JsonClass(generateAdapter = true)
data class SettingsSnapshot(
    val format: String,
    val version: Int,
    val stores: Map<String, Map<String, SettingValue>> = emptyMap(),
    val stocks: List<StockWidgetSettings>? = null,
    val clocks: List<ClockPreset>? = null,
    val rememberedWatchlist: String? = null
) {
    constructor(
        stores: Map<String, Map<String, SettingValue>> = emptyMap(),
        stocks: List<StockWidgetSettings>? = null,
        clocks: List<ClockPreset>? = null,
        rememberedWatchlist: String? = null
    ) : this(FORMAT, 1, stores, stocks, clocks, rememberedWatchlist)

    companion object { const val FORMAT = "silentpulse-settings" }
}

object SettingsCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val moshi = Moshi.Builder().add(StrictSettingsScalars).build()
    private val adapter = moshi.adapter(SettingsSnapshot::class.java).failOnUnknown()

    fun encode(snapshot: SettingsSnapshot): String {
        validate(snapshot)
        return adapter.toJson(snapshot).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    fun decode(json: String): SettingsSnapshot {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return requireNotNull(adapter.fromJson(json)).also(::validate)
    }

    fun validate(snapshot: SettingsSnapshot) {
        require(snapshot.format == SettingsSnapshot.FORMAT && snapshot.version == 1)
        val rawStores: Map<*, *> = snapshot.stores
        require(rawStores.values.all { it is Map<*, *> })
        snapshot.stores.forEach { (store, entries) ->
            val rules = requireNotNull(SettingsPolicy.rules[store]) { "Unsupported settings section" }
            val rawEntries: Map<*, *> = entries
            require(rawEntries.values.all { it is SettingValue })
            entries.forEach { (key, value) ->
                val rule = requireNotNull(rules[key]) { "Unsupported setting" }
                require(rule(value.unpack())) { "Invalid setting value" }
            }
        }
        snapshot.stocks?.let { stocks ->
            val rawStocks: List<*> = stocks
            require(rawStocks.all { it is StockWidgetSettings })
            stocks.forEach(StockWidgetPreferences::requireValid)
        }
        snapshot.clocks?.let { clocks ->
            val rawClocks: List<*> = clocks
            require(rawClocks.all { it is ClockPreset })
            clocks.forEach(ClockPreset::validate)
        }
        snapshot.rememberedWatchlist?.let {
            require(it.length <= 32768)
            if (it.isNotEmpty()) {
                val groups = requireNotNull(StockWatchlist.parse(it))
                StockWidgetPreferences.requireValid(StockWidgetSettings(StockWatchlist.symbols(groups), groups = groups))
            }
        }

    }
}
