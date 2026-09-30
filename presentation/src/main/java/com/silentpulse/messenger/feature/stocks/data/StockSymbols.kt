package com.silentpulse.messenger.feature.stocks.data

import java.util.Locale

object StockSymbols {
    private val ticker = Regex("\\^?[A-Z0-9]+(?:[.-][A-Z0-9]+)*(?:=[FX])?")

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.length !in 1..24 || trimmed.any { it.code > 127 }) return null
        return trimmed.uppercase(Locale.ROOT).takeIf(ticker::matches)
    }
}
