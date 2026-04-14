package com.silentpulse.messenger.feature.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.silentpulse.messenger.BuildConfig
import com.silentpulse.messenger.feature.assistant.WebAiSearchScraper

/**
 * Debug-only receiver to dump Brave Search scraper state for selector tuning.
 *
 * Usage:
 *   adb shell am broadcast -n com.silentpulse.messenger/.feature.debug.BraveDebugReceiver \
 *       -a com.silentpulse.messenger.BRAVE_DEBUG \
 *       --es query "what is quantum physics"
 *
 * Then pull results:
 *   adb pull /sdcard/Android/data/com.silentpulse.messenger/files/brave_debug/ ./brave_debug/
 *
 * Files produced:
 *   1_http_raw.html       — raw HTML from search.brave.com (HTTP scrape path)
 *   2_http_extracted.txt   — what extractBraveAnswer() returns (or null reason)
 *   3_leo_dom.html         — full DOM from Leo /ask WebView after 4s hydration
 *   4_leo_selectors.txt    — JSON: what every CSS selector matched + interesting elements
 *   5_leo_state.txt        — internal state: leoInitialized, leoTurnCount, etc.
 *
 * Open 3_leo_dom.html in Chrome, hit F12, and find the AI answer element.
 * Then tell Copilot the class name / selector and we'll update the scraper.
 *
 * All logic gated on BuildConfig.DEBUG — inert in release builds.
 */
class BraveDebugReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BraveDebug"
        const val ACTION = "com.silentpulse.messenger.BRAVE_DEBUG"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION) return

        val query = intent.getStringExtra("query")
            ?: "what is quantum physics"

        Log.i(TAG, "Starting debug dump for query: \"$query\"")

        // Create a temporary WebAiSearchScraper for the dump.
        // This avoids touching the voice assistant's live instance.
        val scraper = WebAiSearchScraper(context.applicationContext)
        scraper.debugDump(query) { summary ->
            Log.i(TAG, summary)
            scraper.destroy()
        }
    }
}
