package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Fetches real-time stock prices using Yahoo Finance's unofficial chart API.
 *
 * No API key required. Domain whitelisted in network_security_config.xml.
 * API: https://query1.finance.yahoo.com/v8/finance/chart/{TICKER}?interval=1d&range=1d
 *
 * Usage: "what is the price of google stock" / "how much is apple stock today"
 */
class StockQueryHandler(private val context: Context) {

    companion object {
        private const val TAG = "StockQuery"
        private const val BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart"

        /**
         * Maps spoken company/commodity names (lowercase) to Yahoo Finance ticker symbols.
         * Supports common aliases. Commodities use Yahoo's futures/spot symbols.
         */
        private val TICKER_MAP = mapOf(
            // ── Commodities & Metals ──────────────────────────────────────────
            "gold"           to "GC=F",
            "silver"         to "SI=F",
            "platinum"       to "PL=F",
            "copper"         to "HG=F",
            "oil"            to "CL=F",
            "crude oil"      to "CL=F",
            "wti"            to "CL=F",
            "brent"          to "BZ=F",
            "natural gas"    to "NG=F",
            "wheat"          to "ZW=F",
            "corn"           to "ZC=F",
            // ── Crypto ───────────────────────────────────────────────────────
            "bitcoin"        to "BTC-USD",
            "btc"            to "BTC-USD",
            "ethereum"       to "ETH-USD",
            "eth"            to "ETH-USD",
            "dogecoin"       to "DOGE-USD",
            "doge"           to "DOGE-USD",
            "solana"         to "SOL-USD",
            "xrp"            to "XRP-USD",
            "ripple"         to "XRP-USD",
            // ── Big Tech ─────────────────────────────────────────────────────
            "google"         to "GOOGL",
            "alphabet"       to "GOOGL",
            "apple"          to "AAPL",
            "microsoft"      to "MSFT",
            "amazon"         to "AMZN",
            "meta"           to "META",
            "facebook"       to "META",
            "tesla"          to "TSLA",
            "nvidia"         to "NVDA",
            "netflix"        to "NFLX",
            "intel"          to "INTC",
            "amd"            to "AMD",
            "qualcomm"       to "QCOM",
            "broadcom"       to "AVGO",
            "salesforce"     to "CRM",
            "oracle"         to "ORCL",
            "ibm"            to "IBM",
            "uber"           to "UBER",
            "lyft"           to "LYFT",
            "airbnb"         to "ABNB",
            "snap"           to "SNAP",
            "snapchat"       to "SNAP",
            "spotify"        to "SPOT",
            "paypal"         to "PYPL",
            "square"         to "SQ",
            "block"          to "SQ",
            "shopify"        to "SHOP",
            "zoom"           to "ZM",
            "palantir"       to "PLTR",
            "coinbase"       to "COIN",
            // ── Finance ──────────────────────────────────────────────────────
            "jpmorgan"       to "JPM",
            "jp morgan"      to "JPM",
            "wells fargo"    to "WFC",
            "wells"          to "WFC",
            "bank of america" to "BAC",
            "goldman"        to "GS",
            "goldman sachs"  to "GS",
            "morgan stanley" to "MS",
            "citigroup"      to "C",
            "citi"           to "C",
            "american express" to "AXP",
            "visa"           to "V",
            "mastercard"     to "MA",
            // ── Healthcare ───────────────────────────────────────────────────
            "johnson and johnson" to "JNJ",
            "j&j"            to "JNJ",
            "pfizer"         to "PFE",
            "moderna"        to "MRNA",
            "abbvie"         to "ABBV",
            "merck"          to "MRK",
            "unitedhealth"   to "UNH",
            "eli lilly"      to "LLY",
            "lilly"          to "LLY",
            // ── Consumer ─────────────────────────────────────────────────────
            "walmart"        to "WMT",
            "costco"         to "COST",
            "target"         to "TGT",
            "home depot"     to "HD",
            "lowes"          to "LOW",
            "nike"           to "NKE",
            "disney"         to "DIS",
            "mcdonald"       to "MCD",
            "mcdonalds"      to "MCD",
            "starbucks"      to "SBUX",
            "coca cola"      to "KO",
            "coke"           to "KO",
            "pepsi"          to "PEP",
            "pepsico"        to "PEP",
            "procter"        to "PG",
            // ── Energy / Industrial ───────────────────────────────────────────
            "exxon"          to "XOM",
            "chevron"        to "CVX",
            "boeing"         to "BA",
            "caterpillar"    to "CAT",
            // ── Index ETFs ────────────────────────────────────────────────────
            "spy"            to "SPY",
            "s&p"            to "SPY",
            "s and p"        to "SPY",
            "nasdaq"         to "QQQ",
            "qqq"            to "QQQ",
            "dow"            to "DIA",
            "dow jones"      to "DIA"
        )

        /** Set of words that indicate a price/value query. */
        private val KNOWN_ASSETS = TICKER_MAP.keys
    }

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Returns true if the command looks like a price query for a known asset.
     * Matches stock questions ("price of Google stock"), commodity questions
     * ("price of gold today"), and crypto ("how much is bitcoin").
     */
    fun isStockQuery(command: String): Boolean {
        val c = command.lowercase()

        val hasPriceWord = c.contains("price") || c.contains("worth") ||
            c.contains("cost") || c.contains("trading") || c.contains("how much") ||
            c.contains("what is") || c.contains("what's") || c.contains("per ounce") ||
            c.contains("per share")

        if (!hasPriceWord) return false

        // Explicit stock/share/crypto keywords → always route here
        if (c.contains("stock") || c.contains("share") || c.contains("crypto") ||
            c.contains("bitcoin") || c.contains("ethereum") || c.contains("coin")) return true

        // Otherwise only route if a known asset name appears in the command
        return KNOWN_ASSETS.any { asset -> c.contains(asset) }
    }

