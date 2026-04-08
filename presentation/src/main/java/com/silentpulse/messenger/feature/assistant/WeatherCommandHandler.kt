package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.util.concurrent.Executors

/**
 * Handles voice weather queries by fetching real data from Open-Meteo
 * (open-source, no API key, no Google).
 *
 * Network is whitelisted in `network_security_config.xml` — only
 * `open-meteo.com` can be reached.  All other domains fail at TLS.
 *
 * Supports:
 *   - "weather in Seattle"
 *   - "weather tomorrow in Portland"
 *   - "weather for next 5 days in Denver"
 *   - "weather along I-90 corridor"
 *   - "weather along I-5"
 */
class WeatherCommandHandler(private val context: Context) {

    companion object {
        private const val TAG = "WeatherCmd"

        /** Keywords that indicate a weather query. */
        private val WEATHER_TRIGGERS = listOf("weather", "forecast", "temperature")

        /**
         * Major cities along US interstate corridors.
         */
        /**
         * ~5 key checkpoints per corridor — enough to characterise driving conditions
         * without a 20-minute TTS monologue.
         */
        private val CORRIDOR_CITIES = mapOf(
            "i-90" to listOf(
                "Seattle WA", "Spokane WA", "Missoula MT",
                "Rapid City SD", "Chicago IL"
            ),
            "i-5" to listOf(
                "San Diego CA", "Los Angeles CA", "Sacramento CA",
                "Portland OR", "Seattle WA"
            ),
            "i-10" to listOf(
                "Jacksonville FL", "New Orleans LA", "Houston TX",
                "El Paso TX", "Los Angeles CA"
            ),
            "i-95" to listOf(
                "Miami FL", "Savannah GA", "Washington DC",
                "New York NY", "Boston MA"
            ),
            "i-80" to listOf(
                "San Francisco CA", "Salt Lake City UT",
                "Omaha NE", "Chicago IL", "New York NY"
            ),
            "i-40" to listOf(
                "Wilmington NC", "Nashville TN",
                "Oklahoma City OK", "Albuquerque NM", "Barstow CA"
            ),
            "i-70" to listOf(
                "Baltimore MD", "Columbus OH",
                "St. Louis MO", "Kansas City MO", "Denver CO"
            ),
            "i-15" to listOf(
                "San Diego CA", "Las Vegas NV",
                "Salt Lake City UT", "Idaho Falls ID", "Great Falls MT"
            ),
            "i-75" to listOf(
                "Miami FL", "Atlanta GA",
                "Lexington KY", "Toledo OH", "Detroit MI"
            ),
            "i-35" to listOf(
                "San Antonio TX", "Dallas TX",
                "Oklahoma City OK", "Kansas City MO", "Minneapolis MN"
            )
        )
    }

    private val executor = Executors.newSingleThreadExecutor()

    /** @return true if the command looks like a weather query. */
    fun isWeatherCommand(command: String): Boolean {
        val c = command.lowercase()
        return WEATHER_TRIGGERS.any { c.contains(it) }
    }

