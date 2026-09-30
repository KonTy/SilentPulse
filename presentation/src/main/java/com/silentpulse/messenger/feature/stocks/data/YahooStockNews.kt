package com.silentpulse.messenger.feature.stocks.data

import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import timber.log.Timber
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.Locale

data class StockNewsArticle(
    val title: String,
    val publisher: String,
    val url: String,
    val publishedAtMillis: Long?,
    val relatedTickers: List<String> = emptyList()
)

object StockNewsLinks {
    fun isArticle(url: String): Boolean {
        if (url.any(Char::isISOControl)) return false
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) &&
                (host == "finance.yahoo.com" || host.endsWith(".finance.yahoo.com")) &&
                !uri.path.isNullOrBlank() && uri.path != "/"
        } catch (_: URISyntaxException) {
            false
        }
    }
}

class YahooStockNews(private val request: (String) -> String = { YahooStockHttp.get(it) }) {
    fun fetch(symbol: String): List<StockNewsArticle> {
        val ticker = StockSymbols.normalize(symbol) ?: throw StockResponseException()
        YahooStockHttp.checkInterrupted()
        val body = try {
            request("https://query1.finance.yahoo.com/v1/finance/search" +
                "?q=${URLEncoder.encode(ticker, "UTF-8")}&quotesCount=0&newsCount=10&listsCount=0")
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
        val news = response.news ?: throw StockResponseException()
        val articles = news.mapNotNull { item ->
            val related = item?.relatedTickers.orEmpty().mapNotNull { it?.let(StockSymbols::normalize) }.distinct()
            if (item == null || ticker !in related) {
                return@mapNotNull null
            }
            val title = item.title?.trim()
            val link = item.link
            val seconds = item.providerPublishTime
            if (title.isNullOrEmpty() || link == null || !StockNewsLinks.isArticle(link) ||
                (seconds != null && (seconds <= 0 || seconds > Long.MAX_VALUE / 1_000L))) {
                Timber.w("Discarded invalid stock news entry")
                return@mapNotNull null
            }
            StockNewsArticle(title, item.publisher?.trim().orEmpty(), link, seconds?.times(1_000L), related)
        }.sortedByDescending { it.publishedAtMillis ?: 0L }.distinctBy { it.url }.take(10)
        YahooStockHttp.checkInterrupted()
        return articles
    }

    companion object {
        private val adapter = Moshi.Builder().add(StrictStockScalars).build()
            .adapter(YahooStockNewsResponse::class.java)
    }
}

@JsonClass(generateAdapter = true)
internal data class YahooStockNewsResponse(val news: List<YahooStockNewsEntry?>? = null)

@JsonClass(generateAdapter = true)
internal data class YahooStockNewsEntry(
    val title: String? = null,
    val publisher: String? = null,
    val link: String? = null,
    val providerPublishTime: Long? = null,
    val relatedTickers: List<String?>? = null
)
