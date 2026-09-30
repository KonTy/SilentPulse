package com.silentpulse.messenger.feature.stocks.data

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CancellationException

class YahooStockClientTest {
    @Test
    fun `Fidelity mutual fund NAV is supported without inventing an intraday series`() {
        val quote = YahooStockClient { """
            {"chart":{"error":null,"result":[{
              "meta":{"symbol":"FXAIX","currency":"USD","instrumentType":"MUTUALFUND",
                "regularMarketPrice":267.17,"regularMarketTime":1790726907,
                "chartPreviousClose":267.62003,"longName":"Fidelity 500 Index Fund"},
              "timestamp":[1790726907],
              "indicators":{"quote":[{"close":[267.17]}]}
            }]}}
        """ }.fetch("FXAIX")
        assertEquals("Fidelity 500 Index Fund", quote.name)
        assertEquals(267.17, quote.price, 0.0)
        assertEquals(-0.45003, quote.change!!, 0.00001)
        assertEquals(1, quote.points.size)
    }

    @Test
    fun `chart previous close is used when previousClose is absent`() {
        val quote = fetch(response())

        assertEquals("AAPL", quote.symbol)
        assertEquals("Apple Inc.", quote.name)
        assertEquals("USD", quote.currency)
        assertEquals(329.4, quote.price, 0.0)
        assertEquals(338.4, quote.previousClose!!, 0.0)
        assertEquals(-9.0, quote.change!!, 0.000001)
        assertEquals(-9.0 / 338.4 * 100.0, quote.changePercent!!, 0.000001)
        assertEquals(1790712000000L, quote.marketTimeMillis)
        assertEquals(
            listOf(
                StockChartPoint(1790688600000L, 337.0),
                StockChartPoint(1790688900000L, null),
                StockChartPoint(1790689200000L, 329.4)
            ),
            quote.points
        )
    }

    @Test
    fun `explicit previous close takes precedence over chart baseline and first sample`() {
        val quote = fetch(response(meta = meta(extra = ""","previousClose":300.0""")))
        assertEquals(300.0, quote.previousClose!!, 0.0)
        assertEquals(29.4, quote.change!!, 0.000001)
        assertEquals(9.8, quote.changePercent!!, 0.000001)
    }

    @Test
    fun `nullable previousClose falls back to chartPreviousClose`() {
        assertEquals(
            338.4,
            fetch(response(meta = meta(extra = ""","previousClose":null"""))).previousClose!!,
            0.0
        )
    }

    @Test
    fun `up down flat and missing changes have honest values`() {
        listOf(110.0 to 10.0, 90.0 to -10.0, 100.0 to 0.0).forEach { (price, change) ->
            val quote = fetch(response(meta = meta(price = "$price", previous = "100.0")))
            assertEquals(change, quote.change!!, 0.000001)
            assertEquals(change, quote.changePercent!!, 0.000001)
        }
        val missing = fetch(response(meta = meta(previous = null)))
        assertNull(missing.previousClose)
        assertNull(missing.change)
        assertNull(missing.changePercent)
        // The first intraday sample is not the previous trading day's close.
        assertEquals(337.0, missing.points.first().price!!, 0.0)
    }

    @Test
    fun `actual zero and negative prices are not mistaken for missing values`() {
        listOf("0" to 0.0, "-37.63" to -37.63).forEach { (json, expected) ->
            assertEquals(expected, fetch(response(meta = meta(price = json))).price, 0.0)
        }
        val zeroBaseline = fetch(response(meta = meta(previous = "0")))
        assertEquals(329.4, zeroBaseline.change!!, 0.0)
        assertNull(zeroBaseline.changePercent)
    }

