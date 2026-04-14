package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import org.json.JSONArray

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
        /** Chat mode: 24 × 500 ms = 12 s max wait for a streaming response. */
        private const val BING_CHAT_MAX_POLLS = 24
        /** Leo /ask WebView: up to 40 × 800 ms = 32 s poll window after 2 s hydration. */
        private const val LEO_TIMEOUT_MS      = 35_000L
        private const val LEO_POLL_INTERVAL_MS = 800L
        private const val LEO_MAX_POLLS        = 40

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
    /** True once bing.com/chat has fully loaded and is ready for query injection. */
    private var bingChatInitialized = false
    /** Number of completed bot turns; used to detect when a new response arrives. */
    private var bingTurnCount = 0
    // Separate WebView for Brave Leo (search.brave.com/ask) to avoid cookie mixing
    private var leoWebView: WebView? = null
    /** True once the first /ask page has loaded. */
    private var leoInitialized = false

    // ── Public API ────────────────────────────────────────────────────────────

    /** Which AI source to query. */
    enum class Source { BRAVE, BING, LEO }

    /**
     * Search for an AI-generated answer for [query].
     *
     * [source] controls which backend is used:
     *   - BRAVE  → Brave Search HTTP only. Fast (~1-2 s). No Bing fallback.
     *              Used for all general questions by default.
     *   - BING   → Bing WebView directly. Use when the user prefixes the
     *              query with "bing" — e.g. "bing who won the election".
     *
     * Calls [onResult] on the main thread with the answer text, or null when
     * the source fails (caller should fall back to DuckDuckGo/Wikipedia).
     */
    fun search(query: String, source: Source = Source.BRAVE, onResult: (String?) -> Unit) {
        if (!enabled) {
            Log.d(TAG, "Disabled — skipping")
            onResult(null)
            return
        }

        when (source) {
            Source.BING -> {
                Log.d(TAG, "Explicit Bing query: \"$query\"")
                mainHandler.post { fetchBingChat(query, onResult) }
            }
            Source.LEO -> {
                Log.d(TAG, "Explicit Leo query: \"$query\"")
                mainHandler.post { fetchBraveLeo(query, onResult) }
            }
            Source.BRAVE -> {
                executor.execute {
                    try {
                        val brave = fetchBrave(query)
                        if (brave != null) {
                            Log.d(TAG, "Brave answered")
                            mainHandler.post { onResult(brave) }
                            return@execute
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Brave fetch threw: ${e.message}")
                    }
                    // HTTP scraper got no AI summary — try the Leo /ask WebView
                    Log.d(TAG, "Brave HTTP: no AI answer — falling back to Brave Leo WebView")
                    mainHandler.post { fetchBraveLeo(query, onResult) }
                }
            }
        }
    }

    /**
     * Streaming search — calls [onChunk] for each new paragraph as Leo generates
     * it, then [onDone] once the answer stabilises (or null on failure).
     *
     * **Only for Brave/Leo source.** Bing and HTTP-only Brave fall back to the
     * non-streaming [search] path internally.
     *
     * @param onChunk  Called on the main thread with each NEW paragraph text
     *                 as Leo streams it.  The caller should queue it to TTS.
     * @param onDone   Called on the main thread when streaming is complete.
     *                 Receives the full concatenated answer (or null on failure).
     */
    fun searchStreaming(
        query: String,
        source: Source = Source.BRAVE,
        onChunk: (String) -> Unit,
        onDone: (String?) -> Unit,
    ) {
        if (!enabled) {
            Log.d(TAG, "Disabled — skipping")
            onDone(null)
            return
        }

        when (source) {
            Source.LEO -> {
                Log.d(TAG, "Streaming Leo query: \"$query\"")
                mainHandler.post { fetchBraveLeoStreaming(query, onChunk, onDone) }
            }
            Source.BRAVE -> {
                executor.execute {
                    try {
                        val brave = fetchBrave(query)
                        if (brave != null) {
                            Log.d(TAG, "Brave HTTP answered — single chunk")
                            mainHandler.post {
                                onChunk(brave)
                                onDone(brave)
                            }
                            return@execute
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Brave fetch threw: ${e.message}")
                    }
                    Log.d(TAG, "Brave HTTP: no AI answer — falling back to Brave Leo streaming")
                    mainHandler.post { fetchBraveLeoStreaming(query, onChunk, onDone) }
                }
            }
            Source.BING -> {
                // Bing doesn't support paragraph-level streaming; use normal search
                Log.d(TAG, "Bing query (non-streaming): \"$query\"")
                mainHandler.post {
                    fetchBingChat(query) { answer ->
                        if (answer != null) onChunk(answer)
                        onDone(answer)
                    }
                }
            }
        }
    }

    /** Release resources. Call from VoiceAssistantService.onDestroy(). */
    fun destroy() {
        mainHandler.post {
            bingWebView?.destroy()
            bingWebView = null
            leoWebView?.destroy()
            leoWebView = null
            leoInitialized = false
        }
    }

    /**
     * Wipe all Bing session cookies, WebView cache, and DOM storage, then reset
     * conversation state.  Call this when the user says "bing clear" /
     * "[wake word] bing delete cookies".  The next "bing" query starts fresh.
     */
    fun clearBingSession(onDone: () -> Unit) {
        mainHandler.post {
            Log.i(TAG, "Clearing Bing session: cookies, storage, WebView")
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
            bingWebView?.apply {
                clearCache(true)
                clearHistory()
                clearFormData()
                destroy()
            }
            bingWebView = null
            bingChatInitialized = false
            bingTurnCount = 0
            Log.i(TAG, "Bing session cleared")
            onDone()
        }
    }

    /**
     * Wipe the Brave Leo WebView session so the next "leo" query starts a fresh
     * conversation.  Only clears search.brave.com cookies — Bing cookies are
     * not touched.
     */
    fun clearLeoSession(onDone: () -> Unit) {
        mainHandler.post {
            Log.i(TAG, "Clearing Leo session: WebView + brave.com cookies")
            // Remove only search.brave.com cookies to avoid disturbing Bing session
            val cm = CookieManager.getInstance()
            val existingCookies = cm.getCookie("https://search.brave.com") ?: ""
            existingCookies.split(";").forEach { pair ->
                val name = pair.trim().substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cm.setCookie("https://search.brave.com", "$name=; Max-Age=0; Path=/")
                    cm.setCookie("https://brave.com", "$name=; Max-Age=0; Path=/")
                }
            }
            cm.flush()
            leoWebView?.apply {
                clearCache(true)
                clearHistory()
                clearFormData()
                destroy()
            }
            leoWebView = null
            leoInitialized = false
            Log.i(TAG, "Leo session cleared")
            onDone()
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

        // Iterate from the end backwards — pick the first candidate that looks
        // like real prose (not legal/affiliate boilerplate or metadata noise).
        val candidate = parts.reversed().firstOrNull { block ->
            val b = block.trim()
            b.length > 60 &&
                !b.contains("AI-generated", ignoreCase = true) &&
                (b.contains('.') || b.contains(',')) &&
                !isBoilerplate(b)
        }?.trim()

        if (candidate == null) {
            Log.d(TAG, "Brave: could not isolate AI answer from window (all candidates failed quality checks)")
            return null
        }

        Log.d(TAG, "Brave answer candidate (${candidate.length} chars): ${candidate.take(120)}...")

        // Trim to 1200 chars, ending on a sentence boundary when possible
        val trimmed = if (candidate.length > 1200) {
            val cut = candidate.take(1200)
            val lastDot = cut.lastIndexOf('.')
            if (lastDot > 400) cut.take(lastDot + 1) else "$cut..."
        } else candidate

        return cleanForSpeech(trimmed)
    }

    /**
     * Returns true if [text] looks like legal/affiliate boilerplate or site metadata
     * rather than an actual answer. Used to discard junk candidates before speaking.
     *
     * Patterns caught:
     *  - Affiliate disclosures ("affiliate link", "commission", "sponsored")
     *  - Legal notices ("all rights reserved", "terms of service", "privacy policy")
     *  - Cookie banners ("we use cookies", "accept all cookies")
     *  - Shopping metadata ("add to cart", "buy now", "free shipping", "in stock")
     *  - Copyright / disclaimer blocks
     *  - Navigation/footer noise (very short fragments or pure capitalized labels)
     */
    private fun isBoilerplate(text: String): Boolean {
        val t = text.lowercase()
        val boilerplatePatterns = listOf(
            // Legal & affiliate
            "affiliate", "commission", "sponsored content", "we may earn",
            "all rights reserved", "terms of service", "terms and conditions",
            "privacy policy", "cookie policy", "disclaimer",
            "copyright ©", "copyright 2",
            // Cookie banners
            "we use cookies", "accept all cookies", "consent to cookies",
            "by continuing to browse",
            // Shopping metadata noise
            "add to cart", "buy now", "free shipping", "in stock", "out of stock",
            "compare prices", "view deal", "check price",
            // Generic navigation / footer fragments
            "skip to content", "skip to main", "back to top",
            // Review-site boilerplate
            "prices are accurate", "availability subject to change",
            "prices subject to change", "at the time of publication",
            "may have changed since"
        )
        if (boilerplatePatterns.any { t.contains(it) }) {
            Log.d(TAG, "Brave: discarding boilerplate candidate: ${text.take(80)}")
            return true
        }
        // Reject blocks with very low sentence density (metadata key-value noise)
        val sentences = text.split(Regex("[.!?]+")).filter { it.trim().length > 5 }
        if (text.length > 200 && sentences.size < 2) {
            Log.d(TAG, "Brave: discarding low-sentence-density candidate: ${text.take(80)}")
            return true
        }
        return false
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

    // ── Bing Chat — shadow-DOM JS templates ──────────────────────────────────

    /**
     * Inject a query into the Bing Chat textarea (which lives inside nested
     * shadow DOMs) and press Enter.  Caller replaces SP_QUERY_PLACEHOLDER
     * with the escaped query string before calling evaluateJavascript.
     */
    private val BING_CHAT_INJECT_JS = """
    (function() {
        function deepQ(root, sel) {
            var el = root.querySelector(sel);
            if (el) return el;
            var all = root.querySelectorAll('*');
            for (var i = 0; i < all.length; i++) {
                if (all[i].shadowRoot) { var f = deepQ(all[i].shadowRoot, sel); if (f) return f; }
            }
            return null;
        }
        var ta = deepQ(document, 'textarea') || deepQ(document, '[contenteditable="true"]');
        if (!ta) return 'NO_INPUT';
        var desc = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value');
        if (desc && desc.set) desc.set.call(ta, 'SP_QUERY_PLACEHOLDER');
        else ta.value = 'SP_QUERY_PLACEHOLDER';
        ta.dispatchEvent(new Event('input',  {bubbles: true}));
        ta.dispatchEvent(new Event('change', {bubbles: true}));
        setTimeout(function() {
            ta.dispatchEvent(new KeyboardEvent('keydown',  {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
            ta.dispatchEvent(new KeyboardEvent('keypress', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
            ta.dispatchEvent(new KeyboardEvent('keyup',    {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
            var btn = deepQ(document, 'button[type="submit"]') ||
                      deepQ(document, '[aria-label*="send" i]') ||
                      deepQ(document, '[title*="send" i]');
            if (btn) btn.click();
        }, 250);
        return 'OK:' + ta.tagName;
    })()
    """.trimIndent()

    /**
     * Poll for a new Bing Chat bot response.  Caller replaces PRIOR_COUNT with
     * the turn count snapshot taken right before submitting the query.
     * Returns JSON: {status:"done",text:"…",count:N} | {status:"typing"} | {status:"waiting",count:N}
     *
     * Selectors cover both the old bing.com/chat web-component structure
     * (cib-message[source="bot"]) and the new copilot.microsoft.com interface.
     */
    private val BING_CHAT_RESPONSE_JS = """
    (function() {
        function deepAll(root, sel) {
            var r = [].slice.call(root.querySelectorAll(sel));
            var all = root.querySelectorAll('*');
            for (var i = 0; i < all.length; i++) {
                if (all[i].shadowRoot) r = r.concat(deepAll(all[i].shadowRoot, sel));
            }
            return r;
        }
        // Typing / streaming indicators — wait longer if Copilot is still writing
        var typing = deepAll(document, [
            '.streaming-cursor', 'cib-typing-indicator',
            '[aria-label*="typing" i]', '.is-generating',
            '[class*="generating"]', '[class*="streaming"]',
            '[data-testid*="generating"]'
        ].join(','));
        if (typing.length > 0) return JSON.stringify({status:'typing'});

        var msgs = [];
        // 1. Classic bing.com/chat web components
        msgs = deepAll(document, 'cib-message[source="bot"]');
        // 2. copilot.microsoft.com — conversation turns (each turn = one Q+A)
        if (!msgs.length) msgs = deepAll(document, 'cib-chat-turn');
        // 3. data-source attribute variant
        if (!msgs.length) msgs = deepAll(document, '[data-source="bot"]');
        // 4. Adaptive card text blocks (shared between old and new interfaces)
        if (!msgs.length) msgs = deepAll(document, '.ac-container .ac-textBlock');
        if (!msgs.length) msgs = deepAll(document, '.sydney-reply');
        // 5. Class-name pattern matching for copilot.microsoft.com
        if (!msgs.length) msgs = deepAll(document,
            '[class*="message--response"],[class*="bot-message"],[class*="assistant-message"],[class*="response-message"]');
        // 6. Generic last resort — paragraphs inside the main chat area
        if (!msgs.length) {
            var paras = [];
            document.querySelectorAll('main, [role="main"]').forEach(function(m) {
                paras = paras.concat([].slice.call(m.querySelectorAll('p')));
            });
            msgs = paras.filter(function(p) { return (p.innerText || '').trim().length > 80; });
        }

        var count = msgs.length;
        if (count > PRIOR_COUNT) {
            var last = msgs[msgs.length - 1];
            var text = (last.innerText || last.textContent || '').trim();
            if (text.length > 20) return JSON.stringify({status:'done', text:text.substring(0,800), count:count});
        }
        return JSON.stringify({status:'waiting', count:count});
    })()
    """.trimIndent()

    // ── Bing Search — WebView ─────────────────────────────────────────────────

    /**
     * Bing's Copilot AI card is loaded asynchronously via JS after page render,
     * so we need a real WebView. The selectors below cover the known class names
     * as of 2025. Run `adb logcat -s WebAiScraper` to see which one matches or
     * to get the debug dump of result list classes at poll #4.
     */
    private val BING_SELECTORS_JS = """
    (function() {
        function isDefinition(t) {
            return /^The\s+\S+(\s+\S+){0,3}\s+(is|are)\s+(a|an|the)\s/i.test(t);
        }
        var sel = [
            '.b_focusTextLarge', '.b_focusTextHigh',
            '.b_hPanel .b_focusLabel', '#b_pole .b_focusTextSmall',
            '#b_context .b_answer', '.copilot-answer',
            '.b_ans .besc', '#b_results .b_ans p',
            '.b_expansion_text', '[class*="answerCard"] p',
            '.b_rich p', '#b_pole .b_algo p',
            '.b_focusTextSmall', '#b_results li.b_ans .besc'
        ];
        // Collect ALL candidates, then pick the longest non-definitional one.
        // This prevents a short "preview" card winning over the full answer text.
        var candidates = [];
        for (var i = 0; i < sel.length; i++) {
            try {
                var els = document.querySelectorAll(sel[i]);
                for (var k = 0; k < els.length; k++) {
                    var t = (els[k].innerText || '').trim();
                    if (t.length > 50) candidates.push(t);
                }
            } catch(e) {}
        }
        if (!candidates.length) return null;
        // Sort by length descending so the richest answer wins
        candidates.sort(function(a, b) { return b.length - a.length; });
        // First pass: prefer non-definitional
        for (var j = 0; j < candidates.length; j++) {
            if (!isDefinition(candidates[j])) {
                console.log('[BingAI] best non-def (' + candidates[j].length + ' chars)');
                return candidates[j].substring(0, 1500);
            }
        }
        // Fallback: accept anything
        console.log('[BingAI] fallback def (' + candidates[0].length + ' chars)');
        return candidates[0].substring(0, 1500);
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
            private var pageLoaded = false
            override fun onPageFinished(view: WebView, url: String) {
                if (done || pageLoaded) return
                pageLoaded = true
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

    // ── Bing Chat — conversation mode ─────────────────────────────────────────

    /**
     * Chat-mode Bing: loads `bing.com/chat` once and keeps it open between turns.
     *
     * Requires one-time verification via [BingChatVerificationActivity] to log in
     * and solve any CAPTCHA.  Until [bingChatReady] is true, falls back immediately
     * to [fetchBingWebView] (Bing search-page scraper) — no chat attempt is made.
     *
     * Once verified, first call loads the page and injects; subsequent calls inject
     * directly into the already-loaded textarea preserving conversation context.
     */
    private fun fetchBingChat(query: String, onResult: (String?) -> Unit) {
        if (!bingChatReady) {
            Log.d(TAG, "Bing chat: not verified — using search-page scraper directly")
            fetchBingWebView(query, onResult)
            return
        }
        val wv = getOrCreateBingWebView()
        var done = false
        // Give extra time on first call for the SPA to boot
        val timeoutMs = if (bingChatInitialized) BING_TIMEOUT_MS else BING_TIMEOUT_MS + 5_000L

        val timeoutCallback = Runnable {
            if (!done) {
                done = true
                Log.w(TAG, "Bing chat timed out after ${timeoutMs}ms — resetting and falling back to search scraper")
                wv.stopLoading()
                bingChatInitialized = false
                if (bingTurnCount > 0) bingTurnCount--
                fetchBingWebView(query, onResult)
            }
        }
        mainHandler.postDelayed(timeoutCallback, timeoutMs)

        if (!bingChatInitialized) {
            Log.d(TAG, "Bing chat: first use — loading bing.com/chat")
            var pageLoaded = false   // one-shot: block re-entry from redirect fires
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (done || pageLoaded) return
                    pageLoaded = true   // absorb any subsequent redirect callbacks
                    Log.d(TAG, "Bing chat page loaded: $url")
                    // 2 s for the SPA to render the input box
                    mainHandler.postDelayed({
                        if (!done) {
                            bingChatInitialized = true
                            injectBingChatQuery(wv, query, timeoutCallback, onResult, { done = true }, { done })
                        }
                    }, 2_000L)
                }
            }
            wv.loadUrl("https://www.bing.com/chat")
        } else {
            Log.d(TAG, "Bing chat: continuing conversation (turn ${bingTurnCount + 1})")
            injectBingChatQuery(wv, query, timeoutCallback, onResult, { done = true }, { done })
        }
    }

    private fun injectBingChatQuery(
        wv: WebView,
        query: String,
        timeout: Runnable,
        onResult: (String?) -> Unit,
        markDone: () -> Unit,
        isDone: () -> Boolean = { false },
    ) {
        val escaped = query.replace("\\", "\\\\").replace("'", "\\'")
        val js = BING_CHAT_INJECT_JS.replace("SP_QUERY_PLACEHOLDER", escaped)
        wv.evaluateJavascript(js) { result ->
            Log.d(TAG, "Bing chat inject: $result")
            if (result == null || result.contains("NO_INPUT")) {
                Log.w(TAG, "Bing chat: input not found — falling back to search-page scraper")
                bingChatInitialized = false
                markDone()
                mainHandler.removeCallbacks(timeout)
                fetchBingWebView(query, onResult)
            } else {
                val priorCount = bingTurnCount
                bingTurnCount++
                // 1.5 s head-start before polling so the response has time to begin
                mainHandler.postDelayed({
                    pollBingChatResponse(wv, query, timeout, 0, priorCount, isDone, onResult, markDone)
                }, 1_500L)
            }
        }
    }

    private fun pollBingChatResponse(
        wv: WebView,
        query: String,
        timeout: Runnable,
        attempt: Int,
        priorCount: Int,
        isDone: () -> Boolean,
        onResult: (String?) -> Unit,
        markDone: () -> Unit,
    ) {
        // Stop silently if the timeout already fired and started a fallback
        if (isDone()) return

        if (attempt >= BING_CHAT_MAX_POLLS) {
            Log.w(TAG, "Bing chat: no response after $attempt polls — resetting and falling back to search-page scraper")
            bingChatInitialized = false   // force a fresh reload next time
            if (bingTurnCount > 0) bingTurnCount--   // undo the optimistic increment
            markDone()
            mainHandler.removeCallbacks(timeout)
            fetchBingWebView(query, onResult)   // graceful fallback
            return
        }
        val js = BING_CHAT_RESPONSE_JS.replace("PRIOR_COUNT", priorCount.toString())
        wv.evaluateJavascript(js) { raw ->
            if (isDone()) return@evaluateJavascript   // timeout fired while we waited
            val payload = raw?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: ""
            Log.v(TAG, "Bing chat poll $attempt: ${payload.take(120)}")
            if (payload.contains("\"status\":\"done\"")) {
                val match = Regex("\"text\":\"(.*?)\"\\s*[,}]",
                    setOf(RegexOption.DOT_MATCHES_ALL)).find(payload)
                val answer = match?.groupValues?.get(1)
                    ?.replace("\\n", " ")?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")?.trim()
                if (!answer.isNullOrBlank() && answer.length > 20) {
                    Log.d(TAG, "Bing chat answer (${answer.length} chars): ${answer.take(80)}...")
                    markDone()
                    mainHandler.removeCallbacks(timeout)
                    onResult(answer)
                    return@evaluateJavascript
                }
            }
            mainHandler.postDelayed({
                pollBingChatResponse(wv, query, timeout, attempt + 1, priorCount, isDone, onResult, markDone)
            }, BING_POLL_INTERVAL_MS)
        }
    }

    // ── Brave Leo — WebView (/ask?q=QUERY, conversation mode) ──────────────────

    /**
     * JS run by [pollBraveLeoResult] after SvelteKit hydration.
     * Tries class-name patterns in order of specificity, logging which selector
     * won so it can be tuned via `adb logcat -s WebAiScraper`.
     * Falls back to a debug dump of all ask/leo/chatllm-classed elements.
     */
    private val LEO_SELECTORS_JS = """
    (function() {
        var sels = [
            '[class*="chatllm"] p',
            '[class*="llm-output"] p',
            '[class*="ask-answer"] p', '[class*="ask-center"] p', '[class*="ask-content"] p',
            '[class*="leo-answer"] p', '[class*="leo-content"] p', '[class*="leo-response"] p',
            '[class*="answer-content"] p', '[class*="ai-answer"] p', '[class*="summary-answer"] p',
            '[data-testid*="answer"] p', '[data-type="answer"] p',
            '[class*="response-content"] p', '[class*="result-content"] p',
            'main [class*="answer"]', 'main [class*="summary"]',
        ];
        for (var i = 0; i < sels.length; i++) {
            try {
                var els = document.querySelectorAll(sels[i]);
                var texts = [];
                for (var k = 0; k < els.length; k++) {
                    var t = (els[k].innerText || els[k].textContent || '').trim();
                    if (t.length > 40) texts.push(t);
                }
                if (texts.length) {
                    var joined = texts.join(' ');
                    console.log('[LeoAI] sel=' + sels[i] + ' n=' + texts.length +
                        ' total=' + joined.length + 'ch: ' + joined.substring(0, 60));
                    return joined.substring(0, 3000);
                }
            } catch(e) {}
        }
        // Debug: dump all named ask/leo/chatllm elements to help tune selectors
        var dbg = [];
        document.querySelectorAll('[class*="ask"],[class*="leo"],[class*="chatllm"],[class*="answer"]')
            .forEach(function(el) {
                var t = (el.innerText || '').trim();
                if (t.length > 20 && t.length < 2000) dbg.push(el.className + ':' + t.substring(0,60));
            });
        console.log('[LeoAI] no match — dump: ' + JSON.stringify(dbg.slice(0, 8)));
        return null;
    })()
    """.trimIndent()

    private fun fetchBraveLeo(query: String, onResult: (String?) -> Unit) {
        // Always load a fresh URL per query — conversation injection is unreliable.
        val wv = getOrCreateLeoWebView()
        var done = false
        val timeoutMs = LEO_TIMEOUT_MS + 3_000L

        val timeoutCallback = Runnable {
            if (!done) {
                done = true
                Log.w(TAG, "Brave Leo WebView timed out after ${timeoutMs}ms")
                wv.stopLoading()
                leoInitialized = false
                onResult(null)
            }
        }
        mainHandler.postDelayed(timeoutCallback, timeoutMs)

        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://search.brave.com/ask?q=$encoded"
        Log.d(TAG, "Brave Leo: loading $url")
        var pageLoaded = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, urlStr: String) {
                if (done || pageLoaded) return
                pageLoaded = true
                Log.d(TAG, "Brave Leo page finished: $urlStr")
                mainHandler.postDelayed({
                    if (!done) {
                        leoInitialized = true
                        pollBraveLeoResult(wv, timeoutCallback, 0, 0, onResult) { done = true }
                    }
                }, 2_000L)
            }
        }
        wv.loadUrl(url)
    }

    /**
     * Polls Leo for the AI answer using [LEO_SELECTORS_JS].  Returns only
     * when the text **stabilises** (same length for 2 consecutive polls) so
     * that streaming answers are not truncated prematurely.
     *
     * @param prevLen  Text length from the previous poll (0 on first call).
     *                 Used for stability detection.
     */
    private fun pollBraveLeoResult(
        wv: WebView,
        timeout: Runnable,
        attempt: Int,
        prevLen: Int,
        onResult: (String?) -> Unit,
        markDone: () -> Unit,
    ) {
        if (attempt >= LEO_MAX_POLLS) {
            Log.d(TAG, "Brave Leo: no AI answer after $attempt polls — giving up")
            markDone()
            mainHandler.removeCallbacks(timeout)
            onResult(null)
            return
        }

        wv.evaluateJavascript(LEO_SELECTORS_JS) { raw ->
            if (raw != null && raw != "null" && raw.length > 6) {
                val text = raw
                    .removeSurrounding("\"")
                    .replace("\\n", " ")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .trim()
                if (text.length > 50) {
                    // Stability check: Leo streams <p> elements incrementally.
                    // Only return when the text length stopped growing between
                    // two consecutive polls — meaning Leo finished generating.
                    if (text.length > prevLen) {
                        Log.v(TAG, "Brave Leo text still growing: ${text.length} chars (prev $prevLen) — poll $attempt")
                        mainHandler.postDelayed({
                            pollBraveLeoResult(wv, timeout, attempt + 1, text.length, onResult, markDone)
                        }, LEO_POLL_INTERVAL_MS)
                        return@evaluateJavascript
                    }
                    // Text stabilised — return the answer.
                    Log.d(TAG, "Brave Leo answer (${text.length} chars, stable at poll $attempt): ${text.take(80)}...")
                    markDone()
                    mainHandler.removeCallbacks(timeout)
                    onResult(cleanForSpeech(text))
                    return@evaluateJavascript
                }
            }
            mainHandler.postDelayed({
                pollBraveLeoResult(wv, timeout, attempt + 1, 0, onResult, markDone)
            }, LEO_POLL_INTERVAL_MS)
        }
    }

    // ── Streaming Leo (paragraph-by-paragraph) ───────────────────────────────

    /**
     * JS that returns an array of paragraph texts from the best-matching selector.
     * Used by [pollBraveLeoStreaming] to detect new paragraphs incrementally.
     */
    private val LEO_PARAGRAPHS_JS = """
    (function() {
        var sels = [
            '[class*="chatllm"] p',
            '[class*="llm-output"] p',
            '[class*="ask-answer"] p', '[class*="ask-center"] p', '[class*="ask-content"] p',
            '[class*="leo-answer"] p', '[class*="leo-content"] p', '[class*="leo-response"] p',
            '[class*="answer-content"] p', '[class*="ai-answer"] p', '[class*="summary-answer"] p',
            '[class*="response-content"] p', '[class*="result-content"] p',
            'main [class*="answer"] p', 'main [class*="summary"] p',
        ];
        var best = [];
        for (var i = 0; i < sels.length; i++) {
            try {
                var els = document.querySelectorAll(sels[i]);
                var texts = [];
                for (var k = 0; k < els.length; k++) {
                    var t = (els[k].innerText || els[k].textContent || '').trim();
                    if (t.length > 30) texts.push(t);
                }
                if (texts.length > best.length) best = texts;
            } catch(e) {}
        }
        return JSON.stringify(best);
    })()
    """.trimIndent()

    private fun fetchBraveLeoStreaming(
        query: String,
        onChunk: (String) -> Unit,
        onDone: (String?) -> Unit,
    ) {
        // Always load a fresh URL per query.  Conversation injection via textarea
        // is unreliable (follow-up DOM elements differ, polls stall, state breaks).
        // A fresh /ask?q=… is fast and always works.
        val wv = getOrCreateLeoWebView()
        var done = false
        val timeoutMs = LEO_TIMEOUT_MS + 3_000L

        val timeoutCallback = Runnable {
            if (!done) {
                done = true
                Log.w(TAG, "Leo streaming timed out after ${timeoutMs}ms")
                wv.stopLoading()
                leoInitialized = false
                onDone(null)
            }
        }
        mainHandler.postDelayed(timeoutCallback, timeoutMs)

        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://search.brave.com/ask?q=$encoded"
        Log.d(TAG, "Leo streaming: loading $url")
        var pageLoaded = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, urlStr: String) {
                if (done || pageLoaded) return
                pageLoaded = true
                Log.d(TAG, "Leo streaming page finished: $urlStr")
                mainHandler.postDelayed({
                    if (!done) {
                        leoInitialized = true
                        pollBraveLeoStreaming(wv, timeoutCallback, 0, mutableListOf(),
                            onChunk, onDone, markDone = { done = true })
                    }
                }, 2_000L)
            }
        }
        wv.loadUrl(url)
    }

    /**
     * Polls Leo for individual paragraphs and emits each new one via [onChunk].
     * When no new paragraphs appear for 2 consecutive polls the answer is
     * considered complete — [onDone] is called with the full text.
     *
     * @param emitted       List of paragraph texts already emitted (mutated in place).
     * @param stablePolls   Consecutive polls with no new paragraphs.
     */
    private fun pollBraveLeoStreaming(
        wv: WebView,
        timeout: Runnable,
        attempt: Int,
        emitted: MutableList<String>,
        onChunk: (String) -> Unit,
        onDone: (String?) -> Unit,
        markDone: () -> Unit,
        stablePolls: Int = 0,
    ) {
        if (attempt >= LEO_MAX_POLLS) {
            // Timed out, but if we already emitted something, return that.
            if (emitted.isNotEmpty()) {
                val full = emitted.joinToString(" ")
                Log.d(TAG, "Leo streaming: max polls reached, returning ${full.length} chars")
                markDone()
                mainHandler.removeCallbacks(timeout)
                onDone(cleanForSpeech(full))
            } else {
                Log.d(TAG, "Leo streaming: no paragraphs after $attempt polls")
                markDone()
                mainHandler.removeCallbacks(timeout)
                onDone(null)
            }
            return
        }

        wv.evaluateJavascript(LEO_PARAGRAPHS_JS) { raw ->
            val paragraphs = try {
                // evaluateJavascript returns a JSON-encoded string: the outer
                // layer is a JSON string literal wrapping our JS return value.
                // Use JSONTokener to properly unescape the outer layer, then
                // parse the inner value as a JSONArray.
                val inner = if (raw != null && raw.startsWith("\"")) {
                    org.json.JSONTokener(raw).nextValue() as? String ?: "[]"
                } else {
                    raw ?: "[]"
                }
                val arr = JSONArray(inner)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Leo streaming: JSON parse error: ${e.message}")
                emptyList()
            }

            // Emit any paragraphs we haven't emitted yet
            var emittedNew = false
            for (i in emitted.size until paragraphs.size) {
                val text = paragraphs[i]
                emitted.add(text)
                Log.d(TAG, "Leo streaming chunk #${emitted.size}: ${text.take(60)}... (${text.length}ch)")
                onChunk(cleanForSpeech(text))
                emittedNew = true
            }

            val newStablePolls = if (emittedNew || emitted.isEmpty()) 0 else stablePolls + 1

            // Need 2 consecutive stable polls to consider the answer complete
            if (emitted.isNotEmpty() && newStablePolls >= 2) {
                val full = emitted.joinToString(" ")
                Log.d(TAG, "Leo streaming complete: ${emitted.size} paragraphs, ${full.length} chars")
                leoInitialized = true
                markDone()
                mainHandler.removeCallbacks(timeout)
                onDone(cleanForSpeech(full))
                return@evaluateJavascript
            }

            mainHandler.postDelayed({
                pollBraveLeoStreaming(wv, timeout, attempt + 1, emitted,
                    onChunk, onDone, markDone, newStablePolls)
            }, LEO_POLL_INTERVAL_MS)
        }
    }

    private fun getOrCreateLeoWebView(): WebView {
        return leoWebView ?: WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled   = true
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 9) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }.also { leoWebView = it }
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

    // ── Debug dump ──────────────────────────────────────────────────────────

    /**
     * Run a diagnostic query that dumps EVERYTHING to files in the app directory.
     * Triggered via ADB:
     *   adb shell am broadcast -n com.silentpulse.messenger/.feature.debug.BraveDebugReceiver \
     *       -a com.silentpulse.messenger.BRAVE_DEBUG --es query "what is quantum physics"
     *
     * Then pull the results:
     *   adb pull /sdcard/Android/data/com.silentpulse.messenger/files/brave_debug/
     *
     * Files written:
     *   1_http_raw.html         — raw Brave Search HTML (plain HTTP)
     *   2_http_extracted.txt    — what extractBraveAnswer() produces
     *   3_leo_dom.html          — full Leo WebView DOM after hydration
     *   4_leo_selectors.txt     — what every CSS selector matched
     *   5_leo_state.txt         — leoInitialized, etc.
     *
     * The user can open 1_http_raw.html or 3_leo_dom.html in a browser,
     * inspect with F12, and tell us which elements contain the AI answer.
     */
    fun debugDump(query: String, onDone: (String) -> Unit) {
        if (!com.silentpulse.messenger.BuildConfig.DEBUG) {
            onDone("Debug dump is only available in debug builds.")
            return
        }
        val dir = java.io.File(context.getExternalFilesDir(null), "brave_debug")
        dir.mkdirs()
        // Clean old files
        dir.listFiles()?.forEach { it.delete() }

        Log.i(TAG, "=== BRAVE DEBUG DUMP: query=\"$query\" ===")
        val stateFile = java.io.File(dir, "5_leo_state.txt")
        stateFile.writeText(buildString {
            appendLine("query: $query")
            appendLine("timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("leoInitialized: $leoInitialized")
            appendLine("leoWebView: ${if (leoWebView != null) "exists" else "null"}")
            appendLine("bingWebView: ${if (bingWebView != null) "exists" else "null"}")
            appendLine("enabled: $enabled")
        })

        // Step 1: HTTP fetch
        executor.execute {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://search.brave.com/search?q=$encoded&summary=1&source=web"
                Log.d(TAG, "Debug: HTTP GET $url")
                val html = httpGet(url, BRAVE_TIMEOUT_MS.toInt())
                java.io.File(dir, "1_http_raw.html").writeText(html)
                Log.d(TAG, "Debug: saved ${html.length} bytes to 1_http_raw.html")

                val extracted = if (html.isNotEmpty()) extractBraveAnswer(html) else null
                java.io.File(dir, "2_http_extracted.txt").writeText(
                    extracted ?: "(extractBraveAnswer returned null — no AI answer found in HTTP response)"
                )
            } catch (e: Exception) {
                java.io.File(dir, "2_http_extracted.txt").writeText("HTTP fetch failed: ${e.message}")
                Log.e(TAG, "Debug: HTTP step failed", e)
            }

            // Step 2: Leo WebView dump
            mainHandler.post {
                debugDumpLeoWebView(query, dir, onDone)
            }
        }
    }

    private fun debugDumpLeoWebView(query: String, dir: java.io.File, onDone: (String) -> Unit) {
        val wv = getOrCreateLeoWebView()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://search.brave.com/ask?q=$encoded"
        Log.d(TAG, "Debug: Leo loading $url")

        var pageLoaded = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, urlStr: String) {
                if (pageLoaded) return
                pageLoaded = true
                Log.d(TAG, "Debug: Leo page finished, waiting 12s for hydration + answer generation...")
                // Wait 12s for SvelteKit to hydrate AND for Leo to generate the full answer
                mainHandler.postDelayed({
                    debugExtractLeoState(wv, dir, onDone)
                }, 12_000L)
            }
        }
        wv.loadUrl(url)
    }

    private fun debugExtractLeoState(wv: WebView, dir: java.io.File, onDone: (String) -> Unit) {
        // 1. Dump all page HTML
        wv.evaluateJavascript(
            "(function(){ return document.documentElement.outerHTML; })()"
        ) { rawHtml ->
            val html = rawHtml?.removeSurrounding("\"")
                ?.replace("\\n", "\n")
                ?.replace("\\t", "\t")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\/", "/")
                ?.replace("\\\\", "\\")
                ?: "(null)"
            java.io.File(dir, "3_leo_dom.html").writeText(html)
            Log.d(TAG, "Debug: saved ${html.length} chars to 3_leo_dom.html")

            // 2. Run ALL selectors and dump matches
            val selectorDumpJs = """
            (function() {
                var result = [];
                var sels = [
                    '[class*="chatllm"] p',
                    '[class*="ask-answer"] p', '[class*="ask-center"] p', '[class*="ask-content"] p',
                    '[class*="leo-answer"] p', '[class*="leo-content"] p', '[class*="leo-response"] p',
                    '[class*="answer-content"] p', '[class*="ai-answer"] p', '[class*="summary-answer"] p',
                    '[data-testid*="answer"] p', '[data-type="answer"] p',
                    '[class*="response-content"] p', '[class*="result-content"] p',
                    'main [class*="answer"]', 'main [class*="summary"]',
                    '[class*="streaming"]', '[class*="generating"]',
                    '[class*="typing"]', '[class*="loading"]',
                    'textarea', 'input[type="text"]',
                ];
                for (var i = 0; i < sels.length; i++) {
                    try {
                        var els = document.querySelectorAll(sels[i]);
                        var matches = [];
                        for (var k = 0; k < els.length; k++) {
                            var el = els[k];
                            var t = (el.innerText || el.textContent || '').trim();
                            matches.push({
                                tag: el.tagName,
                                className: (el.className || '').substring(0, 120),
                                id: el.id || '',
                                textLen: t.length,
                                text: t.substring(0, 200),
                                childCount: el.children.length,
                            });
                        }
                        if (matches.length > 0) {
                            result.push({selector: sels[i], count: matches.length, matches: matches});
                        }
                    } catch(e) {
                        result.push({selector: sels[i], error: e.message});
                    }
                }

                // Also dump all elements with class containing 'answer', 'leo', 'ask', 'chat', 'summary'
                var interesting = document.querySelectorAll(
                    '[class*="answer"],[class*="leo"],[class*="ask"],[class*="chat"],[class*="summary"],[class*="llm"],[class*="response"]'
                );
                var interestingList = [];
                for (var j = 0; j < interesting.length && j < 50; j++) {
                    var el = interesting[j];
                    var t = (el.innerText || '').trim();
                    interestingList.push({
                        tag: el.tagName,
                        className: (el.className || '').substring(0, 150),
                        id: el.id || '',
                        textLen: t.length,
                        textPreview: t.substring(0, 150),
                    });
                }
                result.push({label: 'INTERESTING_ELEMENTS', count: interestingList.length, elements: interestingList});

                return JSON.stringify(result, null, 2);
            })()
            """.trimIndent()

            wv.evaluateJavascript(selectorDumpJs) { rawJson ->
                val json = rawJson?.removeSurrounding("\"")
                    ?.replace("\\n", "\n")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?: "(null)"
                java.io.File(dir, "4_leo_selectors.txt").writeText(json)
                Log.d(TAG, "Debug: saved selector dump to 4_leo_selectors.txt")

                val summary = "Debug dump complete → ${dir.absolutePath}\n" +
                        "Pull with: adb pull ${dir.absolutePath}/ ./brave_debug/"
                Log.i(TAG, summary)
                leoInitialized = false // Reset so normal queries start fresh
                onDone(summary)
            }
        }
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
