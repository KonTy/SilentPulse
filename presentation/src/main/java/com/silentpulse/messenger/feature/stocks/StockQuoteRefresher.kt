package com.silentpulse.messenger.feature.stocks

import com.silentpulse.messenger.feature.stocks.data.StockQuote
import timber.log.Timber
import java.io.IOException

class StockQuoteRefresher(
    private val cache: StockQuoteCache,
    private val fetch: (String) -> StockQuote,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun refresh(
        intervals: Map<String, Long>,
        forced: Set<String> = emptySet(),
        online: Boolean = true,
        isStopped: () -> Boolean = { false },
        isCurrent: (String) -> Boolean = { true },
        onChanged: () -> Unit = {}
    ) {
        for ((symbol, interval) in intervals) {
            if (isStopped() || Thread.currentThread().isInterrupted) return
            if (!isCurrent(symbol)) continue
            val old = cache.load(symbol)
            val startedAt = now()
            if (!old.needsRefresh(startedAt, interval, symbol in forced)) continue
            val updated = try {
                if (!online) throw IOException("Offline")
                CachedStockQuote(fetch(symbol), now(), startedAt)
            } catch (_: IOException) {
                if (isStopped() || Thread.currentThread().isInterrupted) return
                Timber.w("Stock quote refresh unavailable")
                old.copy(attemptedAt = startedAt, failed = true)
            }
            // A removed/reconfigured widget must not resurrect an abandoned watchlist cache.
            if (!isStopped() && !Thread.currentThread().isInterrupted && isCurrent(symbol)) {
                cache.save(symbol, updated)
                onChanged()
            }
        }
    }
}
