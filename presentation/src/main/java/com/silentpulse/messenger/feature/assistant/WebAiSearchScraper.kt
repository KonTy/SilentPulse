package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * AI answer scraper using real browser rendering — no API key required.
 *
 * Sources tried in order:
 *   1. **Brave Search** (`search.brave.com`) — plain HTTP, server-rendered HTML.
 *      Fast (~1-2 s). Extracts the AI summary that appears on the results page.
 *   2. **Bing Search** (`www.bing.com/search`) — headless WebView, extracts the
 *      Copilot AI card that is loaded via JS after page render (~3-8 s).
 *
 * **Bing Chat** (`bing.com/chat`) requires a one-time human verification step:
 *   start [BingChatVerificationActivity], complete the CAPTCHA and/or login once,
 *   then cookies persist for headless use. Bing Chat is NOT yet wired into the
 *   search() flow — add it in fetchBingChat() below once verification is in place.
 *
 * ── Modularity ────────────────────────────────────────────────────────────────
 * To DISABLE the whole feature: call [setEnabled](context, false).
 *   VoiceAssistantService will fall through to DuckDuckGo/Wikipedia immediately.
 * To REMOVE the whole feature:
 *   1. Delete this file and BingChatVerificationActivity.kt.
 *   2. Remove the 4-line block in VoiceAssistantService that calls scraper.search().
 *   3. Remove brave.com + bing.com from network_security_config.xml.
 *
 * ── Selector tuning ──────────────────────────────────────────────────────────
 * Both extractors log what they find at DEBUG level. Run:
 *   adb logcat -s WebAiScraper
 * to observe which selectors match and tune BRAVE_MARKER / BING_SELECTORS_JS.
 *
 * Domains required in network_security_config.xml:
 *   brave.com (includeSubdomains) — Brave search page + CDN
 *   bing.com  (includeSubdomains) — Bing search + Copilot AJAX
 *   microsoft.com (includeSubdomains) — Bing static assets
 */
class WebAiSearchScraper(private val context: Context) {

    companion object {
        private const val TAG = "WebAiScraper"
        private const val PREFS = "silentpulse_assistant"
        private const val KEY_ENABLED = "web_ai_scraper_enabled"
        const val KEY_BING_CHAT_READY = "bing_chat_verified"

        private const val BRAVE_TIMEOUT_MS = 7_000L
        private const val BING_TIMEOUT_MS = 8_000L
        private const val BING_POLL_INTERVAL_MS = 500L
        private const val BING_MAX_POLLS = 16

        /**
         * Disable or re-enable the AI scraping feature entirely.
         * When disabled, search() immediately calls onResult(null) and
         * VoiceAssistantService falls back to DuckDuckGo + Wikipedia.
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
            Log.i(TAG, "WebAiSearchScraper ${if (enabled) "enabled" else "disabled"}")
        }

        /** Called by BingChatVerificationActivity after successful verification. */
        fun setBingChatReady(context: Context, ready: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BING_CHAT_READY, ready).apply()
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, true)
    val bingChatReady: Boolean get() = prefs.getBoolean(KEY_BING_CHAT_READY, false)

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Reuse the Bing WebView to avoid repeated Chromium initialisation (~80 ms)
    private var bingWebView: WebView? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Search for an AI-generated answer for [query].
     *
     * Calls [onResult] on the main thread with the answer text, or null when
     * both sources fail (caller should fall back to DuckDuckGo/Wikipedia).
     *
     * This method starts on the calling thread; Brave HTTP work is run on a
     * background thread; Bing WebView work returns to the main thread.
     */
    fun search(query: String, onResult: (String?) -> Unit) {
        if (!enabled) {
            Log.d(TAG, "Disabled — skipping")
            onResult(null)
            return
        }

        // Try Brave first (lightweight HTTP, ~1-2 s)
        executor.execute {
            try {
                val brave = fetchBrave(query)
                if (brave != null) {
                    Log.d(TAG, "Brave answered in time")
                    mainHandler.post { onResult(brave) }
                    return@execute
                }
            } catch (e: Exception) {
                Log.w(TAG, "Brave fetch threw: ${e.message}")
            }

            // Brave had no AI answer — try Bing WebView (must run on main thread)
            mainHandler.post { fetchBingWebView(query, onResult) }
        }
    }

