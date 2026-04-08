package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import timber.log.Timber
import java.util.Locale

/**
 * Discovers assistant-capable apps and routes voice commands to them via broadcast.
 *
 * ## How it works
 * 1. Apps declare themselves via intent filter `com.silentpulse.action.ASSISTANT_CAPABLE`
 * 2. User says "Computer, tell Microcore to log my weight at 220"
 * 3. CommandRouter extracts "microcore" as target, strips boilerplate
 * 4. Broadcasts raw command "log my weight at 220" to Microcore's package
 * 5. Microcore does ALL parsing/fuzzy matching/DB work and replies via TTS_REPLY
 *
 * ## Supported phrasing (all route the same):
 * - "tell Microcore to log my weight at 220"
 * - "Microcore, log my weight at 220 pounds"
 * - "log my weight at 220 in Microcore"
 * - "ask Microcore how many calories I have left"
 *
 * SilentPulse does NOT know what each app does. It's just the ears and mouth.
 */
class CommandRouter(private val context: Context) {

    data class DiscoveredApp(
        val packageName: String,
        val label: String,
        /** Lowercase label for matching */
        val labelLower: String
    )

    /** Cached list of assistant-capable apps. Refreshed on demand. */
    private var discoveredApps: List<DiscoveredApp> = emptyList()

    /** Words stripped from the command before forwarding to the target app */
    private val ROUTING_BOILERPLATE = listOf(
        "tell", "ask", "open", "use", "launch", "in", "to", "on", "with", "using", "via", "through"
    )

    // ── App discovery ─────────────────────────────────────────────────────────

    /**
     * Queries PackageManager for apps declaring the ASSISTANT_CAPABLE intent filter.
     * Call this on service start and periodically to pick up new installs.
     */
    fun refreshApps() {
        val intent = Intent(ACTION_ASSISTANT_CAPABLE)
        val resolved = context.packageManager.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA)

