package com.silentpulse.messenger.feature.stocks

import com.silentpulse.messenger.feature.stocks.data.StockChartPoint
import com.silentpulse.messenger.feature.stocks.data.StockQuote
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StockQuoteRefresherTest {
    private val interval = 900_000L
    private val now = 1_800_000_000_000L
    private fun quote(symbol: String = "AAPL") =
        StockQuote(symbol, "Example", "USD", 101.0, 100.0, now - 3_600_000,
            listOf(StockChartPoint(now - 4_000_000, 100.0), StockChartPoint(now - 3_600_000, 101.0)))

    @Test
    fun `overlapping watchlists fetch each symbol once at the shortest requested interval`() {
        assertEquals(mapOf("AAPL" to 900_000L, "MSFT" to 3_600_000L),
            StockWidgetWorker.intervalsFor(listOf(
                StockWidgetSettings(listOf("AAPL", "MSFT"), refreshMinutes = 60),
                StockWidgetSettings(listOf("AAPL"), refreshMinutes = 15)
            )))
    }

    @Test
    fun `successful refresh persists quote with market timestamp distinct from fetch time`() {
        val store = StockTestStore()
        var notifications = 0
        StockQuoteRefresher(store.cache, { quote(it) }, { now })
            .refresh(mapOf("AAPL" to interval), onChanged = { notifications++ })
        val entry = store.cache.load("AAPL")
        assertEquals(quote(), entry.quote)
        assertEquals(now, entry.fetchedAt)
        assertFalse(entry.failed)
        assertFalse(entry.isStale(now, interval))
        assertEquals(1, notifications)
    }

    @Test
    fun `failure keeps old price marked cached and never substitutes zero`() {
        val store = StockTestStore()
        val old = CachedStockQuote(quote(), now - interval, now - interval)
        store.cache.save("AAPL", old)
        StockQuoteRefresher(store.cache, { throw IOException("HTTP 429") }, { now })
            .refresh(mapOf("AAPL" to interval, "MSFT" to interval))
        val cached = store.cache.load("AAPL")
        assertEquals(old.quote, cached.quote)
        assertEquals(old.fetchedAt, cached.fetchedAt)
        assertTrue(cached.failed)
        assertTrue(cached.isStale(now, interval))
        assertNull(store.cache.load("MSFT").quote)
        assertTrue(store.cache.load("MSFT").failed)
    }

    @Test
    fun `one failed symbol does not stop later symbols`() {
        val store = StockTestStore()
        StockQuoteRefresher(store.cache, { if (it == "BAD") throw IOException() else quote(it) }, { now })
            .refresh(mapOf("BAD" to interval, "AAPL" to interval))
        assertTrue(store.cache.load("BAD").failed)
        assertEquals(quote(), store.cache.load("AAPL").quote)
    }

    @Test
    fun `manual refresh bypasses interval but still observes one minute cooldown`() {
        val store = StockTestStore()
        var calls = 0
        var time = now
        val refresher = StockQuoteRefresher(store.cache, { calls++; quote(it) }, { time })
        refresher.refresh(mapOf("AAPL" to interval))
        refresher.refresh(mapOf("AAPL" to interval), setOf("AAPL"))
        assertEquals(1, calls)
        time += 60_000
        refresher.refresh(mapOf("AAPL" to interval))
        assertEquals(1, calls)
        refresher.refresh(mapOf("AAPL" to interval), setOf("AAPL"))
        assertEquals(2, calls)
    }

    @Test
    fun `offline worker marks unavailable without attempting network`() {
        val store = StockTestStore()
        StockQuoteRefresher(store.cache, { fail("must not fetch offline"); quote() }, { now })
            .refresh(mapOf("AAPL" to interval), online = false)
        assertTrue(store.cache.load("AAPL").failed)
    }

    @Test
    fun `removed widget cannot repopulate cache after an in-flight request`() {
        val store = StockTestStore()
        var current = true
        StockQuoteRefresher(store.cache, { current = false; quote() }, { now })
            .refresh(mapOf("AAPL" to interval), isCurrent = { current })
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `stopped work neither fetches nor overwrites cached data`() {
        val store = StockTestStore()
        StockQuoteRefresher(store.cache, { fail("must not fetch"); quote() }, { now })
            .refresh(mapOf("AAPL" to interval), isStopped = { true })
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `expired or future fetch times are stale`() {
        assertTrue(CachedStockQuote(quote(), now - interval * 2, now - interval * 2).isStale(now, interval))
        assertTrue(CachedStockQuote(quote(), now + 1, now + 1).isStale(now, interval))
        assertTrue(CachedStockQuote(attemptedAt = now + 1).needsRefresh(now, interval, false))
    }

    @Test
    fun `corrupt cache surfaces unavailable and orphan symbols are pruned`() {
        val store = StockTestStore()
        store.values["AAPL"] = "{broken"
        assertNull(store.cache.load("AAPL").quote)
        assertTrue(store.cache.load("AAPL").failed)
        store.cache.save("MSFT", CachedStockQuote(quote("MSFT"), now, now))
        store.cache.retain(setOf("MSFT"))
        assertEquals(setOf("MSFT"), store.values.keys)
        store.cache.retain(emptySet())
        assertTrue(store.values.isEmpty())
    }
}
