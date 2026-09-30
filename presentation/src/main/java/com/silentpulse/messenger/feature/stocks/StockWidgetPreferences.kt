package com.silentpulse.messenger.feature.stocks

import android.content.Context
import android.content.SharedPreferences
import com.silentpulse.messenger.feature.stocks.data.StockSymbols
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import timber.log.Timber
import java.io.IOException

@JsonClass(generateAdapter = true)
data class StockWidgetSettings(
    val symbols: List<String>,
    val multiline: Boolean = false,
    val charts: Boolean = true,
    val twoColumns: Boolean = true,
    val dark: Boolean = false,
    val refreshMinutes: Int = 15,
    val dense: Boolean = false,
    val transparent: Boolean = false,
    val showCurrency: Boolean = true,
    val groups: List<StockGroup> = emptyList(),
    val groupStyle: StockGroupStyle = StockGroupStyle.LINES,
    val followAppTheme: Boolean = true,
    val fontScalePercent: Int = 100
)

class StockWidgetPreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences("stock_widgets", Context.MODE_PRIVATE)
    )

    fun load(id: Int): StockWidgetSettings? {
        val raw = preferences.getString("$id.symbols", null) ?: return null
        val symbols = raw.trim().split(Regex("[,\\s]+")).map { StockSymbols.normalize(it) }
            .takeIf { list -> list.isNotEmpty() && list.all { it != null } }?.filterNotNull()?.distinct()
        val refresh = preferences.getInt("$id.refresh", 15)
        val multiline = preferences.getBoolean("$id.multiline", false)
        val dense = preferences.getBoolean("$id.dense", false)
        val fontScale = preferences.getInt("$id.font_scale", 100)
        val groups = readGroups(id)
        val style = preferences.getString("$id.group_style", StockGroupStyle.LINES.name)
            ?.let { name -> StockGroupStyle.values().find { it.name == name } }
        if (symbols == null || refresh !in refreshIntervals || (multiline && dense) || groups == null ||
            style == null || fontScale !in MIN_FONT_SCALE..MAX_FONT_SCALE || fontScale % FONT_SCALE_STEP != 0 ||
            (groups.isNotEmpty() && StockWatchlist.symbols(groups) != symbols)) {
            Timber.w("Stock widget has invalid settings; configuration is required")
            return null
        }
        return StockWidgetSettings(
            symbols = symbols,
            multiline = multiline,
            charts = preferences.getBoolean("$id.charts", true),
            twoColumns = preferences.getBoolean("$id.columns", true),
            dark = preferences.getBoolean("$id.dark", false),
            refreshMinutes = refresh,
            dense = dense,
            transparent = preferences.getBoolean("$id.transparent", false),
            showCurrency = preferences.getBoolean("$id.currency", true),
            groups = groups,
            groupStyle = style,
            followAppTheme = preferences.getBoolean("$id.follow_theme", true),
            fontScalePercent = fontScale
        )
    }

    fun save(id: Int, settings: StockWidgetSettings) {
        require(id > 0)
        requireValid(settings)
        preferences.edit().write(id, settings)
            .putString(REMEMBERED_WATCHLIST, StockWatchlist.format(StockWatchlist.effectiveGroups(settings))).apply()
    }

    fun updateAppearance(id: Int, appearance: StockWidgetAppearance): Boolean {
        require(id > 0)
        val current = load(id) ?: return false
        val updated = appearance.applyTo(current)
        if (updated == current) return false
        save(id, updated)
        return true
    }

    fun remove(id: Int) {
        val settings = load(id)
        preferences.edit().apply {
            if (settings != null) putString(REMEMBERED_WATCHLIST, StockWatchlist.format(StockWatchlist.effectiveGroups(settings)))
            removeWidget(id)
        }.apply()
    }

    fun rememberedWatchlist(): String {
        val raw = preferences.getString(REMEMBERED_WATCHLIST, null) ?: return ""
        if (StockWatchlist.parse(raw) != null) return raw
        Timber.w("Saved stock watchlist is invalid; re-entry is required")
        return ""
    }

    fun restore(oldIds: IntArray, newIds: IntArray) {
        require(oldIds.size == newIds.size && newIds.all { it > 0 })
        val settings = oldIds.map(::load)
        val editor = preferences.edit()
        oldIds.forEach { editor.removeWidget(it) }
        newIds.forEachIndexed { index, id ->
            editor.removeWidget(id)
            settings[index]?.let { editor.write(id, it) }
        }
        editor.apply()
    }

    private fun SharedPreferences.Editor.write(id: Int, settings: StockWidgetSettings) = apply {
        putString("$id.symbols", settings.symbols.joinToString("\n"))
        putBoolean("$id.multiline", settings.multiline)
        putBoolean("$id.charts", settings.charts)
        putBoolean("$id.columns", settings.twoColumns)
        putBoolean("$id.dark", settings.dark)
        putInt("$id.refresh", settings.refreshMinutes)
        putBoolean("$id.dense", settings.dense)
        putBoolean("$id.transparent", settings.transparent)
        putBoolean("$id.currency", settings.showCurrency)
        putString("$id.groups", groupAdapter.toJson(settings.groups))
        putString("$id.group_style", settings.groupStyle.name)
        putBoolean("$id.follow_theme", settings.followAppTheme)
        putInt("$id.font_scale", settings.fontScalePercent)
    }

    private fun SharedPreferences.Editor.removeWidget(id: Int) = apply {
        listOf("symbols", "multiline", "charts", "columns", "dark", "refresh", "dense", "transparent", "currency",
            "groups", "group_style", "follow_theme", "font_scale")
            .forEach { remove("$id.$it") }
    }

    private fun readGroups(id: Int): List<StockGroup>? {
        val json = preferences.getString("$id.groups", null) ?: return emptyList()
        return try {
            groupAdapter.fromJson(json)?.takeIf(::validGroups)
        } catch (_: IOException) {
            Timber.w("Invalid stock widget groups")
            null
        } catch (_: JsonDataException) {
            Timber.w("Invalid stock widget groups")
            null
        }
    }

    companion object {
        private const val REMEMBERED_WATCHLIST = "remembered_watchlist"
        private val groupAdapter = Moshi.Builder().build().adapter<List<StockGroup>>(
            Types.newParameterizedType(List::class.java, StockGroup::class.java)
        )
        val refreshIntervals = listOf(15, 30, 60)
        const val MIN_FONT_SCALE = 80
        const val MAX_FONT_SCALE = 180
        const val FONT_SCALE_STEP = 5

        fun requireValid(settings: StockWidgetSettings) {
            val rawSymbols: List<*> = settings.symbols
            val rawGroups: List<*> = settings.groups
            require(rawSymbols.all { it is String } && rawGroups.all { it is StockGroup })
            settings.groups.forEach { group ->
                val symbols: List<*> = group.symbols
                require(symbols.all { it is String })
            }
            require(settings.symbols.isNotEmpty() &&
                settings.symbols.all { StockSymbols.normalize(it) == it })
            require(settings.symbols.distinct() == settings.symbols)
            require(settings.refreshMinutes in refreshIntervals)
            require(settings.fontScalePercent in MIN_FONT_SCALE..MAX_FONT_SCALE &&
                settings.fontScalePercent % FONT_SCALE_STEP == 0)
            require(!settings.multiline || !settings.dense)
            require(validGroups(settings.groups))
            require(settings.groups.isEmpty() || StockWatchlist.symbols(settings.groups) == settings.symbols)
        }

        fun parseSymbols(raw: String): List<String>? =
            StockWatchlist.parse(raw)?.let(StockWatchlist::symbols)

        private fun validGroups(groups: List<StockGroup>): Boolean = groups.all {
            (it.name.isEmpty() || StockWatchlist.validGroupName(it.name)) &&
                it.symbols.isNotEmpty() && it.symbols.distinct() == it.symbols &&
                it.symbols.all { symbol -> StockSymbols.normalize(symbol) == symbol }
        }
    }
}