        discoveredApps = resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.activityInfo?.loadLabel(context.packageManager)?.toString() ?: pkg
            DiscoveredApp(
                packageName = pkg,
                label = label,
                labelLower = label.lowercase(Locale.getDefault())
            )
        }.distinctBy { it.packageName }

        Timber.d("CommandRouter: discovered ${discoveredApps.size} assistant-capable apps: ${discoveredApps.map { it.label }}")
    }

    /**
     * Returns human-readable list of discovered app names.
     */
    fun getAppNames(): List<String> = discoveredApps.map { it.label }

    // ── Command routing ───────────────────────────────────────────────────────

    data class RouteResult(
        /** Package to send the broadcast to */
        val targetPackage: String,
        /** App label for TTS */
        val appLabel: String,
        /** The raw command string with app name and boilerplate stripped */
        val rawCommand: String
    )

    /**
     * Extracts the target app and raw command from the user's voice transcript.
     * Returns null if no known app is mentioned.
     *
     * Handles multiple phrasing styles:
     * - "tell Microcore to log weight 220"  →  app=Microcore, cmd="log weight 220"
     * - "Microcore log weight 220"          →  app=Microcore, cmd="log weight 220"
     * - "log weight 220 in Microcore"       →  app=Microcore, cmd="log weight 220"
     * - "ask Microcore what is my weight"   →  app=Microcore, cmd="what is my weight"
     */
    fun route(command: String): RouteResult? {
        if (discoveredApps.isEmpty()) refreshApps()

        val lower = command.lowercase(Locale.getDefault())

        // Find which app is mentioned (fuzzy match on label)
        val matchedApp = findApp(lower) ?: return null

        // Strip the app name from the command
        val withoutApp = removeAppName(lower, matchedApp.labelLower)

        // Strip routing boilerplate ("tell", "ask", "to", "in", etc.)
        val rawCommand = stripBoilerplate(withoutApp).trim()

        if (rawCommand.isBlank()) return null

        Timber.d("CommandRouter: route(\"$command\") → app=${matchedApp.label}, raw=\"$rawCommand\"")
        return RouteResult(
            targetPackage = matchedApp.packageName,
            appLabel = matchedApp.label,
            rawCommand = rawCommand
        )
    }

    /**
     * Sends the command to the target app via broadcast.
     */
    fun dispatch(routeResult: RouteResult, sessionId: String) {
        val intent = Intent(ACTION_EXECUTE_COMMAND).apply {
            setPackage(routeResult.targetPackage)
            putExtra(EXTRA_TRANSCRIPT, routeResult.rawCommand)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        context.sendBroadcast(intent)
        Timber.d("CommandRouter: dispatched to ${routeResult.targetPackage}: \"${routeResult.rawCommand}\" session=$sessionId")
    }

    /**
     * Requests the schema (available commands) from a target app.
     */
    fun requestSchema(targetPackage: String) {
        val intent = Intent(ACTION_REQUEST_SCHEMA).apply {
            setPackage(targetPackage)
        }
        context.sendBroadcast(intent)
        Timber.d("CommandRouter: schema requested from $targetPackage")
    }

    // ── Fuzzy app name matching ───────────────────────────────────────────────

    private fun findApp(lowerCommand: String): DiscoveredApp? {
        // Exact substring match first
        for (app in discoveredApps) {
            if (lowerCommand.contains(app.labelLower)) return app
        }
        // Fuzzy: handle spaces/split words ("micro core" → "microcore")
        val words = lowerCommand.split("\\s+".toRegex())
        for (app in discoveredApps) {
            // Check if consecutive words fuzzy-match the app label
            val appWords = app.labelLower.split("\\s+".toRegex())
            for (i in words.indices) {
                if (i + appWords.size <= words.size) {
                    val slice = words.subList(i, i + appWords.size).joinToString("")
                    val target = appWords.joinToString("")
                    if (levenshtein(slice, target) <= 2) return app
                }
            }
            // Single-word fuzzy match
            for (w in words) {
                if (levenshtein(w, app.labelLower) <= 2 && w.length >= 4) return app
            }
        }
        return null
    }

    private fun removeAppName(command: String, appLabelLower: String): String {
        // Try exact removal first
        var result = command.replace(appLabelLower, " ")
        if (result != command) return result.replace("\\s+".toRegex(), " ").trim()

        // Fuzzy removal: find the best matching span and remove it
        val words = command.split("\\s+".toRegex()).toMutableList()
        val appWords = appLabelLower.split("\\s+".toRegex())
        val target = appWords.joinToString("")
        for (i in words.indices) {
            for (len in appWords.size downTo 1) {
                if (i + len > words.size) continue
                val slice = words.subList(i, i + len).joinToString("")
                if (levenshtein(slice, target) <= 2) {
                    for (j in 0 until len) words[i + j] = ""
                    return words.filter { it.isNotEmpty() }.joinToString(" ")
                }
            }
        }
        return command
    }

    private fun stripBoilerplate(text: String): String {
        val words = text.split("\\s+".toRegex()).toMutableList()
        // Strip leading boilerplate words
        while (words.isNotEmpty() && words.first() in ROUTING_BOILERPLATE) {
            words.removeAt(0)
        }
        // Strip trailing boilerplate (e.g. "in" at the end after app name removed)
        while (words.isNotEmpty() && words.last() in ROUTING_BOILERPLATE) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString(" ")
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i-1][j] + 1, dp[i][j-1] + 1, dp[i-1][j-1] + cost)
        }
        return dp[a.length][b.length]
    }

    companion object {
        const val ACTION_ASSISTANT_CAPABLE = "com.silentpulse.action.ASSISTANT_CAPABLE"
        const val ACTION_EXECUTE_COMMAND = "com.silentpulse.action.EXECUTE_COMMAND"
        const val ACTION_TTS_REPLY = "com.silentpulse.action.TTS_REPLY"
        const val ACTION_REQUEST_SCHEMA = "com.silentpulse.action.REQUEST_SCHEMA"
        const val ACTION_REPORT_SCHEMA = "com.silentpulse.action.REPORT_SCHEMA"

        const val EXTRA_TRANSCRIPT = "EXTRA_TRANSCRIPT"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
        const val EXTRA_SPOKEN_TEXT = "EXTRA_SPOKEN_TEXT"
        const val EXTRA_REQUIRE_FOLLOWUP = "EXTRA_REQUIRE_FOLLOWUP"
        const val EXTRA_SCHEMA_JSON = "EXTRA_SCHEMA_JSON"
    }
}
