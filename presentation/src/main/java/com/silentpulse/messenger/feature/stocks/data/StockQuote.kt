package com.silentpulse.messenger.feature.stocks.data

import com.squareup.moshi.JsonClass

/** A missing close remains a gap at the provider's timestamp, never a zero or interpolated price. */
@JsonClass(generateAdapter = true)
data class StockChartPoint(val timeMillis: Long, val price: Double?)

@JsonClass(generateAdapter = true)
data class StockQuote(
    val symbol: String,
    val name: String,
    val currency: String,
    val price: Double,
    val previousClose: Double?,
    val marketTimeMillis: Long,
    val points: List<StockChartPoint>,
    val instrumentType: String? = null,
    val exchange: String? = null
) {
    val change: Double?
        get() = previousClose?.let { (price - it).takeIf(Double::isFinite) }

    val changePercent: Double?
        get() = previousClose?.takeIf { it != 0.0 }?.let { baseline ->
            change?.let { (it / baseline * 100.0).takeIf(Double::isFinite) }
        }
}
