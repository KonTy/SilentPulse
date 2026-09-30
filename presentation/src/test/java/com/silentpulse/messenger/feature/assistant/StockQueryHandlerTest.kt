package com.silentpulse.messenger.feature.assistant

import android.content.Context
import com.silentpulse.messenger.feature.stocks.data.StockQuote
import com.silentpulse.messenger.feature.stocks.data.YahooStockClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class StockQueryHandlerTest {
    private val handler = StockQueryHandler(mock(Context::class.java))

    @Test
    fun `only explicit stock price commands activate the quote handler`() {
        listOf(
            "stock price apple",
            "stock price Microsoft",
            "stock price crude oil",
            "stock price gold",
            "  STOCK   PRICE: MSFT  ",
            "stock prices bitcoin",
            "stock price"
        ).forEach { command -> assertTrue(command, handler.isStockQuery(command)) }
        listOf(
            "open Apple Music",
            "play gold",
            "what is the weather",
            "navigate to Seattle",
            "price of MSFT",
            "what is Microsoft",
            "what is Apple Music",
            "what is a gold standard",
            "what is an oracle",
            "what is stock price",
            "what is the stock price of Apple",
            "how much is bitcoin",
            "price of gold",
            "stock pricing rules",
            "stock priceable assets"
        ).forEach { command -> assertFalse(command, handler.isStockQuery(command)) }
    }

    @Test
    fun `explicit prefix supports company commodity crypto and raw ticker targets`() {
        mapOf(
            "stock price gold" to "GC=F",
            "stock price of silver" to "SI=F",
            "stock price platinum" to "PL=F",
            "stock price crude oil" to "CL=F",
            "stock price brent" to "BZ=F",
            "stock price bitcoin" to "BTC-USD",
            "stock price ethereum" to "ETH-USD",
            "stock price google stock" to "GOOGL",
            "stock price jp morgan" to "JPM",
            "stock price bank of america" to "BAC",
            "stock price Microsoft" to "MSFT",
            "stock price Apple shares today please" to "AAPL",
            "stock price MSFT stock" to "MSFT"
        ).forEach { (command, expected) ->
            assertEquals(command, expected, extractTicker(command))
        }
    }

    @Test
    fun `non-price questions and missing or unrelated assets never reach the network`() {
        val context = mock(Context::class.java)
        val local = StockQueryHandler(context, YahooStockClient { throw AssertionError("Must not request") })
        for (command in listOf("what is Microsoft", "price of gold", "stock price", "stock price apple pie")) {
            var answer = ""
            local.fetchPrice(command) { answer = it }
            assertTrue(answer.startsWith("Say stock price"))
        }
        verifyNoInteractions(context)
    }

    @Test
    fun `shared chart previous close produces a spoken decline`() {
        val quote = YahooStockClient {
            """{"chart":{"result":[{"meta":{"symbol":"AAPL","longName":"Apple Inc.","currency":"USD","regularMarketPrice":329.4,"chartPreviousClose":338.4,"regularMarketTime":1790712000}}],"error":null}}"""
        }.fetch("AAPL")
        assertEquals(
            "Apple Inc. is currently at 329.40 dollars per share, down 9.00 (2.66 percent) from the previous close.",
            formatStockQuote(quote)
        )
    }

    @Test
    fun `rise flat missing baseline and zero baseline use honest change labels`() {
        assertTrue(formatStockQuote(quote(price = 110.0)).endsWith("up 10.00 (10.00 percent) from the previous close."))
        assertTrue(formatStockQuote(quote()).endsWith("unchanged from the previous close."))
        assertEquals(
            "Example is currently at 100.00 dollars per share.",
            formatStockQuote(quote(previous = null))
        )
        assertTrue(formatStockQuote(quote(previous = 0.0)).endsWith("up 100.00 from the previous close."))
    }

    @Test
    fun `currency labels never prepend a misleading dollar sign or treat pence as pounds`() {
        mapOf(
            "USD" to "dollars", "EUR" to "euros", "GBP" to "pounds",
            "GBp" to "pence", "GBX" to "pence", "CAD" to "Canadian dollars",
            "AUD" to "Australian dollars", "HKD" to "HKD", "JPY" to "JPY",
            "ZAc" to "South African cents", "ILA" to "Israeli agorot"
        ).forEach { (currency, label) ->
            val answer = formatStockQuote(quote(currency = currency, previous = null))
            assertEquals("Example is currently at 100.00 $label per share.", answer)
            assertFalse(answer.contains('$'))
        }
    }

    @Test
    fun `futures and cryptocurrency retain their existing unit labels`() {
        mapOf(
            "GC=F" to " per troy ounce",
            "SI=F" to " per troy ounce",
            "PL=F" to " per troy ounce",
            "CL=F" to " per barrel",
            "BZ=F" to " per barrel",
            "ZW=F" to "",
            "BTC-USD" to "",
            "ETH-USD" to "",
            "AAPL" to " per share"
        ).forEach { (symbol, unit) ->
            assertEquals(
                "Example is currently at 100.00 dollars$unit.",
                formatStockQuote(quote(symbol = symbol, previous = null))
            )
        }
    }

    private fun extractTicker(command: String): String? {
        val method = StockQueryHandler::class.java.getDeclaredMethod("extractTicker", String::class.java)
        method.isAccessible = true
        return method.invoke(handler, command) as String?
    }

    private fun quote(
        symbol: String = "AAPL",
        currency: String = "USD",
        price: Double = 100.0,
        previous: Double? = 100.0
    ): StockQuote = StockQuote(symbol, "Example", currency, price, previous, 1790712000000L, emptyList())
}
