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
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * AI-powered search using the Brave Search API.
 *
 * Flow:
 *   1. POST web search with `summary=1` → response may include `summarizer.key`
 *   2. If summarizer key present → call summarizer endpoint → clean AI answer
 *   3. If no summarizer key → concatenate top snippet descriptions (good fallback)
 *
 * API key storage: stored in SharedPreferences under "silentpulse_assistant" / "brave_api_key".
 * Set it once via adb:
 *   adb shell am broadcast -a com.silentpulse.SET_BRAVE_KEY \
 *       -n com.silentpulse.messenger.debug/com.silentpulse.messenger.feature.assistant.ApiKeyReceiver \
 *       --es key "YOUR_KEY_HERE"
 *
 * Or in code (one-time setup):
 *   BraveSearchHandler.saveApiKey(context, "YOUR_KEY_HERE")
 *
 * Free tier: $5 credits/month at api-dashboard.search.brave.com — roughly 1,000 searches/month.
 * Web search: https://api.search.brave.com/res/v1/web/search
 * Summarizer: https://api.search.brave.com/res/v1/summarizer/search
 */
class BraveSearchHandler(private val context: Context) {

    companion object {
        private const val TAG           = "BraveSearch"
        private const val PREFS_NAME    = "silentpulse_assistant"
        private const val KEY_API_KEY   = "brave_api_key"
        private const val SEARCH_URL    = "https://api.search.brave.com/res/v1/web/search"
        private const val SUMMARIZER_URL = "https://api.search.brave.com/res/v1/summarizer/search"

        fun saveApiKey(context: Context, key: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_API_KEY, key.trim()).apply()
            Log.i(TAG, "Brave API key saved (${key.length} chars)")
        }

        fun clearApiKey(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_API_KEY).apply()
        }
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun hasApiKey(): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "").orEmpty().isNotBlank()

    /**
     * Perform an AI-powered search and call [onResult] on the main thread
     * with a clean, speakable answer string.
     */
    fun search(query: String, onResult: (String) -> Unit) {
        if (!hasApiKey()) {
            onResult("Brave Search is not configured. Please add an API key.")
            return
        }
        if (!isNetworkAvailable()) {
            onResult("No data connection. Please enable mobile data or Wi-Fi.")
            return
        }

        Log.d(TAG, "Brave query: \"$query\"")

        executor.execute {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                val answer = doSearch(query)
                mainHandler.post { onResult(answer) }
            } catch (e: Exception) {
                Log.e(TAG, "Brave search failed", e)
                mainHandler.post {
                    onResult("Search failed. Try again or rephrase your question.")
                }
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun doSearch(query: String): String {
        val apiKey = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "").orEmpty()

        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchBody = httpGet(
            "$SEARCH_URL?q=$encoded&count=5&summary=1&search_lang=en",
            apiKey
        )
        val searchJson = JSONObject(searchBody)

        // 1. Try AI summarizer if a key was returned
        val summarizerKey = searchJson
            .optJSONObject("summarizer")
            ?.optString("key", "")
            .orEmpty()

        if (summarizerKey.isNotEmpty()) {
            Log.d(TAG, "Summarizer key received — fetching AI summary")
            try {
                val summaryBody = httpGet(
                    "$SUMMARIZER_URL?key=${URLEncoder.encode(summarizerKey, "UTF-8")}&entity_info=1",
                    apiKey
                )
                val summaryJson = JSONObject(summaryBody)
                val summary = extractSummary(summaryJson)
                if (!summary.isNullOrBlank()) {
                    Log.d(TAG, "AI summary: $summary")
                    return summary
                }
            } catch (e: Exception) {
                Log.w(TAG, "Summarizer call failed, falling back to snippets", e)
            }
        }

        // 2. Fall back to web result snippets
        val snippets = buildSnippetAnswer(searchJson, query)
        if (!snippets.isNullOrBlank()) return snippets

        return "I couldn't find a clear answer. Try rephrasing your question."
    }

    /**
     * Extract the AI-generated summary text from the summarizer response.
     * The response structure:
     *   { "type": "summarizer", "title": "...", "summary": [ { "type": "token", "data": "..." }, ... ] }
     */
    private fun extractSummary(json: JSONObject): String? {
        // The summary is an array of tokens; concatenate "data" segments.
        val tokens = json.optJSONArray("summary")
        if (tokens != null && tokens.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until tokens.length()) {
                val token = tokens.optJSONObject(i)
                val data  = token?.optString("data", "") ?: ""
                sb.append(data)
            }
            val text = sb.toString().trim()
                .replace(Regex("\\[\\d+\\]"), "")   // strip citation markers like [1]
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            return text.ifEmpty { null }
        }
        // Some responses use a flat "text" field
        return json.optString("text", "").takeIf { it.isNotEmpty() }
    }

    /**
     * Build a spoken answer from the top web result snippets when no AI summary
     * is available.  Picks the most informative snippet and trims it to ~350 chars.
     */
    private fun buildSnippetAnswer(json: JSONObject, query: String): String? {
        val results = json.optJSONObject("web")?.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        // Try the first result's description
        for (i in 0 until minOf(results.length(), 3)) {
            val result = results.optJSONObject(i) ?: continue
            val desc = result.optString("description", "")
                .replace(Regex("<[^>]+>"), "")  // strip any HTML tags
                .trim()
            if (desc.length > 50) {
                val source = result.optString("title", "")
                val truncated = truncateToSentence(desc, 350)
                return if (source.isNotEmpty()) "According to ${source.substringBefore(" -").trim()}: $truncated"
                else truncated
            }
        }
        return null
    }

    private fun truncateToSentence(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val truncated = text.take(maxChars)
        val lastDot = truncated.lastIndexOf('.')
        return if (lastDot > maxChars / 2) truncated.take(lastDot + 1)
        else "$truncated..."
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun httpGet(urlString: String, apiKey: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 15_000
        conn.requestMethod  = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("X-Subscription-Token", apiKey)
        return try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode} for $urlString")
            BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } finally {
            conn.disconnect()
        }
    }
}
