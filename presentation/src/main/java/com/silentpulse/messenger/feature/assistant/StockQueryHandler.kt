package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.silentpulse.messenger.feature.stocks.data.StockQuote
import com.silentpulse.messenger.feature.stocks.data.YahooStockClient
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Speaks quotes from the shared, keyless Yahoo Finance client.
 *
 * Usage: "stock price Microsoft" / "stock price crude oil".
 */
class StockQueryHandler(
    private val context: Context,
    private val client: YahooStockClient = YahooStockClient()
) {

    companion object {
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

        private val COMMAND_PREFIX = Regex("^stock\\s+prices?(?=\\s|[:,.!?]|$)", RegexOption.IGNORE_CASE)
        private val TRAILING_FILLER = Regex("\\s+(?:stock|stocks|share|shares|futures|today|right now|currently|please|per ounce|per troy ounce|per barrel|per share)$")
    }

    private val executor = Executors.newSingleThreadExecutor()

    /** Stock lookup is opt-in; ordinary questions containing company names are not price commands. */
    fun isStockQuery(command: String): Boolean = COMMAND_PREFIX.containsMatchIn(command.trim())

    /**
     * Parse the company/ticker from the command, fetch price, and call [onResult]
     * on the main thread with a speakable answer string.
     */
    fun fetchPrice(command: String, onResult: (String) -> Unit) {
        val ticker = extractTicker(command)
        if (ticker == null) {
            onResult("Say stock price followed by a company, ticker, or asset. For example: stock price Microsoft, stock price Apple, or stock price gold.")
            return
        }
        if (!isNetworkAvailable()) {
            onResult("No data connection. Please enable mobile data or Wi-Fi.")
            return
        }

        executor.execute {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                val answer = formatStockQuote(client.fetch(ticker))
                mainHandler.post { onResult(answer) }
            } catch (_: CancellationException) {
                return@execute
            } catch (_: Exception) {
                if (Thread.currentThread().isInterrupted) return@execute
                mainHandler.post { onResult("I couldn't fetch the price for $ticker right now. Try again later.") }
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun extractTicker(command: String): String? {
        val text = command.trim()
        val prefix = COMMAND_PREFIX.find(text) ?: return null
        var cleaned = text.substring(prefix.range.last + 1).trim(' ', ':', ',', '.', '!', '?')
            .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(?:of|for|the)\\s+"), "")
        while (TRAILING_FILLER.containsMatchIn(cleaned)) cleaned = cleaned.replace(TRAILING_FILLER, "")
        TICKER_MAP[cleaned]?.let { return it }

        // If cleaned looks like a raw ticker (1-5 uppercase-able letters), use it directly
        val upperCleaned = cleaned.trim().uppercase(Locale.ROOT)
        if (upperCleaned.matches(Regex("[A-Z]{1,5}"))) {
            return upperCleaned
        }

        return null
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

internal fun formatStockQuote(quote: StockQuote): String {
    val currencyLabel = when (quote.currency) {
        "USD" -> "dollars"
        "EUR" -> "euros"
        "GBP" -> "pounds"
        "GBp", "GBX" -> "pence"
        "CAD" -> "Canadian dollars"
        "AUD" -> "Australian dollars"
        "ZAc", "ZAX" -> "South African cents"
        "ILA", "ILa" -> "Israeli agorot"
        else -> quote.currency
    }
    val unitLabel = when {
        quote.symbol.endsWith("=F") -> when {
            quote.symbol.startsWith("GC") || quote.symbol.startsWith("SI") ||
                quote.symbol.startsWith("PL") -> "per troy ounce"
            quote.symbol.startsWith("CL") || quote.symbol.startsWith("BZ") -> "per barrel"
            else -> ""
        }
        quote.symbol.endsWith("-USD") -> ""
        else -> "per share"
    }
    val unit = if (unitLabel.isEmpty()) "" else " $unitLabel"
    val answer = "${quote.name} is currently at ${stockNumber(quote.price)} $currencyLabel$unit"
    val change = quote.change ?: return "$answer."
    if (change == 0.0) return "$answer, unchanged from the previous close."
    val direction = if (change > 0.0) "up" else "down"
    val percent = quote.changePercent?.let { " (${stockNumber(abs(it))} percent)" }.orEmpty()
    return "$answer, $direction ${stockNumber(abs(change))}$percent from the previous close."
}

private fun stockNumber(value: Double): String = String.format(Locale.US, "%.2f", value)