    /**
     * Parse and fetch weather, then deliver the spoken result on the main thread.
     *
     * @param command    The raw voice command (already lowercased by caller).
     * @param onResult   Called on the main thread for a single-location result or
     *                   after all corridor cities have been enqueued.
     * @param onSegment  For corridor queries: called once per city as each result
     *                   arrives, so TTS can queue them individually (QUEUE_ADD).
     *                   If null, all corridor results are bundled into [onResult].
     */
    fun fetchAndSpeak(
        command: String,
        onResult: (String) -> Unit,
        onSegment: ((String) -> Unit)? = null
    ) {
        // ── Connectivity check ───────────────────────────────────────────
        if (!isNetworkAvailable()) {
            onResult("No data connection. Please turn on mobile data or Wi-Fi, then try again.")
            return
        }

        val c = command.lowercase().trim()
        val days = extractDays(c)

        // ── Corridor query? ──────────────────────────────────────────────
        val corridorMatch = Regex("(?:along|on)\\s+(i-?\\d+)").find(c)
        if (corridorMatch != null) {
            val highway = corridorMatch.groupValues[1]
                .replace(Regex("i(\\d)"), "i-$1")
            val cities = CORRIDOR_CITIES[highway]
            if (cities != null) {
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                executor.execute {
                    if (onSegment != null) {
                        // Stream each city to TTS as it's fetched
                        for (city in cities) {
                            val cityName = city.substringBefore(" ")
                            val result = OpenMeteoService.getWeather(city, days)
                            val text = when (result) {
                                is OpenMeteoService.WeatherResult.Success ->
                                    "$cityName: ${result.spokenSummary}"
                                is OpenMeteoService.WeatherResult.Error ->
                                    "$cityName: weather unavailable."
                            }
                            mainHandler.post { onSegment(text) }
                        }
                        mainHandler.post { onResult("That's the ${highway.uppercase()} corridor.") }
                    } else {
                        // Bundle all cities into one result
                        val result = OpenMeteoService.getCorridorWeather(cities, days)
                        val text = when (result) {
                            is OpenMeteoService.WeatherResult.Success ->
                                "Weather along ${highway.uppercase()}. ${result.spokenSummary}"
                            is OpenMeteoService.WeatherResult.Error -> result.message
                        }
                        mainHandler.post { onResult(text) }
                    }
                }
                return
            }
        }

        // ── Single location ──────────────────────────────────────────────
        val location = extractLocation(c)

        executor.execute {
            val result = if (location != null) {
                OpenMeteoService.getWeather(location, days)
            } else {
                // No city given — auto-detect via IP
                OpenMeteoService.getWeatherHere(days)
            }
            val text = when (result) {
                is OpenMeteoService.WeatherResult.Success ->
                    "Weather for ${result.location}. ${result.spokenSummary}"
                is OpenMeteoService.WeatherResult.Error -> result.message
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(text) }
        }
    }

    // ── Connectivity helpers ──────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private fun extractDays(command: String): Int {
        // "next 5 days" → 5
        val match = Regex("next\\s+(\\d+)\\s+day").find(command)
        if (match != null) return match.groupValues[1].toIntOrNull()?.coerceIn(1, 16) ?: 3

        return when {
            command.contains("five day") || command.contains("5 day") -> 5
            command.contains("seven day") || command.contains("7 day") -> 7
            command.contains("ten day") || command.contains("10 day") -> 10
            command.contains("this week") || command.contains("next week") -> 7
            command.contains("tomorrow") -> 2
            command.contains("this weekend") -> 5
            else -> 1  // just today
        }
    }

    private fun extractLocation(command: String): String? {
        // Words that should never be treated as a location
        val stopWords = setOf(
            "is", "the", "a", "an", "it", "like", "about", "what", "how",
            "today", "tomorrow", "tonight", "now", "currently", "right",
            "this", "that", "here", "there", "outside", "gonna", "going",
            "be", "will", "do", "does", "my", "our", "your", "in", "for",
            "at", "near", "around"
        )

        val patterns = listOf(
            // "weather [like/right now/etc] in Seattle" — allows up to 4 filler words before preposition
            Regex("\\b(?:weather|forecast|temperature)(?:\\s+\\w+){0,4}\\s+(?:in|for|at|near)\\s+(.+)"),
            // "in Seattle weather" — preposition before trigger
            Regex("\\b(?:in|for|at|near)\\s+(.+?)\\s+\\b(?:weather|forecast|temperature)")
        )

        for (pattern in patterns) {
            val match = pattern.find(command)
            if (match != null) {
                var loc = match.groupValues[1].trim()
                // Remove trailing timeframe / filler words
                loc = loc.replace(
                    Regex("\\s*(like|tomorrow|today|tonight|this week|next week|next \\d+ days?|hourly|for the next|five day|seven day|ten day).*$"),
                    ""
                ).trim()
                // Reject if empty or entirely stop-words
                if (loc.isNotEmpty() && loc.split("\\s+".toRegex()).any { it !in stopWords }) {
                    return loc
                }
            }
        }
        return null
    }
}
