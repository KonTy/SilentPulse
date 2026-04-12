package com.silentpulse.messenger.feature.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.silentpulse.messenger.feature.drivemode.NavEtaInfo
import com.silentpulse.messenger.feature.drivemode.SilentPulseNotificationListener

/**
 * Handles voice navigation/directions commands.
 *
 * Routing logic:
 *   - If the user says "google" or "google maps" → launches Google Maps.
 *   - Otherwise → prefers OsmAnd / Organic Maps (open-source, offline maps).
 *
 * Supports natural language like:
 *   - "navigate to Glacier National Park"
 *   - "plot a route to Denver and start navigation using google"
 *   - "directions to 123 Main Street Seattle with google maps"
 *   - "take me to the nearest gas station"
 *
 * No data is sent to Google unless the user explicitly requests Google Maps.
 */
class NavigationCommandHandler(private val context: Context) {

    companion object {
        private const val TAG = "NavCmd"

        /** Trigger phrases for navigation commands. */
        private val NAV_TRIGGERS = listOf(
            "navigate to", "navigation to",
            "directions to", "direction to",
            "route to", "plot a route",
            "take me to", "drive to", "drive me to",
            "how do i get to", "how to get to",
            "go to", "get directions",
            "start navigation"
        )

        /**
         * Google Maps package — only used when user explicitly requests it.
         */
        private const val GOOGLE_MAPS_PKG = "com.google.android.apps.maps"

        /**
         * Preferred open-source map apps in order. First installed one wins.
         */
        private val PREFERRED_MAP_APPS = listOf(
            "net.osmand",           // OsmAnd (F-Droid)
            "net.osmand.plus",      // OsmAnd+ (paid)
            "net.osmand.dev",       // OsmAnd dev
            "app.organicmaps",      // Organic Maps
            "app.organicmaps.debug" // Organic Maps debug
        )
    }

    /** Package of the most recently launched navigation app. */
    private var lastNavPackage: String? = null

    /** @return true if the command looks like a navigation request. */
    fun isNavigationCommand(command: String): Boolean {
        val c = command.lowercase()
        return NAV_TRIGGERS.any { c.contains(it) }
    }

    /** @return true if command means "stop / cancel current navigation". */
    fun isStopNavigationCommand(command: String): Boolean {
        val c = command.lowercase()
        val isStopVerb = c.contains("stop") || c.contains("cancel") || c.contains("end")
        val isNavWord  = c.contains("nav") || c.contains("route") || c.contains("direction") || c.contains("maps") || c.contains("osm") || c.contains("organic")
        return isStopVerb && isNavWord
    }