    /** Release resources. Call from VoiceAssistantService.onDestroy(). */
    fun destroy() {
        mainHandler.post {
            bingWebView?.destroy()
            bingWebView = null
        }
    }

    // ── Brave Search — plain HTTP ─────────────────────────────────────────────

    private fun fetchBrave(query: String): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://search.brave.com/search?q=$encoded&summary=1&source=web"
        Log.d(TAG, "Brave GET $url")

        val html = httpGet(url, timeoutMs = BRAVE_TIMEOUT_MS.toInt())
        if (html.isEmpty()) {
            Log.w(TAG, "Brave: empty response")
            return null
        }
        Log.d(TAG, "Brave: got ${html.length} bytes")

        return extractBraveAnswer(html)
    }

    /**
     * Brave renders the AI summary server-side. The block ends with the literal
     * string "AI-generated answer. Please verify critical facts."
     *
     * Strategy:
     *   1. Strip scripts/styles/tags, decode HTML entities.
     *   2. Locate the "AI-generated answer" marker.
     *   3. In the 3000-char window before the marker, split by inline URLs and
     *      image references (which delimit search-result snippets from each other).
     *   4. Take the last substantial block of natural-language text.
     *
     * If this extracts garbage, run `adb logcat -s WebAiScraper` to see the
     * stripped text window and adjust the split pattern or window size.
     */
    private fun extractBraveAnswer(html: String): String? {
        // Strip code blocks first so their text doesn't pollute the search
        val noCode = html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")

        val marker = "AI-generated answer"
        val markerIdx = noCode.indexOf(marker, ignoreCase = true)
        if (markerIdx < 0) {
            Log.d(TAG, "Brave: AI marker not found — no AI summary for this query")
            return null
        }

        // Take the window before the marker
        val window = noCode.substring(maxOf(0, markerIdx - 3000), markerIdx)

        // Strip remaining tags + decode entities
        val text = window
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        Log.v(TAG, "Brave window tail (last 600): ...${text.takeLast(600)}")

        // Split on URL strings and [Image:…] references — these delimit search
        // result snippets. The AI answer is the last substantial natural-English
        // block that has sentence punctuation.
        val parts = text.split(Regex("https?://\\S+|\\[Image:[^\\]]*\\]"))
        val candidate = parts.lastOrNull { block ->
            val b = block.trim()
            b.length > 60 &&
                !b.contains("AI-generated", ignoreCase = true) &&
                (b.contains('.') || b.contains(','))
        }?.trim()

        if (candidate == null) {
            Log.d(TAG, "Brave: could not isolate AI answer from window")
            return null
        }

        Log.d(TAG, "Brave answer candidate (${candidate.length} chars): ${candidate.take(120)}...")

        // Trim to 500 chars, ending on a sentence boundary when possible
        val trimmed = if (candidate.length > 500) {
            val cut = candidate.take(500)
            val lastDot = cut.lastIndexOf('.')
            if (lastDot > 200) cut.take(lastDot + 1) else "$cut..."
        } else candidate

        return cleanForSpeech(trimmed)
    }

    /**
     * Strip characters that cause TTS to stutter or mispronounce:
     *   - Unicode bullet/list markers (•, ◦, ‣, ⁃, ·)
     *   - Isolates a colon‐then‐bullet pattern like "approximately: •" → "approximately: "
     *   - Collapses any resulting double spaces/punctuation
     */
    private fun cleanForSpeech(text: String): String {
        return text
            // Remove bullet / list markers
            .replace(Regex("[\u2022\u25E6\u2023\u2043\u00B7\u2219]"), "") // •◦‣⁃·∙
            // Replace "word: •" or "word: –" patterns with just "word. "
            .replace(Regex(":\\s*[\u2022\u25E6\u2023\u2043-]"), ": ")
            // Collapse multiple spaces
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    // ── Bing Search — WebView ─────────────────────────────────────────────────

    /**
     * Bing's Copilot AI card is loaded asynchronously via JS after page render,
     * so we need a real WebView. The selectors below cover the known class names
     * as of 2025. Run `adb logcat -s WebAiScraper` to see which one matches or
     * to get the debug dump of result list classes at poll #4.
     */
    private val BING_SELECTORS_JS = """
    (function() {
        var sel = [
            '#b_context .b_answer',
            '.copilot-answer',
            '.b_ans .besc',
            '#b_results .b_ans p',
            '.b_expansion_text',
            '[class*="answerCard"] p',
            '.b_rich p',
            '#b_pole .b_algo p',
            '.b_focusTextSmall',
            '#b_results li.b_ans .besc'
        ];
        for (var i = 0; i < sel.length; i++) {
            try {
                var el = document.querySelector(sel[i]);
                if (el && el.innerText && el.innerText.trim().length > 50) {
                    console.log('[BingAI] matched: ' + sel[i]);
                    return el.innerText.trim().substring(0, 500);
                }
            } catch(e) {}
        }
        return null;
    })()
    """.trimIndent()

    private val BING_DEBUG_JS = """
    (function() {
        var items = [];
        document.querySelectorAll('#b_results > li').forEach(function(li) {
            items.push(li.className + ' >> ' + li.innerText.substring(0, 60));
        });
        return JSON.stringify(items.slice(0, 8));
    })()
    """.trimIndent()

    private fun fetchBingWebView(query: String, onResult: (String?) -> Unit) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.bing.com/search?q=$encoded&setlang=en"
        Log.d(TAG, "Bing WebView: $url")

        val wv = getOrCreateBingWebView()
        var done = false

        val timeoutCallback = Runnable {
            if (!done) {
                done = true
                Log.w(TAG, "Bing WebView timed out after ${BING_TIMEOUT_MS}ms")
                wv.stopLoading()
                onResult(null)
            }
        }
        mainHandler.postDelayed(timeoutCallback, BING_TIMEOUT_MS)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (done) return
                Log.d(TAG, "Bing page finished: $url")
                pollBingResult(wv, timeoutCallback, 0, onResult) { done = true }
            }
        }
        wv.loadUrl(url)
    }

    private fun pollBingResult(
        wv: WebView,
        timeout: Runnable,
        attempt: Int,
        onResult: (String?) -> Unit,
        markDone: () -> Unit,
    ) {
        if (attempt >= BING_MAX_POLLS) {
            Log.d(TAG, "Bing: no AI card found after $attempt polls — giving up")
            markDone()
            mainHandler.removeCallbacks(timeout)
            onResult(null)
            return
        }

        // On poll #4 dump the result list for debugging selector issues
        if (attempt == 4) {
            wv.evaluateJavascript(BING_DEBUG_JS) { dbg ->
                Log.d(TAG, "Bing result-list dump: $dbg")
            }
        }

        wv.evaluateJavascript(BING_SELECTORS_JS) { raw ->
            if (raw != null && raw != "null" && raw.length > 6) {
                val text = raw
                    .removeSurrounding("\"")
                    .replace("\\n", " ")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .trim()
                if (text.length > 50) {
                    Log.d(TAG, "Bing answer (${text.length} chars): ${text.take(80)}...")
                    markDone()
                    mainHandler.removeCallbacks(timeout)
                    onResult(text)
                    return@evaluateJavascript
                }
            }
            // Not found yet — wait and retry
            mainHandler.postDelayed({
                pollBingResult(wv, timeout, attempt + 1, onResult, markDone)
            }, BING_POLL_INTERVAL_MS)
        }
    }

    // ── WebView factory ───────────────────────────────────────────────────────

    private fun getOrCreateBingWebView(): WebView {
        return bingWebView ?: WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled   = true
                // Use a real Chrome UA so the page renders its full feature set
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            // 'this' inside apply is the WebView — required by setAcceptThirdPartyCookies
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }.also { bingWebView = it }
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private fun httpGet(urlString: String, timeoutMs: Int = 8_000): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout    = timeoutMs
        conn.requestMethod  = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept",          "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        conn.setRequestProperty("Accept-Encoding", "identity")   // no compression
        conn.setRequestProperty("Cache-Control",   "no-cache")
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36")
        return try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $urlString")
                return ""
            }
            val charset = conn.contentType
                ?.split(";")
                ?.firstOrNull { it.contains("charset") }
                ?.substringAfter("=")
                ?.trim()
                ?: "UTF-8"
            BufferedReader(InputStreamReader(conn.inputStream, charset)).readText()
        } finally {
            conn.disconnect()
        }
    }
}
