package com.silentpulse.messenger.feature.assistant

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient

/** Page scripts can include private content and authentication URLs in console output. */
open class DiagnosticFreeWebChromeClient : WebChromeClient() {
    final override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
}
