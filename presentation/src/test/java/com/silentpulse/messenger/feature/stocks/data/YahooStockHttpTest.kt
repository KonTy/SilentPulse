package com.silentpulse.messenger.feature.stocks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class YahooStockHttpTest {
    private val endpoint = "https://query1.finance.yahoo.com/v8/finance/chart/AAPL?interval=5m&range=1d"

    @Test
    fun `transport is bounded keyless nonredirecting and closes resources`() {
        val connection = Connection(body = """{"result":"ok"}""".toByteArray())
        assertEquals("""{"result":"ok"}""", YahooStockHttp.get(endpoint) { connection })
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals(setOf("User-Agent", "Accept"), connection.requestProperties.keys)
        assertTrue(connection.streamClosed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `both trusted HTTPS hosts are allowed but no redirects or other authorities`() {
        listOf(
            "https://query1.finance.yahoo.com",
            "https://query2.finance.yahoo.com",
            "https://query1.finance.yahoo.com:443"
        ).forEach { address ->
            assertEquals("{}", YahooStockHttp.get(address) { Connection() })
        }
        listOf(
            "http://query1.finance.yahoo.com",
            "https://query1.finance.yahoo.com.example.com",
            "https://query1.finance.yahoo.com@evil.example",
            "https://evil.example@query1.finance.yahoo.com",
            "https://query1.finance.yahoo.com:444",
            "https://query1.finance.yahoo.com/#fragment",
            "https://google.com",
            "file:///etc/passwd"
        ).forEach { address ->
            expect<IOException> {
                YahooStockHttp.get(address) { throw AssertionError("Unexpected connection") }
            }
        }
    }

    @Test
    fun `redirect errors close error streams and do not read their bodies`() {
        listOf(301, 302, 307, 308, 401, 404, 429, 500).forEach { status ->
            val connection = Connection(status = status)
            val error = expect<IOException> { YahooStockHttp.get(endpoint) { connection } }
            assertEquals("Stock request failed", error.message)
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.errorClosed)
            assertEquals(0, connection.inputReads)
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun `oversized declared body fails before opening the stream`() {
        val connection = Connection(declaredLength = (YahooStockHttp.MAX_RESPONSE_BYTES + 1).toString())
        expect<StockResponseException> { YahooStockHttp.get(endpoint) { connection } }
        assertEquals(0, connection.inputReads)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `chunked or falsely declared oversized body is bounded and closed`() {
        listOf(null, "1", "not a length").forEach { declaredLength ->
            val connection = Connection(
                body = ByteArray(YahooStockHttp.MAX_RESPONSE_BYTES + 1) { 65 },
                declaredLength = declaredLength
            )
            expect<StockResponseException> { YahooStockHttp.get(endpoint) { connection } }
            assertTrue(connection.streamClosed)
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun `exact maximum size remains accepted`() {
        val connection = Connection(body = ByteArray(YahooStockHttp.MAX_RESPONSE_BYTES) { 65 })
        assertEquals(YahooStockHttp.MAX_RESPONSE_BYTES, YahooStockHttp.get(endpoint) { connection }.length)
        assertTrue(connection.streamClosed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `read failures close resources without wrapping transport errors`() {
        val failure = IOException("offline")
        val connection = Connection(readFailure = failure)
        assertSame(failure, expect<IOException> { YahooStockHttp.get(endpoint) { connection } })
        assertTrue(connection.streamClosed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `interrupt during read is preserved while closing stream and connection`() {
        val connection = Connection(interruptOnRead = true)
        try {
            expect<InterruptedIOException> { YahooStockHttp.get(endpoint) { connection } }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(connection.streamClosed)
            assertTrue(connection.disconnected)
        } finally {
            Thread.interrupted()
        }
    }

    private class Connection(
        body: ByteArray = "{}".toByteArray(),
        private val status: Int = 200,
        private val declaredLength: String? = null,
        private val readFailure: IOException? = null,
        private val interruptOnRead: Boolean = false
    ) : HttpsURLConnection(URL("https://query1.finance.yahoo.com")) {
        var disconnected = false
        var streamClosed = false
        var errorClosed = false
        var inputReads = 0
        private val bytes = ByteArrayInputStream(body)

        override fun getInputStream(): InputStream {
            inputReads++
            return object : InputStream() {
                override fun read(): Int {
                    readFailure?.let { throw it }
                    if (interruptOnRead) Thread.currentThread().interrupt()
                    return bytes.read()
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    readFailure?.let { throw it }
                    if (interruptOnRead) Thread.currentThread().interrupt()
                    return bytes.read(buffer, offset, length)
                }

                override fun close() { streamClosed = true }
            }
        }

        override fun getErrorStream(): InputStream = object : InputStream() {
            override fun read(): Int = throw AssertionError("Error bodies must not be read")
            override fun close() { errorClosed = true }
        }

        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? =
            if (name == "Content-Length") declaredLength else null

        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getCipherSuite(): String = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

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
