package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

/**
 * Headless WebView scraper for Outlook Web App (outlook.office.com).
 *
 * Requires one-time authentication via [OutlookVerificationActivity] —
 * user logs in with MFA, cookies persist for headless reuse.
 *
 * Capabilities:
 *   - Fetch inbox (top N emails): sender, subject, preview
 *   - Read full email body by index
 *   - Reply to an email (inject text + click Send)
 *   - Delete an email
 *   - Forward an email to a recipient
 *
 * All interaction is via JS injection into the OWA React SPA.
 * Data never leaves the device — the WebView runs in-process.
 */
class OutlookWebScraper(private val context: Context) {

    companion object {
        private const val TAG = "OutlookScraper"
        private const val PREFS = "silentpulse_assistant"
        private const val KEY_OUTLOOK_READY = "outlook_verified"

        private const val INBOX_URL = "https://outlook.office.com/mail/inbox"
        private const val TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 800L
        private const val MAX_POLLS = 25

        fun setOutlookReady(context: Context, ready: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OUTLOOK_READY, ready).apply()
            Log.i(TAG, "Outlook ready = $ready")
        }

        fun isOutlookReady(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OUTLOOK_READY, false)
        }
    }

    data class EmailPreview(
        val index: Int,
        val sender: String,
        val subject: String,
        val preview: String,
        val isRead: Boolean
    )

    val isReady: Boolean get() = isOutlookReady(context)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var inboxLoaded = false

    // ── WebView lifecycle ─────────────────────────────────────────────────────

    private fun getOrCreateWebView(): WebView {
        return webView ?: WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled   = true
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }.also { webView = it }
    }

    fun shutdown() {
        webView?.destroy()
        webView = null
        inboxLoaded = false
    }

    // ── Fetch inbox ───────────────────────────────────────────────────────────

    /**
     * Fetch the top [count] emails from the inbox.
     * Calls [onResult] on the main thread with the list, or empty on failure.
     */
    fun fetchInbox(count: Int = 5, onResult: (List<EmailPreview>) -> Unit) {
        if (!isReady) {
            Log.w(TAG, "Not authenticated — need OutlookVerificationActivity first")
            onResult(emptyList())
            return
        }
        mainHandler.post {
            val wv = getOrCreateWebView()
            var done = false

            val timeoutCallback = Runnable {
                if (!done) {
                    done = true
                    Log.w(TAG, "Inbox fetch timed out after ${TIMEOUT_MS}ms")
                    wv.stopLoading()
                    onResult(emptyList())
                }
            }
            mainHandler.postDelayed(timeoutCallback, TIMEOUT_MS)

            if (inboxLoaded) {
                // Already on inbox page — just scrape
                pollInbox(wv, count, 0, timeoutCallback, onResult) { done = true }
            } else {
                wv.webViewClient = object : WebViewClient() {
                    private var loaded = false
                    override fun onPageFinished(view: WebView, url: String) {
                        if (done || loaded) return
                        loaded = true
                        Log.d(TAG, "Inbox page finished: $url")

                        // Check if we got redirected to login
                        if (url.contains("login.microsoftonline.com") || url.contains("login.live.com")) {
                            done = true
                            mainHandler.removeCallbacks(timeoutCallback)
                            Log.w(TAG, "Session expired — redirected to login: $url")
                            setOutlookReady(context, false)
                            inboxLoaded = false
                            onResult(emptyList())
                            return
                        }

                        inboxLoaded = true
                        // Give the SPA ~2s to hydrate after onPageFinished
                        mainHandler.postDelayed({
                            pollInbox(wv, count, 0, timeoutCallback, onResult) { done = true }
                        }, 2000)
                    }
                }
                wv.loadUrl(INBOX_URL)
            }
        }
    }

    private fun pollInbox(
        wv: WebView,
        count: Int,
        attempt: Int,
        timeout: Runnable,
        onResult: (List<EmailPreview>) -> Unit,
        markDone: () -> Unit
    ) {
        if (attempt >= MAX_POLLS) {
            Log.w(TAG, "Inbox: no emails found after $attempt polls")
            markDone()
            mainHandler.removeCallbacks(timeout)
            onResult(emptyList())
            return
        }

        // Debug dump on poll #3 to help tune selectors
        if (attempt == 3) {
            wv.evaluateJavascript(DEBUG_DUMP_JS) { dump ->
                Log.d(TAG, "DOM debug dump: $dump")
            }
        }

        wv.evaluateJavascript(INBOX_SCRAPE_JS.replace("MAX_COUNT", count.toString())) { raw ->
            if (raw != null && raw != "null" && raw.length > 10) {
                try {
                    val cleaned = raw
                        .removeSurrounding("\"")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    val arr = JSONArray(cleaned)
                    if (arr.length() > 0) {
                        val emails = (0 until arr.length()).map { i ->
                            val obj = arr.getJSONObject(i)
                            EmailPreview(
                                index = i,
                                sender  = obj.optString("sender", "Unknown"),
                                subject = obj.optString("subject", "(no subject)"),
                                preview = obj.optString("preview", ""),
                                isRead  = obj.optBoolean("isRead", false)
                            )
                        }
                        Log.d(TAG, "Fetched ${emails.size} emails")
                        markDone()
                        mainHandler.removeCallbacks(timeout)
                        onResult(emails)
                        return@evaluateJavascript
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "JSON parse error on poll $attempt: ${e.message}")
                }
            }
            // Not ready yet — retry
            mainHandler.postDelayed({
                pollInbox(wv, count, attempt + 1, timeout, onResult, markDone)
            }, POLL_INTERVAL_MS)
        }
    }

    // ── Read full email body ──────────────────────────────────────────────────

    /**
     * Click on the [emailIndex]-th email in the inbox list and extract the full body.
     */
    fun readEmailBody(emailIndex: Int, onResult: (String?) -> Unit) {
        mainHandler.post {
            val wv = getOrCreateWebView()
            var done = false

            val timeoutCallback = Runnable {
                if (!done) { done = true; onResult(null) }
            }
            mainHandler.postDelayed(timeoutCallback, TIMEOUT_MS)

            // Click the email row
            val clickJs = CLICK_EMAIL_JS.replace("EMAIL_INDEX", emailIndex.toString())
            wv.evaluateJavascript(clickJs) { clickResult ->
                if (clickResult == "\"false\"" || clickResult == "false") {
                    done = true
                    mainHandler.removeCallbacks(timeoutCallback)
                    onResult(null)
                    return@evaluateJavascript
                }
                // Wait for the reading pane to populate
                mainHandler.postDelayed({
                    pollEmailBody(wv, 0, timeoutCallback, onResult) { done = true }
                }, 1500)
            }
        }
    }

    private fun pollEmailBody(
        wv: WebView,
        attempt: Int,
        timeout: Runnable,
        onResult: (String?) -> Unit,
        markDone: () -> Unit
    ) {
        if (attempt >= 15) {
            markDone(); mainHandler.removeCallbacks(timeout); onResult(null); return
        }
        wv.evaluateJavascript(READ_BODY_JS) { raw ->
            if (raw != null && raw != "null" && raw.length > 10) {
                val text = raw.removeSurrounding("\"")
                    .replace("\\n", " ").replace("\\\"", "\"").trim()
                if (text.length > 20) {
                    markDone(); mainHandler.removeCallbacks(timeout); onResult(text)
                    return@evaluateJavascript
                }
            }
            mainHandler.postDelayed({
                pollEmailBody(wv, attempt + 1, timeout, onResult, markDone)
            }, POLL_INTERVAL_MS)
        }
    }

    // ── Delete current email ──────────────────────────────────────────────────

    fun deleteCurrentEmail(onResult: (Boolean) -> Unit) {
        mainHandler.post {
            val wv = getOrCreateWebView()
            wv.evaluateJavascript(DELETE_EMAIL_JS) { raw ->
                val ok = raw?.contains("true") == true
                Log.d(TAG, "Delete result: $ok")
                onResult(ok)
            }
        }
    }

    // ── Reply to current email ────────────────────────────────────────────────

    fun replyToCurrentEmail(text: String, onResult: (Boolean) -> Unit) {
        mainHandler.post {
            val wv = getOrCreateWebView()
            val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            val js = REPLY_EMAIL_JS.replace("REPLY_TEXT_PLACEHOLDER", escaped)
            wv.evaluateJavascript(js) { _ ->
                // Wait for compose pane to appear, inject text, click send
                mainHandler.postDelayed({
                    pollReplyCompose(wv, escaped, 0, onResult)
                }, 2000)
            }
        }
    }

    private fun pollReplyCompose(wv: WebView, text: String, attempt: Int, onResult: (Boolean) -> Unit) {
        if (attempt >= 10) { onResult(false); return }
        val js = INJECT_REPLY_TEXT_JS.replace("REPLY_TEXT_PLACEHOLDER", text)
        wv.evaluateJavascript(js) { raw ->
            if (raw?.contains("true") == true) {
                // Text injected, now click send after a brief delay
                mainHandler.postDelayed({
                    wv.evaluateJavascript(CLICK_SEND_JS) { sendRaw ->
                        onResult(sendRaw?.contains("true") == true)
                    }
                }, 1000)
            } else {
                mainHandler.postDelayed({
                    pollReplyCompose(wv, text, attempt + 1, onResult)
                }, POLL_INTERVAL_MS)
            }
        }
    }

    // ── Forward current email ─────────────────────────────────────────────────

    fun forwardCurrentEmail(recipient: String, onResult: (Boolean) -> Unit) {
        mainHandler.post {
            val wv = getOrCreateWebView()
            val escapedRecipient = recipient.replace("\\", "\\\\").replace("'", "\\'")
            val js = FORWARD_EMAIL_JS.replace("RECIPIENT_PLACEHOLDER", escapedRecipient)
            wv.evaluateJavascript(js) { _ ->
                mainHandler.postDelayed({
                    pollForwardCompose(wv, escapedRecipient, 0, onResult)
                }, 2000)
            }
        }
    }

    private fun pollForwardCompose(wv: WebView, recipient: String, attempt: Int, onResult: (Boolean) -> Unit) {
        if (attempt >= 10) { onResult(false); return }
        val js = INJECT_FORWARD_RECIPIENT_JS.replace("RECIPIENT_PLACEHOLDER", recipient)
        wv.evaluateJavascript(js) { raw ->
            if (raw?.contains("true") == true) {
                mainHandler.postDelayed({
                    wv.evaluateJavascript(CLICK_SEND_JS) { sendRaw ->
                        onResult(sendRaw?.contains("true") == true)
                    }
                }, 1500)
            } else {
                mainHandler.postDelayed({
                    pollForwardCompose(wv, recipient, attempt + 1, onResult)
                }, POLL_INTERVAL_MS)
            }
        }
    }

    // ── Go back to inbox list ─────────────────────────────────────────────────

    fun goBackToInbox(onDone: () -> Unit) {
        mainHandler.post {
            val wv = getOrCreateWebView()
            wv.evaluateJavascript(GO_BACK_JS) { _ ->
                mainHandler.postDelayed(onDone, 1000)
            }
        }
    }

    // ── JavaScript snippets ───────────────────────────────────────────────────

    private val DEBUG_DUMP_JS = """
    (function() {
        var roles = document.querySelectorAll('[role]');
        var summary = [];
        for (var i = 0; i < Math.min(roles.length, 50); i++) {
            var el = roles[i];
            summary.push(el.tagName + '[role=' + el.getAttribute('role') + '] text=' +
                (el.textContent || '').substring(0, 60).replace(/\n/g,' '));
        }
        return JSON.stringify(summary);
    })()
    """.trimIndent()

    /**
     * Scrape the inbox mail list. OWA uses role="listbox" for the mail list
     * and individual items are role="option" or role="row" depending on view.
     * We try multiple selector strategies.
     */
    private val INBOX_SCRAPE_JS = """
    (function() {
        // Strategy 1: role="option" items inside the mail list
        var items = document.querySelectorAll('[role="listbox"] [role="option"]');
        // Strategy 2: role="row" (compact/dense view)
        if (!items || items.length === 0) {
            items = document.querySelectorAll('[role="listbox"] [role="row"]');
        }
        // Strategy 3: data-convid items (conversation list items in newer OWA)
        if (!items || items.length === 0) {
            items = document.querySelectorAll('[data-convid]');
        }
        // Strategy 4: broad fallback — any aria-label containing "message"
        if (!items || items.length === 0) {
            items = document.querySelectorAll('div[aria-label*="essage"]');
        }
        if (!items || items.length === 0) return null;

        var mails = [];
        var max = Math.min(items.length, MAX_COUNT);
        for (var i = 0; i < max; i++) {
            var el = items[i];
            var text = (el.textContent || '').replace(/\s+/g, ' ').trim();

            // Try to find structured sub-elements
            var senderEl = el.querySelector('[class*="ender"], [class*="rom"], [data-testid*="ender"]');
            var subjectEl = el.querySelector('[class*="ubject"], [data-testid*="ubject"]');
            var previewEl = el.querySelector('[class*="review"], [class*="ody"], [data-testid*="review"]');

            var sender = senderEl ? senderEl.textContent.trim() : '';
            var subject = subjectEl ? subjectEl.textContent.trim() : '';
            var preview = previewEl ? previewEl.textContent.trim() : '';

            // Fallback: split the full text heuristically if structured selectors failed
            if (!sender && !subject) {
                var parts = text.split(/\s{2,}/);
                if (parts.length >= 2) {
                    sender = parts[0];
                    subject = parts[1];
                    preview = parts.slice(2).join(' ');
                } else {
                    sender = text.substring(0, 40);
                    subject = '';
                    preview = '';
                }
            }

            var isRead = !el.querySelector('[class*="nread"], [aria-label*="nread"]');

            mails.push({
                sender: sender.substring(0, 100),
                subject: subject.substring(0, 200),
                preview: preview.substring(0, 300),
                isRead: isRead
            });
        }
        return JSON.stringify(mails);
    })()
    """.trimIndent()

    private val CLICK_EMAIL_JS = """
    (function() {
        var items = document.querySelectorAll('[role="listbox"] [role="option"]');
        if (!items || items.length === 0) items = document.querySelectorAll('[role="listbox"] [role="row"]');
        if (!items || items.length === 0) items = document.querySelectorAll('[data-convid]');
        var target = items[EMAIL_INDEX];
        if (!target) return 'false';
        target.click();
        return 'true';
    })()
    """.trimIndent()

    private val READ_BODY_JS = """
    (function() {
        // The reading pane has role="document" or a div with class containing "ReadingPane"
        var pane = document.querySelector('[role="document"]');
        if (!pane) pane = document.querySelector('[class*="eadingPane"] [class*="ody"]');
        if (!pane) pane = document.querySelector('[class*="essageBody"]');
        if (!pane) pane = document.querySelector('[aria-label*="essage body"]');
        if (!pane) return null;
        return pane.innerText || pane.textContent || null;
    })()
    """.trimIndent()

    private val DELETE_EMAIL_JS = """
    (function() {
        // Look for the delete button in the reading pane toolbar
        var btn = document.querySelector('[aria-label="Delete"], [aria-label="delete"], [title="Delete"], button[name="Delete"]');
        if (!btn) {
            // Try finding by icon/class
            var btns = document.querySelectorAll('button[aria-label]');
            for (var i = 0; i < btns.length; i++) {
                if (btns[i].getAttribute('aria-label').toLowerCase().indexOf('delete') >= 0) {
                    btn = btns[i]; break;
                }
            }
        }
        if (!btn) return 'false';
        btn.click();
        return 'true';
    })()
    """.trimIndent()

    private val REPLY_EMAIL_JS = """
    (function() {
        // Click the Reply button
        var btn = document.querySelector('[aria-label="Reply"], [aria-label="reply"], [title="Reply"], button[name="Reply"]');
        if (!btn) {
            var btns = document.querySelectorAll('button[aria-label]');
            for (var i = 0; i < btns.length; i++) {
                if (btns[i].getAttribute('aria-label').toLowerCase() === 'reply') {
                    btn = btns[i]; break;
                }
            }
        }
        if (!btn) return 'false';
        btn.click();
        return 'true';
    })()
    """.trimIndent()

    private val INJECT_REPLY_TEXT_JS = """
    (function() {
        // Find the compose area (contenteditable div in OWA)
        var compose = document.querySelector('[role="textbox"][aria-label*="essage"], div[contenteditable="true"][aria-label*="essage"]');
        if (!compose) compose = document.querySelector('div[contenteditable="true"]');
        if (!compose) return 'false';
        compose.focus();
        compose.innerHTML = '<p>REPLY_TEXT_PLACEHOLDER</p>';
        // Trigger input event so OWA's React recognizes the change
        compose.dispatchEvent(new Event('input', {bubbles: true}));
        return 'true';
    })()
    """.trimIndent()

    private val CLICK_SEND_JS = """
    (function() {
        var btn = document.querySelector('[aria-label="Send"], [aria-label="send"], [title="Send"], button[name="Send"]');
        if (!btn) {
            var btns = document.querySelectorAll('button[aria-label]');
            for (var i = 0; i < btns.length; i++) {
                if (btns[i].getAttribute('aria-label').toLowerCase() === 'send') {
                    btn = btns[i]; break;
                }
            }
        }
        if (!btn) return 'false';
        btn.click();
        return 'true';
    })()
    """.trimIndent()

    private val FORWARD_EMAIL_JS = """
    (function() {
        var btn = document.querySelector('[aria-label="Forward"], [aria-label="forward"], [title="Forward"], button[name="Forward"]');
        if (!btn) {
            var btns = document.querySelectorAll('button[aria-label]');
            for (var i = 0; i < btns.length; i++) {
                if (btns[i].getAttribute('aria-label').toLowerCase() === 'forward') {
                    btn = btns[i]; break;
                }
            }
        }
        if (!btn) return 'false';
        btn.click();
        return 'true';
    })()
    """.trimIndent()

    private val INJECT_FORWARD_RECIPIENT_JS = """
    (function() {
        // Find the To field in forward compose
        var toField = document.querySelector('[aria-label="To"], input[aria-label="To"]');
        if (!toField) toField = document.querySelector('[role="combobox"][aria-label*="o"]');
        if (!toField) return 'false';
        toField.focus();
        toField.value = 'RECIPIENT_PLACEHOLDER';
        toField.dispatchEvent(new Event('input', {bubbles: true}));
        // Hit Enter to resolve the recipient
        toField.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', keyCode: 13, bubbles: true}));
        return 'true';
    })()
    """.trimIndent()

    private val GO_BACK_JS = """
    (function() {
        // Click the back/close button in the reading pane, or navigate to inbox
        var btn = document.querySelector('[aria-label="Close"], [aria-label="Back"], [aria-label="back to list"]');
        if (btn) { btn.click(); return 'true'; }
        // Fallback: navigate
        window.location.href = 'https://outlook.office.com/mail/inbox';
        return 'nav';
    })()
    """.trimIndent()
}