    @Test
    fun `name falls back to short name then verified symbol`() {
        assertEquals(
            "Apple",
            fetch(response(meta = meta(name = null, extra = ""","shortName":"Apple""""))).name
        )
        assertEquals("AAPL", fetch(response(meta = meta(name = "\"  \""))).name)
        assertEquals("AAPL", fetch(response(meta = meta(name = null))).name)
    }

    @Test
    fun `generated Moshi adapters round trip nullable samples and omit computed values`() {
        val adapter = Moshi.Builder().build().adapter(StockQuote::class.java)
        val quote = fetch(response())
        val serialized = adapter.toJson(quote)
        assertEquals(quote, adapter.fromJson(serialized))
        assertFalse(serialized.contains("\"change\""))
        assertFalse(serialized.contains("\"changePercent\""))
        val noBaseline = quote.copy(previousClose = null, points = emptyList())
        assertEquals(noBaseline, adapter.fromJson(adapter.toJson(noBaseline)))
    }

    @Test
    fun `a single valid sample is kept without drawing invented history`() {
        val quote = fetch(response(chart = chart(timestamps = "[1790688600]", close = "[337.0]")))
        assertEquals(listOf(StockChartPoint(1790688600000L, 337.0)), quote.points)
    }

    @Test
    fun `change calculations never return nonfinite numbers`() {
        val quote = fetch(response())
        assertNull(quote.copy(price = Double.MAX_VALUE, previousClose = -Double.MAX_VALUE).change)
        assertNull(quote.copy(price = Double.MAX_VALUE, previousClose = 1.0).changePercent)
    }

    @Test
    fun `missing charts and missing individual closes never invent prices`() {
        listOf("", ""","timestamp":null""", ""","timestamp":[]""").forEach { chart ->
            assertTrue(fetch(response(chart = chart)).points.isEmpty())
        }
        val noCloses = fetch(response(chart = ""","timestamp":[1790688600,1790688900]"""))
        assertEquals(listOf(null, null), noCloses.points.map { it.price })
        val shortCloses = fetch(response(chart = chart(close = "[337.0]")))
        assertEquals(listOf(337.0, null, null), shortCloses.points.map { it.price })
        val allNull = fetch(response(chart = chart(close = "[null,null,null]")))
        assertEquals(listOf(null, null, null), allNull.points.map { it.price })
    }

    @Test
    fun `extra closes without timestamps are never assigned invented times`() {
        val quote = fetch(response(chart = chart(close = "[337.0,null,329.4,999.0]")))
        assertEquals(3, quote.points.size)
        assertEquals(329.4, quote.points.last().price!!, 0.0)
    }

    @Test
    fun `invalid missing duplicate or reversed chart timestamps discard chart not valid quote`() {
        listOf(
            "[1790688600,null,1790689200]",
            "[1790688600,0,1790689200]",
            "[1790688600,-1,1790689200]",
            "[1790688600,9223372036854776,1790689200]",
            "[1790688600,1790688600,1790689200]",
            "[1790689200,1790688900,1790688600]"
        ).forEach { timestamps ->
            val quote = fetch(response(chart = chart(timestamps = timestamps)))
            assertTrue(timestamps, quote.points.isEmpty())
            assertEquals(329.4, quote.price, 0.0)
        }
    }

    @Test
    fun `malformed scalar chart samples are rejected instead of coerced or shifted`() {
        listOf(
            chart(timestamps = """[1790688600,"1790688900",1790689200]"""),
            chart(timestamps = "[1790688600,1790688900.5,1790689200]"),
            chart(timestamps = "[1790688600,true,1790689200]"),
            chart(close = """[337.0,"329.4",329.4]"""),
            chart(close = "[337.0,true,329.4]"),
            chart(close = "[337.0,1e400,329.4]")
        ).forEach { samples ->
            expect<StockResponseException> { fetch(response(chart = samples)) }
        }
    }

    @Test
    fun `missing invalid and nonfinite quote prices fail without zero defaults`() {
        listOf(null, "null", "\"329.4\"", "true", "[]", "{}", "1e400", "-1e400", "\"NaN\"")
            .forEach { price ->
                expect<StockResponseException> { fetch(response(meta = meta(price = price))) }
            }
    }

    @Test
    fun `present previous close must be a finite number`() {
        listOf("\"338.4\"", "true", "1e400", "-1e400", "{}").forEach { previous ->
            expect<StockResponseException> { fetch(response(meta = meta(previous = previous))) }
        }
    }

    @Test
    fun `currency must be an explicit valid code and preserves subunits`() {
        listOf(null, "null", "\"\"", "\"usd\"", "\" USD \"", "\"ZZZ\"", "123", "\"$\"")
            .forEach { currency ->
                expect<StockResponseException> { fetch(response(meta = meta(currency = currency))) }
            }
        listOf("USD", "EUR", "JPY", "HKD", "GBP", "GBp", "GBX", "ZAc", "ILA").forEach { currency ->
            assertEquals(currency, fetch(response(meta = meta(currency = "\"$currency\""))).currency)
        }
    }

    @Test
    fun `quote must identify the requested symbol rather than trust ordering or defaults`() {
        listOf(null, "null", "\"\"", "\"MSFT\"", "\"AAPL?foo=bar\"", "123").forEach { symbol ->
            expect<StockResponseException> { fetch(response(meta = meta(symbol = symbol))) }
        }
    }

    @Test
    fun `market timestamp must be present positive integral and fit milliseconds`() {
        listOf(
            null, "null", "0", "-1", "1.5", "1790712000.00000000001",
            "\"1790712000\"", "true", "9223372036854776", "1e50"
        )
            .forEach { time ->
                expect<StockResponseException> { fetch(response(meta = meta(time = time))) }
            }
    }

    @Test
    fun `invalid JSON missing results and provider errors become bounded content free errors`() {
        listOf(
            "", "null", "{", "[]", "{}",
            """{"chart":{"result":null,"error":{"code":"Not Found","description":"private text"}}}""",
            """{"chart":{"result":[],"error":null}}""",
            """{"chart":{"result":[null],"error":null}}""",
            """{"chart":{"result":[{}],"error":null}}""",
            """{"chart":{"result":[{"meta":${meta()}},{"meta":${meta()}}],"error":null}}""",
            response().replace("\"error\":null", "\"error\":{}"),
            response() + "{}",
            " ".repeat(YahooStockHttp.MAX_RESPONSE_BYTES + 1)
        ).forEach { body ->
            val failure = expect<StockResponseException> { fetch(body) }
            assertEquals("Invalid stock response", failure.message)
        }
    }

    @Test
    fun `encoded symbol is one path component and queries request only one day of five minute data`() {
        listOf("aapl", "BRK-B", "BTC-USD", "^DJI", "GC=F", "0700.HK", "VOD.L").forEach { raw ->
            val symbol = StockSymbols.normalize(raw)!!
            val urls = mutableListOf<String>()
            val client = YahooStockClient { url ->
                urls.add(url)
                response(meta = meta(symbol = "\"$symbol\""))
            }
            assertEquals(symbol, client.fetch(raw).symbol)
            val uri = URI(urls.single())
            assertEquals("https", uri.scheme)
            assertEquals("query1.finance.yahoo.com", uri.host)
            assertEquals(
                "/v8/finance/chart/$symbol",
                URLDecoder.decode(uri.rawPath, "UTF-8")
            )
            assertEquals("interval=5m&range=1d", uri.rawQuery)
            assertNull(uri.rawUserInfo)
            assertNull(uri.fragment)
            if (symbol.contains('^')) assertTrue(uri.rawPath.contains("%5E"))
            if (symbol.contains('=')) assertTrue(uri.rawPath.contains("%3D"))
        }
    }

    @Test
    fun `invalid ticker never makes a request`() {
        val client = YahooStockClient { throw AssertionError("Unexpected request") }
        listOf("", "../AAPL", "AAPL?range=5d", "AAPL/MSFT", "https://example.com").forEach { raw ->
            expect<StockResponseException> { client.fetch(raw) }
        }
    }

    @Test
    fun `network failures and cancellation propagate unchanged without retries`() {
        listOf(IOException("offline"), InterruptedIOException("interrupted"), CancellationException("cancelled"))
            .forEach { failure ->
                var requests = 0
                val client = YahooStockClient { requests++; throw failure }
                assertSame(failure, expect<Exception> { client.fetch("AAPL") })
                assertEquals(1, requests)
            }
    }

    @Test
    fun `thread interruption is preserved before requests and after returned data`() {
        try {
            Thread.currentThread().interrupt()
            val client = YahooStockClient { throw AssertionError("Unexpected request") }
            expect<InterruptedIOException> { client.fetch("AAPL") }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        try {
            val client = YahooStockClient { Thread.currentThread().interrupt(); response() }
            expect<InterruptedIOException> { client.fetch("AAPL") }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interrupted exception from injected transport restores interrupt flag`() {
        val failure = InterruptedException()
        try {
            val client = YahooStockClient { throw failure }
            assertSame(failure, expect<InterruptedException> { client.fetch("AAPL") })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `history supports fixed ranges without replacing yesterday's close with a month baseline`() {
        val requests = mutableListOf<String>()
        val client = YahooStockClient { url ->
            requests.add(url)
            response(meta = meta(previous = if (url.endsWith("range=1d")) "338.4" else "200.0"))
        }
        StockHistoryRange.values().forEach { range ->
            assertEquals(3, client.fetchHistory("AAPL", range).size)
            assertTrue(requests.last().endsWith("interval=${range.interval}&range=${range.range}"))
        }
        assertEquals(338.4, client.fetch("AAPL").previousClose!!, 0.0)
        assertTrue(requests.last().endsWith("interval=5m&range=1d"))
    }

    @Test
    fun `instrument type and exchange metadata are optional and old cache records still parse`() {
        val complete = fetch(response(meta = meta(extra = ""","instrumentType":"EQUITY","fullExchangeName":"NasdaqGS"""")))
        assertEquals("EQUITY", complete.instrumentType)
        assertEquals("NasdaqGS", complete.exchange)
        val old = fetch(response())
        assertNull(old.instrumentType)
        assertNull(old.exchange)
        val adapter = Moshi.Builder().build().adapter(StockQuote::class.java)
        assertEquals(old, adapter.fromJson(adapter.toJson(old)))
    }

    private fun fetch(body: String): StockQuote = YahooStockClient { body }.fetch("AAPL")

    private fun meta(
        symbol: String? = "\"AAPL\"",
        name: String? = "\"Apple Inc.\"",
        currency: String? = "\"USD\"",
        price: String? = "329.4",
        previous: String? = "338.4",
        time: String? = "1790712000",
        extra: String = ""
    ): String = listOf(
        symbol?.let { "\"symbol\":$it" },
        name?.let { "\"longName\":$it" },
        currency?.let { "\"currency\":$it" },
        price?.let { "\"regularMarketPrice\":$it" },
        previous?.let { "\"chartPreviousClose\":$it" },
        time?.let { "\"regularMarketTime\":$it" }
    ).filterNotNull().joinToString(",", "{", "$extra}")

    private fun chart(
        timestamps: String = "[1790688600,1790688900,1790689200]",
        close: String = "[337.0,null,329.4]"
    ): String = ""","timestamp":$timestamps,"indicators":{"quote":[{"close":$close}]}"""

    private fun response(meta: String = meta(), chart: String = chart()): String =
        """{"chart":{"result":[{"meta":$meta$chart}],"error":null}}"""

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}", failure)
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError()
    }
}
