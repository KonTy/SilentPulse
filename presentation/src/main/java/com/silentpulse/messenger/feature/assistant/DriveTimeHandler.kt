package com.silentpulse.messenger.feature.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Handles "how long to X" / "drive time to X" / "how far to X" voice queries.
 *
 * Pipeline:
 *   1. Parse destination from the voice command.
 *   2. Get the device's last known location (GPS / network).
 *   3. Geocode the destination via Nominatim (OpenStreetMap — no API key).
 *   4. Route via OSRM public demo server (open-source routing engine — no API key).
 *   5. Speak duration + distance.
 *
 * Domains whitelisted in network_security_config.xml:
 *   - nominatim.openstreetmap.org   (geocoding)
 *   - router.project-osrm.org       (routing)
 */
class DriveTimeHandler(private val context: Context) {

    companion object {
        private const val TAG = "DriveTimeCmd"

        private val DRIVE_TRIGGERS = listOf(
            "how long will it take", "how long would it take", "how long does it take",
            "how long to drive", "how long to get",
            "how long to", "how far to", "how far is",
            "drive time to", "travel time to", "eta to",
            "how many hours", "how many miles", "distance to",
            "time to drive"
        )
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun isDriveTimeCommand(command: String): Boolean {
        val c = command.lowercase()
        return DRIVE_TRIGGERS.any { c.contains(it) } &&
                (c.contains(" to ") || c.contains("drive") || c.contains("travel"))
    }

    fun fetchAndSpeak(command: String, onResult: (String) -> Unit) {
        if (!isNetworkAvailable()) {
            onResult("No data connection. Please turn on mobile data or Wi-Fi, then try again.")
            return
        }

        val destination = extractDestination(command)
        if (destination.isNullOrBlank()) {
            onResult("I didn't catch the destination. Try saying: how long to, and then the place name.")
            return
        }

        Log.d(TAG, "Drive time destination: \"$destination\"")

        executor.execute {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                // Get current location first
                val origin = getLastLocation()
                if (origin == null) {
                    mainHandler.post {
                        onResult(
                            "I need your location to calculate drive time. " +
                            "Please make sure location permission is granted and location is enabled."
                        )
                    }
                    return@execute
                }

                // Geocode destination
                val destCoords = nominatimGeocode(destination)
                if (destCoords == null) {
                    mainHandler.post { onResult("I couldn't find $destination on the map. Try using a more specific place name.") }
                    return@execute
                }

                // Route via OSRM
                val result = osrmRoute(origin.first, origin.second, destCoords.first, destCoords.second, destination)
                mainHandler.post { onResult(result) }
            } catch (e: Exception) {
                Log.e(TAG, "Drive time fetch failed", e)
                mainHandler.post { onResult("I couldn't calculate the drive time right now. Try again later.") }
            }
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun extractDestination(command: String): String? {
        var c = command.lowercase()
            .replace("how long will it take to drive to", "DRIVE_TO")
            .replace("how long would it take to drive to", "DRIVE_TO")
            .replace("how long does it take to drive to", "DRIVE_TO")
            .replace("how long to get to", "DRIVE_TO")
            .replace("how long to drive to", "DRIVE_TO")
            .replace("how long to", "DRIVE_TO")
            .replace("how far to", "DRIVE_TO")
            .replace("how far is", "DRIVE_TO")
            .replace("drive time to", "DRIVE_TO")
            .replace("travel time to", "DRIVE_TO")
            .replace("eta to", "DRIVE_TO")
            .replace("distance to", "DRIVE_TO")
            .trim()

        val marker = "drive_to"
        val idx = c.indexOf(marker)
        if (idx >= 0) {
            val dest = c.substring(idx + marker.length)
                .replace("from here", "")
                .replace("from my location", "")
                .replace("from current location", "")
                .replace(com.silentpulse.messenger.feature.drivemode.WidgetPrefs.getWakeWord(context), "")
                .trim()
            if (dest.isNotEmpty()) return dest.replaceFirstChar { it.uppercase() }
        }

        // Fallback: take everything after the last " to "
        val toIdx = c.lastIndexOf(" to ")
        if (toIdx >= 0) {
            val dest = c.substring(toIdx + 4)
                .replace("from here", "").replace("from my location", "")
                .trim()
            if (dest.isNotEmpty()) return dest.replaceFirstChar { it.uppercase() }
        }
        return null
    }

    // ── Geocoding + routing ───────────────────────────────────────────────────

    private fun nominatimGeocode(place: String): Pair<Double, Double>? {
        val url = "https://nominatim.openstreetmap.org/search" +
                "?q=${URLEncoder.encode(place, "UTF-8")}&format=json&limit=1"
        Log.d(TAG, "Nominatim: $url")
        val body = httpGet(url)
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val obj = arr.getJSONObject(0)
        return Pair(obj.getDouble("lat"), obj.getDouble("lon"))
    }

    private fun osrmRoute(
        originLat: Double, originLon: Double,
        destLat: Double, destLon: Double,
        destName: String
    ): String {
        // OSRM expects lon,lat order
        val url = "https://router.project-osrm.org/route/v1/driving/" +
                "$originLon,$originLat;$destLon,$destLat?overview=false"
        Log.d(TAG, "OSRM: $url")
        val json = JSONObject(httpGet(url))
        val code = json.optString("code", "")
        if (code != "Ok") return "No route found to $destName."

        val routes = json.optJSONArray("routes") ?: return "No route found to $destName."
        if (routes.length() == 0) return "No route found to $destName."
        val route = routes.getJSONObject(0)
        val durationSecs = route.getDouble("duration")
        val distanceMeters = route.getDouble("distance")

        val totalMins = (durationSecs / 60).roundToInt()
        val distanceMiles = (distanceMeters / 1609.34).roundToInt()

        val hours = totalMins / 60
        val mins = totalMins % 60
        val timeStr = when {
            hours == 0 -> "$mins minutes"
            mins == 0 -> if (hours == 1) "1 hour" else "$hours hours"
            else -> "${if (hours == 1) "1 hour" else "$hours hours"} and $mins minutes"
        }

        return "About $timeStr to $destName. That's approximately $distanceMiles miles."
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private fun getLastLocation(): Pair<Double, Double>? {
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarseGranted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            Log.w(TAG, "No location permission")
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null) {
                    Log.d(TAG, "Location from $provider: ${loc.latitude}, ${loc.longitude}")
                    return Pair(loc.latitude, loc.longitude)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Provider $provider unavailable", e)
            }
        }
        Log.w(TAG, "No last known location available")
        return null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "SilentPulse/1.0 (Android; offline-first)")
        return try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } finally {
            conn.disconnect()
        }
    }
}
