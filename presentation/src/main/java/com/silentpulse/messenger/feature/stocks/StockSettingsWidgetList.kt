package com.silentpulse.messenger.feature.stocks

internal class StockSettingsWidgetList(
    private val installedIds: () -> IntArray,
    private val isOwnWidget: (Int) -> Boolean,
    private val settings: (Int) -> StockWidgetSettings?
) {
    data class Entry(val widgetId: Int, val settings: StockWidgetSettings?)

    fun load(): List<Entry> = installedIds().distinct().sorted()
        .filter(::canConfigure).map { Entry(it, settings(it)) }

    fun canConfigure(widgetId: Int): Boolean = widgetId > 0 && isOwnWidget(widgetId)
}
