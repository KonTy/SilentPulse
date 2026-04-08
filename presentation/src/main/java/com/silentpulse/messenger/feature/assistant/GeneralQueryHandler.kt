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
 * Handles generic knowledge questions using a two-source strategy:
 *
 * 1. **DuckDuckGo Instant Answers** — great for calculations, unit conversions,
 *    definitions, and direct factual lookups. No API key. Returns empty for
 *    conversational or speculative questions.
 *
 * 2. **Wikipedia Search + Extract** — falls back to Wikipedia when DDG has no
 *    answer. Searches for the most relevant article, fetches its intro extract,
 *    and reads the first few sentences. Works for factual/scientific questions
 *    like "is chocolate poisonous to cats" or "what is theobromine".
 *
 * Domains whitelisted in network_security_config.xml:
 *   - api.duckduckgo.com
 *   - en.wikipedia.org
 */
class GeneralQueryHandler(private val context: Context) {

    companion object {
        private const val TAG = "GeneralQuery"
        private const val DDG_URL = "https://api.duckduckgo.com/"
        private const val WIKI_SEARCH_URL = "https://en.wikipedia.org/w/api.php"
        private const val MAX_CHARS = 400
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun fetchAndSpeak(command: String, onResult: (String) -> Unit) {
        if (!isNetworkAvailable()) {
            onResult("No data connection. Please turn on mobile data or Wi-Fi, then try again.")
            return
        }

        Log.d(TAG, "General query: \"$command\"")

        executor.execute {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                val answer = queryDuckDuckGo(command)
                    ?: queryWikipedia(command)
                    ?: "I couldn't find an answer for that. Try rephrasing your question."
                mainHandler.post { onResult(answer) }
            } catch (e: Exception) {
                Log.e(TAG, "General query failed", e)
                mainHandler.post { onResult("I couldn't find an answer right now. Try again later.") }
            }
        }
    }

    // ── DuckDuckGo Instant Answers ─────────────────────────────────────────────

    private fun queryDuckDuckGo(query: String): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$DDG_URL?q=$encoded&format=json&no_redirect=1&no_html=1&skip_disambig=1"
        Log.d(TAG, "DDG URL: $url")

        val body = httpGet(url)
        val json = JSONObject(body)

        // 1. Direct computed answer (unit conversions, calculations, etc.)
        val answer = json.optString("Answer", "").trim()
        if (answer.isNotEmpty()) {
            Log.d(TAG, "DDG Answer: $answer")
            return answer
        }

        // 2. Wikipedia abstract — only use if it looks substantive
        val abstract_ = json.optString("AbstractText", "").trim()
        if (abstract_.length > 80) {
            Log.d(TAG, "DDG AbstractText (${abstract_.length} chars)")
            return truncateToSentence(abstract_, MAX_CHARS)
        }

        // 3. Dictionary definition
        val definition = json.optString("Definition", "").trim()
        if (definition.isNotEmpty()) {
            Log.d(TAG, "DDG Definition: $definition")
            return definition
        }

        Log.d(TAG, "DDG returned no useful answer — trying Wikipedia")
        return null
    }

    // ── Wikipedia Search + Extract ────────────────────────────────────────────

    private fun queryWikipedia(query: String): String? {
        // Step 1: search for the most relevant article
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$WIKI_SEARCH_URL?action=query&list=search" +
                "&srsearch=$encoded&format=json&srprop=snippet&srlimit=1"
        Log.d(TAG, "Wikipedia search: $searchUrl")

        val searchBody = httpGet(searchUrl)
        val searchJson = JSONObject(searchBody)
        val results = searchJson.optJSONObject("query")?.optJSONArray("search")
        if (results == null || results.length() == 0) {
            Log.d(TAG, "Wikipedia: no search results")
            return null
        }

        val topResult = results.getJSONObject(0)
        val title = topResult.optString("title", "")
        if (title.isEmpty()) return null

        // Step 2: fetch the intro extract of that article
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val extractUrl = "$WIKI_SEARCH_URL?action=query&titles=$encodedTitle" +
                "&prop=extracts&exsentences=3&format=json&explaintext=1"
        Log.d(TAG, "Wikipedia extract: $extractUrl (article: $title)")

        val extractBody = httpGet(extractUrl)
        val extractJson = JSONObject(extractBody)
        val pages = extractJson.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val page = pages.optJSONObject(pages.keys().next()) ?: return null
        val extract = page.optString("extract", "").trim()
            .replace(Regex("\\n+"), " ")       // flatten newlines
            .replace(Regex("==.*?=="), "")     // remove section headers
            .replace(Regex("\\s{2,}"), " ")    // collapse spaces
            .trim()

        if (extract.isEmpty()) return null

        val spoken = truncateToSentence(extract, MAX_CHARS)
        Log.d(TAG, "Wikipedia answer from \"$title\": $spoken")
        return "According to Wikipedia, on $title: $spoken"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "SilentPulse/1.0 (Android; offline-first)")
        return try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } finally {
            conn.disconnect()
        }
    }
}
