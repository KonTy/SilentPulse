package com.silentpulse.messenger.feature.assistant

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One-time setup Activity for Bing Chat (bing.com/chat).
 *
 * Open this once to:
 *   1. Solve the "I'm human" Cloudflare verification (if shown).
 *   2. Sign in to your Microsoft account (if required).
 *
 * After the chat interface loads, tap "Done". Cookies are saved permanently by
 * Android's WebView CookieManager and reused in future headless scraping calls.
 * You will not need to open this Activity again unless cookies expire.
 *
 * ── How to invoke ─────────────────────────────────────────────────────────────
 * Launch from Settings or any screen:
 *   startActivity(Intent(this, BingChatVerificationActivity::class.java))
 *
 * After tapping Done, WebAiSearchScraper.bingChatReady returns true. Wire
 * BingChatVerificationActivity into WebAiSearchScraper.fetchBingChat() when
 * you are ready to use Bing Chat as a scraping source.
 */
class BingChatVerificationActivity : Activity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── Toolbar ──────────────────────────────────────────────────────────
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 12, 24, 12)
        }
        TextView(this).apply {
            text = "Complete any Bing Chat verification, then tap Done"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            toolbar.addView(this)
        }
        Button(this).apply {
            text = "Done"
            setOnClickListener { onDone() }
            toolbar.addView(this)
        }

        // ── WebView ──────────────────────────────────────────────────────────
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled   = true
                // Use a real Chrome UA to minimise bot detection heuristics
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            // 'this' inside apply is the WebView — required by setAcceptThirdPartyCookies
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()   // handle redirects inside the view
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(toolbar)
        root.addView(webView)
        setContentView(root)

        webView.loadUrl("https://www.bing.com/chat")
    }

    private fun onDone() {
        // Flush cookies to disk so they survive the process being killed
        CookieManager.getInstance().flush()
        WebAiSearchScraper.setBingChatReady(applicationContext, true)
        finish()
    }

    @Deprecated("Deprecated in API 33")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
