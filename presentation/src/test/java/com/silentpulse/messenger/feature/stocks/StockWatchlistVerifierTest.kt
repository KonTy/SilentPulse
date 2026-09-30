package com.silentpulse.messenger.feature.stocks

import com.silentpulse.messenger.feature.stocks.data.StockQuote
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class StockWatchlistVerifierTest {
    private fun quote(symbol: String) = StockQuote(symbol, symbol, "USD", 10.0, 9.0, 1_000, emptyList())

    @Test
    fun `new symbols are verified once and existing verified symbols are not re-requested`() {
        val fetched = mutableListOf<String>()
        val result = StockWatchlistVerifier { fetched.add(it); quote(it) }
            .verify(listOf("AAPL", "GC=F", "GC=F"), mapOf("AAPL" to quote("AAPL")))
        assertEquals(listOf("GC=F"), fetched)
        assertEquals(setOf("AAPL", "GC=F"), result.keys)
    }

    @Test
    fun `unavailable or unknown symbol rejects the complete verification result`() {
        val failure = assertThrows(StockVerificationException::class.java) {
            StockWatchlistVerifier { if (it == "BAD") throw IOException() else quote(it) }
                .verify(listOf("AAPL", "BAD"))
        }
        assertEquals("BAD", failure.symbol)
    }

    @Test
    fun `cancellation does not turn into an invalid ticker or continue requests`() {
        var requests = 0
        assertThrows(CancellationException::class.java) {
            StockWatchlistVerifier { requests++; quote(it) }.verify(listOf("AAPL")) { _, _, _ ->
                throw CancellationException()
            }
        }
        assertEquals(0, requests)
    }

    @Test
    fun `response for a different instrument is rejected`() {
        assertThrows(StockVerificationException::class.java) {
            StockWatchlistVerifier { quote("MSFT") }.verify(listOf("AAPL"))
        }
    }
}
