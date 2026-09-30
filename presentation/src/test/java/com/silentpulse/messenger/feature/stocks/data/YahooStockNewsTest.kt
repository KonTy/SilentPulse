package com.silentpulse.messenger.feature.stocks.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CancellationException

class YahooStockNewsTest {
    @Test
    fun `headlines must be tagged with the requested ticker and retain publisher date and link`() {
        val news = YahooStockNews { """
            {"news":[
              {"title":"Company update","publisher":"Example publisher","link":"https://finance.yahoo.com/news/company-update.html","providerPublishTime":1790712000,"relatedTickers":["AAPL",null]},
              {"title":"Other company","link":"https://finance.yahoo.com/news/other.html","relatedTickers":["MSFT"]},
              {"title":"Untargeted story","link":"https://finance.yahoo.com/news/untargeted.html"}
            ]}
        """ }.fetch("aapl")
        assertEquals(1, news.size)
        assertEquals("Company update", news[0].title)
        assertEquals("Example publisher", news[0].publisher)
        assertEquals(1790712000000L, news[0].publishedAtMillis)
        assertEquals("https://finance.yahoo.com/news/company-update.html", news[0].url)
        assertEquals(listOf("AAPL"), news[0].relatedTickers)
    }

    @Test
    fun `unapproved or malformed article URLs never become clickable news`() {
        listOf(
            "javascript:alert(1)", "intent://article", "http://finance.yahoo.com/news/a",
            "https://finance.yahoo.com.evil.test/news/a", "https://evil@finance.yahoo.com/news/a",
            "https://finance.yahoo.com:8443/news/a", "https://127.0.0.1/news/a",
            "https://finance.yahoo.com", "https://finance.yahoo.com/", "https://finance.yahoo.com/news/a\n"
        ).forEach { assertFalse(it, StockNewsLinks.isArticle(it)) }
        assertTrue(StockNewsLinks.isArticle("https://finance.yahoo.com/markets/stocks/articles/story.html"))
        assertTrue(StockNewsLinks.isArticle("https://uk.finance.yahoo.com/news/story.html"))
    }

    @Test
    fun `unknown dates stay unknown and duplicate links keep the newest result`() {
        val news = YahooStockNews { """
            {"news":[
              {"title":"Older","link":"https://finance.yahoo.com/news/a","providerPublishTime":100,"relatedTickers":["AAPL"]},
              {"title":"Newer","link":"https://finance.yahoo.com/news/a","providerPublishTime":200,"relatedTickers":["AAPL"]},
              {"title":"Undated","link":"https://finance.yahoo.com/news/b","relatedTickers":["AAPL"]},
              {"title":"Invalid time","link":"https://finance.yahoo.com/news/c","providerPublishTime":-1,"relatedTickers":["AAPL"]}
            ]}
        """ }.fetch("AAPL")
        assertEquals(listOf("Newer", "Undated"), news.map { it.title })
        assertNull(news[1].publishedAtMillis)
    }

    @Test
    fun `news request sends only the encoded symbol to the approved service`() {
        var address = ""
        YahooStockNews { address = it; """{"news":[]}""" }.fetch("^GSPC")
        val uri = URI(address)
        assertEquals("https", uri.scheme)
        assertEquals("query1.finance.yahoo.com", uri.host)
        assertEquals("/v1/finance/search", uri.path)
        val query = uri.rawQuery.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals("^GSPC", query["q"])
        assertEquals("0", query["quotesCount"])
        assertEquals("10", query["newsCount"])
        assertEquals("0", query["listsCount"])
    }

    @Test
    fun `provider failures are errors rather than an empty news feed`() {
        for (body in listOf("{}", """{"news":null}""", "not json", """{"news":[{"title":42}]}""")) {
            assertThrows(StockResponseException::class.java) { YahooStockNews { body }.fetch("AAPL") }
        }
        val failure = IOException()
        assertSame(failure, assertThrows(IOException::class.java) { YahooStockNews { throw failure }.fetch("AAPL") })
        val cancelled = CancellationException()
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            YahooStockNews { throw cancelled }.fetch("AAPL")
        })
        assertTrue(YahooStockNews { """{"news":[]}""" }.fetch("FXAIX").isEmpty())
    }

    @Test
    fun `broader mentions are retained with their actual Yahoo tags rather than dropped or called primary`() {
        val articles = YahooStockNews { """
            {"news":[
              {"title":"Apple releases an update","link":"https://finance.yahoo.com/news/apple-update","relatedTickers":["AAPL"]},
              {"title":"T-Mobile changes fees","link":"https://finance.yahoo.com/news/mobile-fees","relatedTickers":["TMUS","AAPL"]}
            ]}
        """ }.fetch("AAPL")
        assertEquals(2, articles.size)
        assertEquals(listOf("TMUS", "AAPL"), articles[1].relatedTickers)
    }
}
