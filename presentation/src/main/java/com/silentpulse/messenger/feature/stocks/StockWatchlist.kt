package com.silentpulse.messenger.feature.stocks

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class StockGroup(val name: String, val symbols: List<String>) {
    val hasSeparator: Boolean get() = name.trimEnd().endsWith("-")
    val displayName: String get() =
        if (hasSeparator) name.trimEnd().dropLast(1).trimEnd().ifBlank { name } else name
}

enum class StockGroupStyle { LINES, BORDERS, NONE }

object StockWatchlist {
    fun validGroupName(name: String): Boolean =
        name.isNotBlank() && name.length <= 40 && name.none { it in "[]\r\n" }

    fun parse(raw: String): List<StockGroup>? {
        val result = mutableListOf<StockGroup>()
        var name = ""
        var hasHeading = false
        val symbols = mutableListOf<String>()
        for (line in raw.lineSequence().map(String::trim).filter(String::isNotEmpty)) {
            if (line.startsWith("[") || line.endsWith("]")) {
                if (!line.startsWith("[") || !line.endsWith("]")) return null
                if (hasHeading && symbols.isEmpty()) return null
                if (symbols.isNotEmpty()) result.add(StockGroup(name, symbols.distinct()))
                name = line.substring(1, line.lastIndex).trim()
                if (!validGroupName(name)) return null
                hasHeading = true
                symbols.clear()
            } else {
                for (token in line.split(',').map(String::trim).filter(String::isNotEmpty)) {
                    val asset = StockAssets.resolve(token)
                    if (asset != null) {
                        symbols.add(asset)
                    } else {
                        // Keep legacy whitespace-separated ticker lists, but resolve multiword
                        // commodity names before splitting (for example "crude oil").
                        symbols.addAll(token.split(Regex("\\s+")).map { StockAssets.resolve(it) ?: return null })
                    }
                }
            }
        }
        if (symbols.isEmpty()) return null
        result.add(StockGroup(name, symbols.distinct()))
        return result
    }

    fun format(groups: List<StockGroup>): String = groups.joinToString("\n\n") { group ->
        (if (group.name.isNotEmpty()) "[${group.name}]\n" else "") + group.symbols.joinToString(", ") {
            StockAssets.editorSymbol(it)
        }
    }

    fun effectiveGroups(settings: StockWidgetSettings): List<StockGroup> =
        settings.groups.ifEmpty { listOf(StockGroup("", settings.symbols)) }

    fun symbols(groups: List<StockGroup>): List<String> = groups.flatMap { it.symbols }.distinct()
}

data class StockDisplayRow(
    val groupName: String?,
    val symbols: List<String>,
    val groupStart: Boolean,
    val groupEnd: Boolean,
    val separator: Boolean = false
)

object StockWidgetRows {
    fun build(settings: StockWidgetSettings, columns: Int): List<StockDisplayRow> =
        StockWatchlist.effectiveGroups(settings).flatMap { group ->
            val named = group.name.isNotEmpty()
            val chunks = group.symbols.chunked(columns)
            val header = if (named) listOf(StockDisplayRow(group.displayName, emptyList(), true, false, group.hasSeparator)) else emptyList()
            header + chunks.mapIndexed { index, symbols ->
                StockDisplayRow(null, symbols, !named && index == 0, index == chunks.lastIndex, group.hasSeparator)
            }
        }
}
