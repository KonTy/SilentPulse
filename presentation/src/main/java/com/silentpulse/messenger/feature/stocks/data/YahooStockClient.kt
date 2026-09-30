package com.silentpulse.messenger.feature.stocks.data

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.io.IOException
import java.lang.reflect.Type
import java.math.BigDecimal
import java.net.URLEncoder
import java.util.Currency

class StockResponseException : IOException("Invalid stock response")

enum class StockHistoryRange(val interval: String, val range: String) {
    DAY("5m", "1d"),
    WEEK("30m", "5d"),
    MONTH("1d", "1mo"),
    YEAR("1d", "1y")
}

/** Blocking, keyless daily quotes and fixed-range price history. Use a background thread. */
class YahooStockClient(private val request: (String) -> String = { YahooStockHttp.get(it) }) {

    /**
     * Returns the provider's market time and previous close, not fetch time or the first chart sample.
     * Missing closes remain null; invalid timestamp order discards only the chart.
     * Malformed response types fail with [StockResponseException]; transport/cancellation passes through.
     */
    fun fetch(symbol: String): StockQuote {
        val ticker = StockSymbols.normalize(symbol) ?: throw StockResponseException()
        val result = chart(ticker, StockHistoryRange.DAY)
        val meta = result.meta ?: throw StockResponseException()
        val currency = meta.currency?.takeIf(::validCurrency) ?: throw StockResponseException()
        val price = meta.regularMarketPrice?.takeIf(Double::isFinite) ?: throw StockResponseException()
        val marketTime = millis(meta.regularMarketTime) ?: throw StockResponseException()
        val previousClose = (meta.previousClose ?: meta.chartPreviousClose)?.also {
            if (!it.isFinite()) throw StockResponseException()
        }
        val name = sequenceOf(meta.longName, meta.shortName)
            .filterNotNull().map(String::trim).firstOrNull { it.isNotEmpty() } ?: ticker
        val quote = StockQuote(
            ticker, name, currency, price, previousClose, marketTime, points(result),
            meta.instrumentType, meta.fullExchangeName ?: meta.exchangeName
        )
        YahooStockHttp.checkInterrupted()
        return quote
    }

    fun fetchHistory(symbol: String, range: StockHistoryRange): List<StockChartPoint> {
        val ticker = StockSymbols.normalize(symbol) ?: throw StockResponseException()
        // A multi-day chart baseline is not yesterday's close. History never changes daily quote deltas.
        val points = points(chart(ticker, range))
        YahooStockHttp.checkInterrupted()
        return points
    }

    private fun chart(ticker: String, range: StockHistoryRange): YahooChartResult {
        YahooStockHttp.checkInterrupted()
        val url = "$BASE_URL/${URLEncoder.encode(ticker, "UTF-8")}?interval=${range.interval}&range=${range.range}"
        // Transport failures and cancellation are not parsing failures.
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
        val chart = response.chart ?: throw StockResponseException()
        if (chart.error != null) throw StockResponseException()
        val result = chart.result?.singleOrNull() ?: throw StockResponseException()
        val meta = result.meta ?: throw StockResponseException()
        val returnedSymbol = meta.symbol?.let(StockSymbols::normalize)
        if (returnedSymbol != ticker) throw StockResponseException()
        return result
    }

    private fun points(result: YahooChartResult): List<StockChartPoint> {
        val timestamps = result.timestamp.orEmpty()
        val close = result.indicators?.quote?.singleOrNull()?.close.orEmpty()
        val points = ArrayList<StockChartPoint>(timestamps.size)
        var previousTime = 0L
        for ((index, seconds) in timestamps.withIndex()) {
            // A missing/invalid time cannot be placed honestly; do not sort or shift price samples.
            val time = millis(seconds) ?: return emptyList()
            if (time <= previousTime) return emptyList()
            val price = close.getOrNull(index)
            if (price != null && !price.isFinite()) throw StockResponseException()
            points.add(StockChartPoint(time, price))
            previousTime = time
        }
        return points
    }

    companion object {
        private const val BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart"
        private val adapter = Moshi.Builder().add(StrictStockScalars).build()
            .adapter(YahooChartResponse::class.java)
        private val subunitCurrencies = setOf("GBp", "GBX", "ZAc", "ZAX", "ILA", "ILa")

        private fun validCurrency(value: String): Boolean = value in subunitCurrencies || try {
            value.matches(Regex("[A-Z]{3}")) && Currency.getInstance(value) != null
        } catch (_: IllegalArgumentException) {
            false
        }

        private fun millis(seconds: Long?): Long? =
            seconds?.takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000L }?.times(1_000L)
    }
}

/** The API must provide actual numbers and strings, not Moshi's usual scalar coercions. */
internal object StrictStockScalars : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (annotations.isNotEmpty()) return null
        val token = when (type) {
            Long::class.javaObjectType, Double::class.javaObjectType -> JsonReader.Token.NUMBER
            String::class.java -> JsonReader.Token.STRING
            else -> return null
        }
        val delegate = moshi.nextAdapter<Any>(this, type, annotations)
        return object : JsonAdapter<Any>() {
            override fun fromJson(reader: JsonReader): Any? {
                if (reader.peek() != token) throw JsonDataException("Unexpected scalar type")
                if (type == Long::class.javaObjectType) {
                    return try {
                        BigDecimal(reader.nextString()).longValueExact()
                    } catch (_: NumberFormatException) {
                        throw JsonDataException("Invalid timestamp")
                    } catch (_: ArithmeticException) {
                        throw JsonDataException("Invalid timestamp")
                    }
                }
                return delegate.fromJson(reader)
            }

            override fun toJson(writer: JsonWriter, value: Any?) = delegate.toJson(writer, value)
        }.nullSafe()
    }
}

@JsonClass(generateAdapter = true)
internal data class YahooChartResponse(val chart: YahooChart? = null)

@JsonClass(generateAdapter = true)
internal data class YahooChart(
    val result: List<YahooChartResult?>? = null,
    val error: Any? = null
)

@JsonClass(generateAdapter = true)
internal data class YahooChartResult(
    val meta: YahooQuoteMeta? = null,
    val timestamp: List<Long?>? = null,
    val indicators: YahooChartIndicators? = null
)

@JsonClass(generateAdapter = true)
internal data class YahooQuoteMeta(
    val symbol: String? = null,
    val longName: String? = null,
    val shortName: String? = null,
    val currency: String? = null,
    val regularMarketPrice: Double? = null,
    val previousClose: Double? = null,
    val chartPreviousClose: Double? = null,
    val regularMarketTime: Long? = null,
    val instrumentType: String? = null,
    val fullExchangeName: String? = null,
    val exchangeName: String? = null
)

@JsonClass(generateAdapter = true)
internal data class YahooChartIndicators(val quote: List<YahooChartPrices?>? = null)

@JsonClass(generateAdapter = true)
internal data class YahooChartPrices(val close: List<Double?>? = null)
