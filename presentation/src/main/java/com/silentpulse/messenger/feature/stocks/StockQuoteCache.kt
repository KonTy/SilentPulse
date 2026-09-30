package com.silentpulse.messenger.feature.stocks

import android.content.Context
import android.content.SharedPreferences
import com.silentpulse.messenger.feature.stocks.data.StockQuote
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import timber.log.Timber
import java.io.IOException

@JsonClass(generateAdapter = true)
data class CachedStockQuote(
    val quote: StockQuote? = null,
    val fetchedAt: Long = 0,
    val attemptedAt: Long = 0,
    val failed: Boolean = false
) {
    fun isStale(now: Long, intervalMillis: Long): Boolean =
        failed || fetchedAt <= 0 || fetchedAt > now || now - fetchedAt >= intervalMillis * 2

    fun needsRefresh(now: Long, intervalMillis: Long, force: Boolean): Boolean {
        val elapsed = now - attemptedAt
        return attemptedAt <= 0 || elapsed < 0 ||
            elapsed >= if (force) MANUAL_COOLDOWN_MILLIS else intervalMillis
    }

    companion object {
        const val MANUAL_COOLDOWN_MILLIS = 60_000L
    }
}

class StockQuoteCache(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences("stock_quote_cache", Context.MODE_PRIVATE)
    )

    fun load(symbol: String): CachedStockQuote {
        val json = preferences.getString(symbol, null) ?: return CachedStockQuote()
        return try {
            val entry = adapter.fromJson(json) ?: throw JsonDataException("Missing cached quote")
            val quote = entry.quote
            if (entry.fetchedAt < 0 || entry.attemptedAt < 0 || (quote != null &&
                    (quote.symbol != symbol || !quote.price.isFinite() || quote.marketTimeMillis <= 0 ||
                        quote.currency.isBlank() || quote.points.any { it.price?.isFinite() == false }))) {
                throw JsonDataException("Invalid cached quote")
            }
            entry
        } catch (_: JsonDataException) {
            invalidCache()
        } catch (_: IOException) {
            invalidCache()
        }
    }

    fun save(symbol: String, entry: CachedStockQuote) {
        preferences.edit().putString(symbol, adapter.toJson(entry)).apply()
    }

    fun retain(symbols: Set<String>) {
        val editor = preferences.edit()
        preferences.all.keys.filterNot { it in symbols }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun invalidCache(): CachedStockQuote {
        Timber.w("Invalid stock quote cache; a fresh quote is required")
        return CachedStockQuote(failed = true)
    }

    companion object {
        private val adapter = Moshi.Builder().build().adapter(CachedStockQuote::class.java)
    }
}