    /**
     * Stop whichever navigation app is currently running.
     * Tries the notification Stop action first; falls back to bringing the app
     * to foreground so the user can tap Stop themselves.
     */
    fun stopNavigation(command: String, onSpeak: (String, (() -> Unit)?) -> Unit) {
        // Determine target package from command, last-launched, or scan all notifications
        val c = command.lowercase()
        val targetPkg: String? = when {
            c.contains("google") -> GOOGLE_MAPS_PKG
            c.contains("osm") || c.contains("organic") ->
                PREFERRED_MAP_APPS.firstOrNull { pkg ->
                    try { context.packageManager.getPackageInfo(pkg, 0); true }
                    catch (_: Exception) { false }
                }
            else -> null // no app specified — scan all notifications below
        }

        Log.d(TAG, "Stopping navigation: targetPkg=$targetPkg lastNav=$lastNavPackage")

        // Try targeted stop first, then scan-all, then last-known, then give up
        val fired = when {
            targetPkg != null -> SilentPulseNotificationListener.fireStopNavAction(targetPkg)
            else -> SilentPulseNotificationListener.fireStopAnyNav()
        }

        if (fired) {
            // Navigation stop action fired. After a short delay, re-launch the app
            // with a plain launcher intent so it opens to the default map view — this
            // clears the residual route shown on screen.
            val clearPkg = targetPkg ?: lastNavPackage
            val clearIntent = clearPkg?.let {
                context.packageManager.getLaunchIntentForPackage(it)?.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
            }
            if (clearIntent != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val opts = android.app.ActivityOptions.makeBasic().apply {
                            setPendingIntentBackgroundActivityStartMode(
                                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            )
                        }
                        val pi = PendingIntent.getActivity(
                            context, 99, clearIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
                        )
                        pi.send(context, 0, null, null, null, null, opts.toBundle())
                        Log.d(TAG, "Relaunched $clearPkg to clear route")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not relaunch to clear route", e)
                    }
                }, 800)
            }
            onSpeak("Navigation stopped.", null)
        } else {
            // Notification action not available — open the app via PendingIntent
            val fallbackPkg = targetPkg ?: lastNavPackage
            val launchIntent = fallbackPkg?.let {
                context.packageManager.getLaunchIntentForPackage(it)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            if (launchIntent != null) {
                try {
                    PendingIntent.getActivity(
                        context, 0, launchIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    ).send()
                    onSpeak("Opening navigation app — tap Stop to end the route.", null)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not open nav app", e)
                    onSpeak("Could not stop navigation.", null)
                }
            } else {
                onSpeak("No active navigation found.", null)
            }
        }
        lastNavPackage = null
    }

    // ── ETA / arrival time ────────────────────────────────────────────────────

    /**
     * @return true if the command is asking for arrival time / ETA of the current route.
     * Matches phrases like "when will I arrive", "what time will I get there",
     * "how long left", "eta", "arrival time", "time remaining".
     */
    fun isEtaCommand(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("eta") ||
            c.contains("arrival time") ||
            c.contains("time of arrival") ||
            c.contains("time remaining") ||
            c.contains("how much time left") ||
            (c.contains("when") && (c.contains("arriv") || c.contains("get there") || c.contains("destinat"))) ||
            (c.contains("what time") && (c.contains("arriv") || c.contains("get there") || c.contains("there"))) ||
            (c.contains("how long") && (c.contains("left") || c.contains("remaining") || c.contains("destinat") || c.contains("get there")))
    }

    /**
     * Read the active navigation notification and speak the ETA back to the user.
     * Requires [SilentPulseNotificationListener] to be running and connected.
     */
    fun handleEta(onSpeak: (String, (() -> Unit)?) -> Unit) {
        val eta = SilentPulseNotificationListener.getNavEta(context)
        if (eta == null) {
            onSpeak("No active navigation found. Start a route first.", null)
            return
        }
        Log.d(TAG, "Nav ETA from ${eta.appName}: \"${eta.text}\"")
        onSpeak(buildEtaResponse(eta), null)
    }

    /**
     * Convert a raw [NavEtaInfo] into a natural-language spoken string.
     *
     * Handles common formats:
     *   OsmAnd:      "3 min · 1.2 km · Arriving at 3:45 PM"
     *   Google Maps: "Arriving at 3:45 PM · 15 min (8.2 km)"
     */
    private fun buildEtaResponse(eta: NavEtaInfo): String {
        val raw = eta.text

        // Extract "arriving at HH:MM AM/PM" — works for both apps
        val arrivalMatch = Regex(
            "arri(?:ving|val)\\s+(?:at\\s+)?(\\d{1,2}:\\d{2}\\s*(?:am|pm)?)",
            RegexOption.IGNORE_CASE
        ).find(raw)

        // Extract "N min" or "N mins"
        val minuteMatch = Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE).find(raw)

        // Extract "N.N km" or "N mi"
        val distMatch = Regex("([\\d.]+)\\s*(km|mi\\b)", RegexOption.IGNORE_CASE).find(raw)

        return buildString {
            when {
                arrivalMatch != null && minuteMatch != null -> {
                    append("You'll arrive at ${arrivalMatch.groupValues[1]}")
                    append(", in about ${minuteMatch.groupValues[1]} minutes")
                    distMatch?.let { append(", ${it.groupValues[1]} ${it.groupValues[2]} remaining") }
                    append(".")
                }
                arrivalMatch != null -> {
                    append("You'll arrive at ${arrivalMatch.groupValues[1]}.")
                }
                minuteMatch != null -> {
                    append("About ${minuteMatch.groupValues[1]} minutes remaining")
                    distMatch?.let { append(", ${it.groupValues[1]} ${it.groupValues[2]} to go") }
                    append(".")
                }
                else -> {
                    // Fallback: speak the raw notification text as-is
                    append("Navigation says: $raw.")
                }
            }
        }
    }

    /**
     * Parse the destination from a voice command and launch navigation.
     *
     * @param command The raw voice command.
     * @param onSpeak Callback to speak feedback to the user.
     */
    fun handleNavigation(command: String, onSpeak: (String, (() -> Unit)?) -> Unit) {
        val wantsGoogle = command.lowercase().let {
            it.contains("google maps") || it.contains("using google") ||
            it.contains("with google") || it.contains("on google") ||
            it.contains("via google") || Regex("\\bgoogle\\b").containsMatchIn(it)
        }

        val destination = extractDestination(command)
        if (destination.isNullOrBlank()) {
            onSpeak("I didn't catch the destination. Try saying navigate to and then the place name.", null)
            return
        }

        Log.d(TAG, "Navigation destination: \"$destination\" (google=$wantsGoogle)")

        if (wantsGoogle) {
            lastNavPackage = GOOGLE_MAPS_PKG
            launchGoogleMaps(destination, onSpeak)
        } else {
            lastNavPackage = findInstalledMapApp() ?: PREFERRED_MAP_APPS.first()
            launchOpenSourceMaps(destination, onSpeak)
        }
    }

    private fun launchGoogleMaps(destination: String, onSpeak: (String, (() -> Unit)?) -> Unit) {
        val pm = context.packageManager
        val googleInstalled = try { pm.getPackageInfo(GOOGLE_MAPS_PKG, 0); true }
            catch (_: PackageManager.NameNotFoundException) { false }

        if (!googleInstalled) {
            Log.d(TAG, "Google Maps not installed, falling back to open-source")
            onSpeak("Google Maps is not installed. Using your open-source map app instead.") {}
            launchOpenSourceMaps(destination, onSpeak)
            return
        }

        // google.navigation:q= starts turn-by-turn immediately.
        // On Android 14+ (API 34), foreground services are BAL-blocked even via
        // PendingIntent.send() unless we pass ActivityOptions that explicitly
        // allow background activity starts.  FLAG_CANCEL_CURRENT ensures a fresh
        // token is created every call so the destination URI is never stale.
        val navUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, navUri).apply {
            setPackage(GOOGLE_MAPS_PKG)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        onSpeak("Starting navigation to $destination with Google Maps.") {
            // On Android 14+ (API 34+) direct startActivity from a service is silently
            // killed by ActivityTaskManager even with FLAG_ACTIVITY_NEW_TASK — it throws
            // no exception but the activity never appears. Always go through PendingIntent
            // with BOTH setPendingIntentBackgroundActivityStartMode AND
            // setPendingIntentCreatorBackgroundActivityStartMode set to ALLOWED.
            fun makeBalOpts(): android.os.Bundle {
                val opts = android.app.ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    opts.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                return opts.toBundle()
            }
            // To set creator opts, we must pass the bundle during PendingIntent creation,
            // not during send().
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
                makeBalOpts()
            )
            try {
                pending.send(context, 0, null, null, null, null, makeBalOpts())
                Log.d(TAG, "Google Maps PendingIntent sent (BAL creator+sender allowed)")
            } catch (e: Exception) {
                Log.e(TAG, "Google Maps PendingIntent failed", e)
                // Fallback: bare geo: URI without package restriction (system chooser)
                try {
                    val fallbackPi = PendingIntent.getActivity(
                        context, 1,
                        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
                        makeBalOpts()
                    )
                    fallbackPi.send(context, 0, null, null, null, null, makeBalOpts())
                    Log.d(TAG, "Google Maps geo: fallback PI sent")
                } catch (e2: Exception) {
                    Log.e(TAG, "All Google Maps launch attempts failed", e2)
                }
            }
        }
    }

    private fun launchOpenSourceMaps(destination: String, onSpeak: (String, (() -> Unit)?) -> Unit) {
        Log.d(TAG, "Open-source navigation to: \"$destination\"")
        val mapApp = findInstalledMapApp()
        val appName = mapApp?.let { getAppLabel(it) } ?: "Maps"
        val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")

        // Build intent: target specific app if found, otherwise let system choose
        val intent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (mapApp != null) setPackage(mapApp)
        }

        onSpeak("Navigating to $destination with $appName.") {
            // Wrap in a PendingIntent and use MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            // to bypass Android 14+ BAL restrictions from a foreground service.
            fun sendViaPI(i: Intent, requestCode: Int) {
                val opts = android.app.ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    opts.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                
                // Pass bundle during creation to act as creator opt-in
                val pi = PendingIntent.getActivity(
                    context, requestCode, i,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
                    opts.toBundle()
                )
                // Pass bundle during send to act as sender opt-in
                pi.send(context, 0, null, null, null, null, opts.toBundle())
            }
            try {
                sendViaPI(intent, 1)
                Log.d(TAG, "Open-source maps PendingIntent sent for $mapApp")
            } catch (e: Exception) {
                Log.w(TAG, "Targeted launch failed ($mapApp), trying bare geo: URI", e)
                // Retry without a specific package — system chooser will appear
                try {
                    sendViaPI(Intent(Intent.ACTION_VIEW, geoUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }, 2)
                } catch (e2: Exception) {
                    Log.e(TAG, "All navigation launch attempts failed", e2)
                    onSpeak("No map app found. Please install OsmAnd or Organic Maps.", null)
                }
            }
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun extractDestination(command: String): String? {
        val c = command.lowercase().trim()

        // Remove common filler phrases including Google Map references
        var cleaned = c
            .replace("using google maps", "")
            .replace("with google maps", "")
            .replace("on google maps", "")
            .replace("via google maps", "")
            .replace("using google", "")
            .replace("with google", "")
            .replace("via google", "")
            .replace(Regex("\\bgoogle\\b"), "")
            .replace("and start navigation", "")
            .replace("and start navigating", "")
            .replace("and navigate", "")
            .replace("from here", "")
            .replace("from my location", "")
            .replace("from current location", "")
            .replace("please", "")
            .replace(com.silentpulse.messenger.feature.drivemode.WidgetPrefs.getWakeWord(context), "")
            .trim()

        // Try each trigger phrase and extract what comes after it
        for (trigger in NAV_TRIGGERS.sortedByDescending { it.length }) {
            val idx = cleaned.indexOf(trigger)
            if (idx >= 0) {
                val afterTrigger = cleaned.substring(idx + trigger.length).trim()
                if (afterTrigger.isNotEmpty()) {
                    return afterTrigger
                        .replaceFirst(Regex("^(the|a|an)\\s+"), "") // "the nearest" → "nearest"
                        .trim()
                        .replaceFirstChar { it.uppercase() } // Capitalize for display
                }
            }
        }

        // Fallback: if "to" appears, take everything after the last "to"
        val toIdx = cleaned.lastIndexOf(" to ")
        if (toIdx >= 0) {
            val dest = cleaned.substring(toIdx + 4).trim()
            if (dest.isNotEmpty()) {
                return dest.replaceFirstChar { it.uppercase() }
            }
        }

        return null
    }

    private fun findInstalledMapApp(): String? {
        val pm = context.packageManager
        for (pkg in PREFERRED_MAP_APPS) {
            try {
                pm.getPackageInfo(pkg, 0)
                Log.d(TAG, "Found map app: $pkg")
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed, try next
            }
        }
        Log.d(TAG, "No preferred map app found")
        return null
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }
    }
}
