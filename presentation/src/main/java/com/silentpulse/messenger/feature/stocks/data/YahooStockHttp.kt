package com.silentpulse.messenger.feature.stocks.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object YahooStockHttp {
    const val MAX_RESPONSE_BYTES = 1_048_576
    private val hosts = setOf("query1.finance.yahoo.com", "query2.finance.yahoo.com")

    fun get(
        address: String,
        openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }
    ): String {
        checkInterrupted()
        val url = URL(address)
        if (url.protocol != "https" || url.host !in hosts ||
            url.port !in listOf(-1, 443) || url.userInfo != null || url.ref != null
        ) {
            throw IOException("Unapproved stock endpoint")
        }
        val connection = openConnection(url)
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "SilentPulse")
            connection.setRequestProperty("Accept", "application/json")
            checkInterrupted()
            if (connection.responseCode != 200) {
                connection.errorStream?.close()
                throw IOException("Stock request failed")
            }
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (length != null && length > MAX_RESPONSE_BYTES) {
                throw StockResponseException()
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    checkInterrupted()
                    val count = input.read(buffer)
                    checkInterrupted()
                    if (count == -1) break
                    if (count > MAX_RESPONSE_BYTES - output.size()) throw StockResponseException()
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Stock request interrupted")
        }
    }
}