    /**
     * Parse the company/ticker from the command, fetch price, and call [onResult]
     * on the main thread with a speakable answer string.
     */
    fun fetchPrice(command: String, onResult: (String) -> Unit) {
        if (!isNetworkAvailable()) {
            onResult("No data connection. Please enable mobile data or Wi-Fi.")
            return
        }

        val ticker = extractTicker(command)
        if (ticker == null) {
            onResult("I'm not sure which stock you mean. Try saying something like: what is the price of Apple stock.")
            return
        }

        Log.d(TAG, "Stock query: command=\"$command\" → ticker=$ticker")

        executor.execute {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                val answer = queryYahooFinance(ticker)
                mainHandler.post { onResult(answer) }
            } catch (e: Exception) {
                Log.e(TAG, "Stock query failed for $ticker", e)
                mainHandler.post { onResult("I couldn't fetch the price for $ticker right now. Try again later.") }
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun extractTicker(command: String): String? {
        val c = command.lowercase()

        // Remove filler phrases to isolate the company name
        val cleaned = c
            .replace(Regex("what(?:'s| is) the (current )?price of"), "")
            .replace(Regex("how much is"), "")
            .replace(Regex("what(?:'s| is)"), "")
            .replace(Regex("\\b(stock|stocks|share|shares|price|today|right now|currently|trading|at|the|for|of)\\b"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        Log.d(TAG, "extractTicker: cleaned=\"$cleaned\"")

        // Try longest match first (handles "jp morgan" before "morgan")
        val sortedKeys = TICKER_MAP.keys.sortedByDescending { it.length }
        for (key in sortedKeys) {
            if (cleaned.contains(key)) {
                return TICKER_MAP[key]
            }
        }

        // If cleaned looks like a raw ticker (2-5 uppercase-able letters), use it directly
        val upperCleaned = cleaned.trim().uppercase()
        if (upperCleaned.matches(Regex("[A-Z]{1,5}"))) {
            return upperCleaned
        }

        return null
    }

    private fun queryYahooFinance(ticker: String): String {
        val url = "$BASE_URL/$ticker?interval=1d&range=1d"
        Log.d(TAG, "Yahoo Finance URL: $url")

        val body = httpGet(url)
        val json = JSONObject(body)

        val result = json.optJSONObject("chart")
            ?.optJSONArray("result")
            ?.optJSONObject(0)
            ?: throw RuntimeException("No result in Yahoo Finance response")

        val meta = result.optJSONObject("meta")
            ?: throw RuntimeException("No meta in Yahoo Finance result")

        val price       = meta.optDouble("regularMarketPrice", Double.NaN)
        val prevClose   = meta.optDouble("previousClose", Double.NaN)
        val currency    = meta.optString("currency", "USD")
        val symbol      = meta.optString("symbol", ticker)
        val longName    = meta.optString("longName", "").ifEmpty {
            meta.optString("shortName", symbol)
        }

        if (price.isNaN()) throw RuntimeException("No price data returned for $ticker")

        val priceStr = "%.2f".format(price)
        val currencyLabel = when (currency) {
            "USD" -> "dollars"
            "EUR" -> "euros"
            "GBP" -> "pounds"
            "CAD" -> "Canadian dollars"
            "AUD" -> "Australian dollars"
            else  -> currency
        }

        // Unit label: futures/spots = per ounce or per barrel, stocks = per share
        val unitLabel = when {
            symbol.endsWith("=F") -> when {
                symbol.startsWith("GC") || symbol.startsWith("SI") ||
                symbol.startsWith("PL") -> "per troy ounce"
                symbol.startsWith("CL") || symbol.startsWith("BZ") -> "per barrel"
                else -> ""
            }
            symbol.endsWith("-USD") -> "" // crypto — no unit
            else -> "per share"
        }
        val unitStr = if (unitLabel.isEmpty()) "" else " $unitLabel"

        return if (!prevClose.isNaN() && prevClose != 0.0) {
            val change    = price - prevClose
            val changePct = (change / prevClose) * 100
            val direction = if (change >= 0) "up" else "down"
            val changeStr = "%.2f".format(abs(change))
            val pctStr    = "%.2f".format(abs(changePct))
            "$longName is currently at $$priceStr $currencyLabel$unitStr, " +
            "$direction $changeStr ($pctStr percent) from yesterday."
        } else {
            "$longName is currently at $$priceStr $currencyLabel$unitStr."
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 15_000
        conn.requestMethod  = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } finally {
            conn.disconnect()
        }
    }
}
