package com.silentpulse.messenger.feature.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * One-shot setup receiver for the Brave Search API key.
 *
 * Usage (one-time adb command after getting a free key at api-dashboard.search.brave.com):
 *
 *   adb shell am broadcast \
 *       -a com.silentpulse.SET_BRAVE_KEY \
 *       -n com.silentpulse.messenger/.feature.assistant.ApiKeyReceiver \
 *       --es key "YOUR_KEY_HERE"
 *
 * The key is stored in private SharedPreferences on-device — never uploaded.
 * Declared in AndroidManifest.xml with android:exported="false" so only local
 * or same-signature callers can deliver the broadcast.
 */
class ApiKeyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SET_BRAVE_KEY = "com.silentpulse.SET_BRAVE_KEY"
        private const val TAG = "ApiKeyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_BRAVE_KEY) return
        val key = intent.getStringExtra("key").orEmpty().trim()
        if (key.isEmpty()) {
            Log.w(TAG, "Received SET_BRAVE_KEY with empty key — ignoring")
            return
        }
        BraveSearchHandler.saveApiKey(context, key)
        Log.i(TAG, "Brave API key updated (${key.length} chars)")
    }
}
