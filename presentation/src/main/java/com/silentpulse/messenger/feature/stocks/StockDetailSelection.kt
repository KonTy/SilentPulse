package com.silentpulse.messenger.feature.stocks

import com.silentpulse.messenger.feature.stocks.data.StockSymbols

object StockDetailSelection {
    fun resolve(rawSymbol: String?, settings: StockWidgetSettings?): String? {
        val symbol = rawSymbol?.let(StockSymbols::normalize) ?: return null
        return symbol.takeIf { settings != null && it in settings.symbols }
    }
}
