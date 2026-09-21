package com.silentpulse.messenger.feature.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.silentpulse.messenger.BuildConfig
import com.silentpulse.messenger.feature.assistant.WebAiSearchScraper

/**
 * Debug-only scraper state flags; never writes queries, pages or replies to disk.
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

        Log.i(TAG, "ai_diagnostics_requested")

        // Create a temporary WebAiSearchScraper for the dump.
        // This avoids touching the voice assistant's live instance.
        val scraper = WebAiSearchScraper(context.applicationContext)
        scraper.debugDump(query) { summary ->
            Log.i(TAG, summary)
            scraper.destroy()
        }
    }
}
