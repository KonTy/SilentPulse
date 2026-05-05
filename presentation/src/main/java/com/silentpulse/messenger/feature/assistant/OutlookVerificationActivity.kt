package com.silentpulse.messenger.feature.assistant

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.EditorInfo

/**
 * One-time login Activity for Outlook Web App.
 *
 * Opens outlook.office.com in a visible WebView so the user can:
 *   1. Enter their corporate credentials.
 *   2. Complete MFA (Authenticator push, SMS code, etc.).
 *
 * After the inbox loads, tap "Done". Cookies are saved permanently by
 * Android's WebView CookieManager and reused by [OutlookWebScraper]
 * for headless inbox reading, replying, deleting, and forwarding.
 *
 * ── How to invoke ─────────────────────────────────────────────────────────────
 * From ADB:
 *   adb shell am start -n com.silentpulse.messenger/.feature.assistant.OutlookVerificationActivity
 *
 * Or from code:
 *   startActivity(Intent(this, OutlookVerificationActivity::class.java))
 */
class OutlookVerificationActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText

    private fun navigateTo(input: String) {
        var url = input
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        webView.loadUrl(url)
        urlBar.setText(url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }

        // ── Toolbar ──────────────────────────────────────────────────────────
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        // Row 1: instruction + Done button
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        TextView(this).apply {
            text = "Log in to Outlook, then tap Done"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            topRow.addView(this)
        }
        Button(this).apply {
            text = "Done"
            setOnClickListener { onDone() }
            topRow.addView(this)
        }
        toolbar.addView(topRow)

        // Row 2: address bar + Go button
        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        val urlBar = EditText(this).apply {
            setText("https://outlook.office.com/mail/inbox")
            textSize = 12f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigateTo(text.toString().trim())
                    true
                } else false
            }
        }
        urlRow.addView(urlBar)
        Button(this).apply {
            text = "Go"
            setOnClickListener { navigateTo(urlBar.text.toString().trim()) }
            urlRow.addView(this)
        }
        toolbar.addView(urlRow)
        this.urlBar = urlBar

        // ── WebView ──────────────────────────────────────────────────────────
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled   = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            // Handle popups: load popup URLs in the same WebView (MFA flows use window.open)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    // Extract the URL from the popup request and load it in our main WebView
                    val transport = resultMsg?.obj as? android.webkit.WebView.WebViewTransport
                    if (transport != null) {
                        // Create a temporary WebView to capture the URL, then redirect to main
                        val popupView = WebView(this@OutlookVerificationActivity).apply {
                            settings.javaScriptEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    Log.d(TAG, "Popup redirect → loading in main WebView: $url")
                                    webView.loadUrl(url)
                                    return true
                                }
                            }
                        }
                        transport.webView = popupView
                        resultMsg.sendToTarget()
                        return true
                    }
                    return false
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    val host = request?.url?.host ?: "?"
                    val code = error?.errorCode ?: -1
                    Log.e(TAG, "ERR [$code] host=$host url=${request?.url}")
                }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    val host = android.net.Uri.parse(error?.url ?: "").host ?: "?"
                    Log.e(TAG, "SSL BLOCKED host=$host  url=${error?.url}")
                    // Do NOT proceed — let it fail so blocked domains are visible in logcat
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "Page finished: $url")
                    url?.let { urlBar.setText(it) }
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(toolbar)
        root.addView(webView)
        setContentView(root)

        webView.loadUrl("https://outlook.office.com/mail/inbox")
    }

    private fun onDone() {
        CookieManager.getInstance().flush()
        OutlookWebScraper.setOutlookReady(applicationContext, true)
        Log.i(TAG, "Outlook session saved — cookies flushed, outlookReady=true")
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

    companion object {
        private const val TAG = "OutlookVerify"
    }
}
