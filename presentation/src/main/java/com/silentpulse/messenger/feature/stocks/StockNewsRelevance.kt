package com.silentpulse.messenger.feature.stocks

import java.text.Normalizer
import java.util.Locale

object StockNewsRelevance {
    fun headlineNamesInstrument(
        headline: String,
        symbol: String,
        name: String?,
        instrumentType: String?
    ): Boolean {
        val title = words(headline)
        val names = mutableListOf<String>()
        names.addAll(brandNames[symbol].orEmpty())
        names.addAll(StockAssets.entries.find { it.symbol == symbol }?.aliases.orEmpty())
        name?.takeIf { it.isNotBlank() && !it.equals(symbol, ignoreCase = true) }?.let {
            names.add(it)
            if (instrumentType == null || instrumentType == "EQUITY") names.add(companyName(it))
            if (instrumentType == "CRYPTOCURRENCY") names.add(it.replace(Regex("\\s+[A-Z]{3}$"), ""))
        }
        if (names.map(::words).filter { it.length >= 2 }.any { " $title ".contains(" $it ") }) return true

        val bare = symbol.removePrefix("^")
        val escaped = Regex.escape(bare)
        // Short/common tickers must have an explicit financial marker, not merely an English word.
        val marked = Regex("(?:\\$\\s*|\\(\\s*|\\b(?:NASDAQ|NYSE|AMEX)\\s*:\\s*)$escaped(?![A-Za-z0-9])",
            RegexOption.IGNORE_CASE)
        if (marked.containsMatchIn(headline)) return true
        return bare.length >= 3 && bare !in commonWords &&
            Regex("(?<![A-Za-z0-9])$escaped(?![A-Za-z0-9])").containsMatchIn(headline)
    }

    private fun companyName(name: String): String {
        val parts = words(name).split(" ").toMutableList()
        if (parts.firstOrNull() == "the") parts.removeAt(0)
        if (parts.size > 2 && parts[parts.lastIndex - 1] == "class" && parts.last() in setOf("a", "b", "c")) {
            repeat(2) { parts.removeAt(parts.lastIndex) }
        }
        while (parts.lastOrNull() in corporateSuffixes) parts.removeAt(parts.lastIndex)
        return parts.joinToString(" ")
    }

    private fun words(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private val corporateSuffixes = setOf(
        "inc", "incorporated", "corp", "corporation", "company", "co", "ltd", "limited",
        "plc", "holdings", "holding", "group", "sa", "se", "nv", "ag", "llc"
    )
    private val commonWords = setOf("ALL", "ARE", "FOR", "ONE", "NOW", "OUT", "SEE", "AI", "IT", "ON")
    // Conservative headline brands, not the voice router's broader aliases like "wells" or "square".
    private val brandNames = mapOf(
        "AAPL" to listOf("Apple"),
        "GOOG" to listOf("Google", "Alphabet"),
        "GOOGL" to listOf("Google", "Alphabet"),
        "META" to listOf("Meta", "Facebook"),
        "AMZN" to listOf("Amazon"),
        "TMUS" to listOf("T-Mobile"),
        "F" to listOf("Ford"),
        "A" to listOf("Agilent"),
        "BRK-A" to listOf("Berkshire Hathaway"),
        "BRK-B" to listOf("Berkshire Hathaway")
    )
}
