package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import timber.log.Timber
import java.util.Locale
import java.util.UUID

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
class CommandRouter(
    private val context: Context,
    elapsedTime: () -> Long = SystemClock::elapsedRealtime
) {

    data class DiscoveredApp(
        val packageName: String,
        val label: String,
        /** Lowercase label for matching */
        val labelLower: String,
        /** Command prefixes that auto-route to this app (e.g. "journal", "todo") */
        val commandPrefixes: List<String> = emptyList()
    )

    /** Cached list of assistant-capable apps. Refreshed on demand. */
    private var discoveredApps: List<DiscoveredApp> = emptyList()
    private val replySessions = AssistantReplySessions(elapsedTime)

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
        val resolved = context.packageManager.queryBroadcastReceivers(
            intent,
            PackageManager.GET_META_DATA
        )

        discoveredApps = resolved.mapNotNull { info ->
            val receiver = info.activityInfo ?: return@mapNotNull null
            val pkg = receiver.packageName
            if (!receiver.enabled || !receiver.exported || !hasApprovedSignature(pkg) ||
                pkg == MICROCORE_PACKAGE && receiver.metaData?.getInt(PROTOCOL_VERSION, 0) != 2) {
                return@mapNotNull null
            }
            val label = APPROVED_APPS.getValue(pkg)
            val prefixes = receiver.metaData
                ?.getString("com.silentpulse.command_prefixes")
                ?.split(",")
                ?.map { it.trim().lowercase(Locale.ROOT) }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            DiscoveredApp(
                packageName = pkg,
                label = label,
                labelLower = label.lowercase(Locale.ROOT),
                commandPrefixes = prefixes
            )
        }.distinctBy { it.packageName }

        Timber.d("CommandRouter: discovered %d approved companion apps", discoveredApps.size)
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
        refreshApps()

        val lower = command.lowercase(Locale.ROOT)

        // Find which app is mentioned (fuzzy match on label or prefix match)
        val matchedApp = findApp(lower) ?: return null

        // Check if matched via command prefix — if so, send the full command as-is
        val matchedViaPrefix = matchedApp.commandPrefixes.any { prefix ->
            lower.startsWith("$prefix ") || lower == prefix
        }

        val rawCommand = if (matchedViaPrefix) {
            // Prefix match: send entire transcript (Grafium needs "journal X" to parse)
            lower.trim()
        } else {
            // Label match: strip the app name and boilerplate
            val withoutApp = removeAppName(lower, matchedApp.labelLower)
            stripBoilerplate(withoutApp).trim()
        }

        if (rawCommand.isBlank()) return null

        Timber.d("CommandRouter: approved route selected (prefix=%s)", matchedViaPrefix)
        return RouteResult(
            targetPackage = matchedApp.packageName,
            appLabel = matchedApp.label,
            rawCommand = rawCommand
        )
    }

    /**
     * Sends the command to the target app via broadcast.
     */
    fun dispatch(routeResult: RouteResult, sessionId: String): Boolean {
        if (!isTrustedTarget(routeResult.targetPackage)) {
            Timber.w("CommandRouter: rejected an unapproved or incorrectly signed target")
            return false
        }
        val uid = targetUid(routeResult.targetPackage)
        val nonce = newSessionId()
        val separateNonce = routeResult.targetPackage == MICROCORE_PACKAGE
        val wireSessionId = if (separateNonce) sessionId else nonce
        replySessions.clear()
        if (uid == null || !replySessions.expect(
                nonce, routeResult.targetPackage, uid, sessionId, wireSessionId, separateNonce
            )) {
            Timber.w("CommandRouter: rejected an invalid or conflicting request capability")
            return false
        }
        val intent = Intent(ACTION_EXECUTE_COMMAND).apply {
            setPackage(routeResult.targetPackage)
            putExtra(EXTRA_TRANSCRIPT, routeResult.rawCommand)
            putExtra(EXTRA_SESSION_ID, wireSessionId)
            putExtra(EXTRA_REPLY_NONCE, nonce)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        return try {
            context.sendBroadcast(intent)
            Timber.d("CommandRouter: command dispatched to an approved companion")
            true
        } catch (_: SecurityException) {
            replySessions.cancel(nonce)
            Timber.w("CommandRouter: companion rejected command delivery")
            false
        }
    }

    /**
     * Requests the schema (available commands) from a target app.
     */
    fun requestSchema(targetPackage: String): Boolean {
        if (targetPackage != MICROCORE_PACKAGE || !isTrustedTarget(targetPackage)) {
            Timber.w("CommandRouter: authenticated schema transport is unavailable")
            return false
        }
        val uid = targetUid(targetPackage)
        if (uid == null) {
            Timber.w("CommandRouter: approved companion is unavailable")
            return false
        }
        val sessionId = newSessionId()
        val nonce = newSessionId()
        replySessions.clear()
        if (!replySessions.expect(
                nonce, targetPackage, uid, sessionId, sessionId, true, AssistantReplySessions.Kind.SCHEMA
            )) {
            Timber.w("CommandRouter: could not authorize schema response")
            return false
        }
        val intent = Intent(ACTION_REQUEST_SCHEMA).apply {
            setPackage(targetPackage)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_REPLY_NONCE, nonce)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        return try {
            context.sendBroadcast(intent)
            Timber.d("CommandRouter: requested authenticated companion schema")
            true
        } catch (_: SecurityException) {
            replySessions.cancel(nonce)
            Timber.w("CommandRouter: companion rejected schema delivery")
            false
        }
    }

    private fun hasApprovedSignature(packageName: String): Boolean =
        packageName in APPROVED_APPS &&
            context.packageManager.checkSignatures(context.packageName, packageName) == PackageManager.SIGNATURE_MATCH

    fun isTrustedTarget(packageName: String): Boolean {
        if (!hasApprovedSignature(packageName)) return false
        if (packageName != MICROCORE_PACKAGE) return true
        val intent = Intent(ACTION_ASSISTANT_CAPABLE).apply { setPackage(packageName) }
        return context.packageManager.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA).any {
            val receiver = it.activityInfo
            receiver?.packageName == packageName && receiver.enabled && receiver.exported &&
                receiver.metaData?.getInt(PROTOCOL_VERSION, 0) == 2
        }
    }

    data class AuthorizedReply(val sessionId: String, val packageName: String)

    fun acceptReply(sessionId: String?, replyNonce: String? = null, senderUid: Int? = null): AuthorizedReply? =
        acceptResponse(sessionId, replyNonce, senderUid, AssistantReplySessions.Kind.COMMAND)

    fun acceptSchemaReply(sessionId: String?, replyNonce: String?, senderUid: Int?): AuthorizedReply? =
        acceptResponse(sessionId, replyNonce, senderUid, AssistantReplySessions.Kind.SCHEMA)

    private fun acceptResponse(
        sessionId: String?,
        replyNonce: String?,
        senderUid: Int?,
        kind: AssistantReplySessions.Kind
    ): AuthorizedReply? {
        val reply = replySessions.consume(replyNonce ?: sessionId)
        if (reply == null || !isTrustedTarget(reply.packageName) ||
            targetUid(reply.packageName) != reply.uid || senderUid != null && senderUid != reply.uid ||
            reply.kind != kind || reply.wireSessionId != sessionId ||
            reply.requiresReplyNonce && replyNonce == null) {
            Timber.w("CommandRouter: ignored an unsolicited, expired or untrusted reply")
            return null
        }
        return AuthorizedReply(reply.sessionId, reply.packageName)
    }

    fun clearPendingReplies() = replySessions.clear()

    fun declaredCommandPrefixes(packageName: String): List<String> {
        refreshApps()
        return discoveredApps.firstOrNull { it.packageName == packageName }?.commandPrefixes.orEmpty()
    }

    /** Private commands must stay local even without a named or discoverable companion. */
    fun isCompanionCommand(command: String): Boolean {
        if (isPrivateHealthCommand(command)) return true
        val apps = APPROVED_APPS.map { (pkg, label) ->
            val currentPrefixes = discoveredApps.firstOrNull { it.packageName == pkg }?.commandPrefixes.orEmpty()
            DiscoveredApp(pkg, label, label.lowercase(Locale.ROOT), PRIVATE_PREFIXES + currentPrefixes)
        }
        return findApp(command.lowercase(Locale.ROOT), apps) != null
    }

    fun isPrivateHealthCommand(command: String): Boolean {
        val lower = command.lowercase(Locale.ROOT)
        // Treat health topics as local by default rather than guessing whether a reading is personal.
        // Explicit online requests are handled separately by the service.
        return HEALTH_TOPICS.containsMatchIn(lower) ||
            FAST_DURATION.containsMatchIn(lower)
    }

    @Suppress("DEPRECATION")
    private fun targetUid(packageName: String): Int? = try {
        context.packageManager.getApplicationInfo(packageName, 0).takeIf { it.enabled }?.uid
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    // ── Fuzzy app name matching ───────────────────────────────────────────────

    /**
     * Public entry point: checks if a string matches a known app name.
     * Returns the matched app or null.
     */
    fun findAppByName(lowerCommand: String): DiscoveredApp? {
        refreshApps()
        return findApp(lowerCommand)
    }

    private fun findApp(lowerCommand: String, apps: List<DiscoveredApp> = discoveredApps): DiscoveredApp? {
        // Check command prefix matches first (e.g. "journal" → Grafium)
        for (app in apps) {
            for (prefix in app.commandPrefixes) {
                if (lowerCommand.startsWith("$prefix ") || lowerCommand == prefix) {
                    return app
                }
            }
        }
        // Exact substring match on app label
        for (app in apps) {
            if (lowerCommand.contains(app.labelLower)) return app
        }
        // Fuzzy: handle spaces/split words ("micro core" → "microcore")
        val words = lowerCommand.split("\\s+".toRegex())
        for (app in apps) {
            // Check if consecutive words fuzzy-match the app label
            val appWords = app.labelLower.split("\\s+".toRegex())
            for (i in words.indices) {
                for (length in 1..appWords.size + 1) {
                    if (i + length > words.size) continue
                    val slice = words.subList(i, i + length).joinToString("")
                    val target = appWords.joinToString("")
                    if (slice.length >= 4 && levenshtein(slice, target) <= 2) return app
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
            for (len in appWords.size + 1 downTo 1) {
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
        private const val MICROCORE_PACKAGE = "com.microcore.microcore"
        private const val PROTOCOL_VERSION = "com.silentpulse.voice_protocol_version"
        const val VOICE_COMMAND_PERMISSION = "com.silentpulse.messenger.permission.VOICE_COMMAND"
        private val APPROVED_APPS = mapOf(
            "com.grafium.app" to "Grafium",
            MICROCORE_PACKAGE to "Microcore"
        )
        private val PRIVATE_PREFIXES = listOf(
            "journal", "add journal", "note", "add note", "note to self",
            "todo", "to-do", "to do", "task", "add todo", "add task",
            "read my journal", "read journal", "read my todos", "read my tasks",
            "what are my todos", "what are my tasks", "log weight", "log my weight",
            "log food", "log meal", "log water"
        )
        private val HEALTH_TOPICS = Regex(
            "\\b(?:weight|weigh(?:ed)?|bmi|body\\s+fat|calories?|kcals?|fasting|fasted|" +
                "blood\\s+(?:pressure|sugar|glucose)|bp|systolic|diastolic|heart\\s+rate|pulse)\\b"
        )
        private val FAST_DURATION = Regex("\\b(?:how\\s+long|when)\\b.*\\bfast\\b|\\b(?:my|our)\\s+fast\\b")

        fun newSessionId(): String = UUID.randomUUID().toString()

        const val ACTION_ASSISTANT_CAPABLE = "com.silentpulse.action.ASSISTANT_CAPABLE"
        const val ACTION_EXECUTE_COMMAND = "com.silentpulse.action.EXECUTE_COMMAND"
        const val ACTION_TTS_REPLY = "com.silentpulse.action.TTS_REPLY"
        const val ACTION_REQUEST_SCHEMA = "com.silentpulse.action.REQUEST_SCHEMA"
        const val ACTION_REPORT_SCHEMA = "com.silentpulse.action.REPORT_SCHEMA"

        const val EXTRA_TRANSCRIPT = "EXTRA_TRANSCRIPT"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
        const val EXTRA_REPLY_NONCE = "EXTRA_REPLY_NONCE"
        const val EXTRA_SPOKEN_TEXT = "EXTRA_SPOKEN_TEXT"
        const val EXTRA_REQUIRE_FOLLOWUP = "EXTRA_REQUIRE_FOLLOWUP"
        const val EXTRA_SCHEMA_JSON = "EXTRA_SCHEMA_JSON"
    }
}
