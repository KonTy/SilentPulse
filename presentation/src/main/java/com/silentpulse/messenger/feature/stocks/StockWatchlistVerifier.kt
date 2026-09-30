package com.silentpulse.messenger.feature.stocks

import com.silentpulse.messenger.feature.stocks.data.StockQuote
import java.io.IOException
import java.util.concurrent.CancellationException

class StockVerificationException(val symbol: String, cause: IOException) : IOException("Stock verification failed", cause)

class StockWatchlistVerifier(private val fetch: (String) -> StockQuote) {
    fun verify(
        symbols: List<String>,
        existing: Map<String, StockQuote> = emptyMap(),
        beforeFetch: (String, Int, Int) -> Unit = { _, _, _ -> }
    ): Map<String, StockQuote> {
        val result = linkedMapOf<String, StockQuote>()
        val unique = symbols.distinct()
        for ((index, symbol) in unique.withIndex()) {
            beforeFetch(symbol, index + 1, unique.size)
            val quote = existing[symbol] ?: try {
                fetch(symbol)
            } catch (failure: IOException) {
                if (Thread.currentThread().isInterrupted) {
                    throw CancellationException("Stock verification interrupted").apply { initCause(failure) }
                }
                throw StockVerificationException(symbol, failure)
            }
            if (quote.symbol != symbol) throw StockVerificationException(symbol, IOException("Mismatched instrument"))
            result[symbol] = quote
        }
        return result
    }
}
