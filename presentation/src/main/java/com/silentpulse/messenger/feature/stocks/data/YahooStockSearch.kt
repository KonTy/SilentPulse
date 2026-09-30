package com.silentpulse.messenger.feature.stocks.data

import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder

data class StockSearchResult(
    val symbol: String,
    val name: String,
    val exchange: String = "",
    val type: String = ""
)

/** User-initiated financial instrument lookup; never receives app content or group names. */
class YahooStockSearch(private val request: (String) -> String = { YahooStockHttp.get(it) }) {
    fun search(rawQuery: String): List<StockSearchResult> {
        require(validQuery(rawQuery)) { "Invalid stock search query" }
        YahooStockHttp.checkInterrupted()
        val query = rawQuery.trim().removePrefix("$")
        val url = "https://query1.finance.yahoo.com/v1/finance/search" +
            "?q=${URLEncoder.encode(query, "UTF-8")}&quotesCount=30&newsCount=0&listsCount=0"
        val body = try {
            request(url)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
        YahooStockHttp.checkInterrupted()
        if (body.length > YahooStockHttp.MAX_RESPONSE_BYTES) throw StockResponseException()
        val response = try {
            adapter.fromJson(body) ?: throw StockResponseException()
        } catch (_: JsonDataException) {
            throw StockResponseException()
        } catch (_: IOException) {
            throw StockResponseException()
        }
        val quotes = response.quotes ?: throw StockResponseException()
        val results = quotes.mapNotNull { quote ->
            if (quote == null) {
                Timber.w("Discarded missing stock search result")
                return@mapNotNull null
            }
            if (quote.isYahooFinance == false) return@mapNotNull null
            val symbol = quote.symbol?.let(StockSymbols::normalize)
            if (symbol == null) {
                Timber.w("Discarded unsupported stock search result")
                return@mapNotNull null
            }
            val name = sequenceOf(quote.longname, quote.shortname)
                .filterNotNull().map(String::trim).firstOrNull(String::isNotEmpty) ?: symbol
            StockSearchResult(
                symbol, name,
                quote.exchDisp?.trim().orEmpty().ifEmpty { quote.exchange?.trim().orEmpty() },
                quote.typeDisp?.trim().orEmpty().ifEmpty { quote.quoteType?.trim().orEmpty() }
            )
        }.distinctBy { it.symbol }.take(30)
        YahooStockHttp.checkInterrupted()
        return results
    }

    companion object {
        fun validQuery(rawQuery: String): Boolean {
            val query = rawQuery.trim().removePrefix("$")
            return query.length in 1..120 && query.none(Char::isISOControl)
        }

        private val adapter = Moshi.Builder().add(StrictStockScalars).build()
            .adapter(YahooStockSearchResponse::class.java)
    }
}

@JsonClass(generateAdapter = true)
internal data class YahooStockSearchResponse(val quotes: List<YahooStockSearchQuote?>? = null)

@JsonClass(generateAdapter = true)
internal data class YahooStockSearchQuote(
    val symbol: String? = null,
    val shortname: String? = null,
    val longname: String? = null,
    val exchange: String? = null,
    val exchDisp: String? = null,
    val quoteType: String? = null,
    val typeDisp: String? = null,
    val isYahooFinance: Boolean? = null
)
