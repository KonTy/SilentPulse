package com.silentpulse.messenger.feature.assistant

import timber.log.Timber
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Handles "what time is it" / "what's the time in Chicago" voice queries.
 *
 * Fully offline — uses Java's built-in IANA timezone database (java.time.ZoneId)
 * which ships on every Android device (API 26+).  No network calls.
 *
 * Supports:
 *   - "What time is it" / "what's the time" → local device time
 *   - "What time is it in Tokyo" / "time in New York" → remote city time
 *   - Fuzzy matching: "what's the time in chicago illinois" → America/Chicago
 */
class TimeHandler {

    companion object {
        private val TIME_PATTERN = Regex(
            "(?:what(?:'s| is) the time|what time is it|tell me the time|current time)" +
            "(?:\\s+in\\s+(.+))?",
            RegexOption.IGNORE_CASE
        )

        /** Additional trigger: just "time in <city>" */
        private val TIME_IN_PATTERN = Regex(
            "^time\\s+in\\s+(.+)",
            RegexOption.IGNORE_CASE
        )

        private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a", Locale.US)

        /**
         * Map of common city names / aliases to IANA zone IDs.
         * Covers major world cities that don't appear directly in the zone DB
         * or whose zone ID uses a different city name.
         */
        private val CITY_ALIASES = mapOf(
            "new york" to "America/New_York",
            "nyc" to "America/New_York",
            "los angeles" to "America/Los_Angeles",
            "la" to "America/Los_Angeles",
            "san francisco" to "America/Los_Angeles",
            "sf" to "America/Los_Angeles",
            "chicago" to "America/Chicago",
            "dallas" to "America/Chicago",
            "houston" to "America/Chicago",
            "austin" to "America/Chicago",
            "denver" to "America/Denver",
            "phoenix" to "America/Phoenix",
            "seattle" to "America/Los_Angeles",
            "portland" to "America/Los_Angeles",
            "miami" to "America/New_York",
            "atlanta" to "America/New_York",
            "boston" to "America/New_York",
            "philadelphia" to "America/New_York",
            "detroit" to "America/Detroit",
            "minneapolis" to "America/Chicago",
            "st louis" to "America/Chicago",
            "kansas city" to "America/Chicago",
            "salt lake city" to "America/Denver",
            "las vegas" to "America/Los_Angeles",
            "honolulu" to "Pacific/Honolulu",
            "hawaii" to "Pacific/Honolulu",
            "anchorage" to "America/Anchorage",
            "alaska" to "America/Anchorage",
            "london" to "Europe/London",
            "paris" to "Europe/Paris",
            "berlin" to "Europe/Berlin",
            "rome" to "Europe/Rome",
            "madrid" to "Europe/Madrid",
            "amsterdam" to "Europe/Amsterdam",
            "brussels" to "Europe/Brussels",
            "vienna" to "Europe/Vienna",
            "zurich" to "Europe/Zurich",
            "stockholm" to "Europe/Stockholm",
            "oslo" to "Europe/Oslo",
            "copenhagen" to "Arctic/Longyearbyen", // Denmark uses Europe/Copenhagen but not in ZoneId
            "helsinki" to "Europe/Helsinki",
            "warsaw" to "Europe/Warsaw",
            "prague" to "Europe/Prague",
            "budapest" to "Europe/Budapest",
            "bucharest" to "Europe/Bucharest",
            "athens" to "Europe/Athens",
            "istanbul" to "Europe/Istanbul",
            "moscow" to "Europe/Moscow",
            "kiev" to "Europe/Kiev",
            "kyiv" to "Europe/Kiev",
            "dubai" to "Asia/Dubai",
            "mumbai" to "Asia/Kolkata",
            "delhi" to "Asia/Kolkata",
            "new delhi" to "Asia/Kolkata",
            "india" to "Asia/Kolkata",
            "bangalore" to "Asia/Kolkata",
            "kolkata" to "Asia/Kolkata",
            "chennai" to "Asia/Kolkata",
            "karachi" to "Asia/Karachi",
            "islamabad" to "Asia/Karachi",
            "dhaka" to "Asia/Dhaka",
            "bangkok" to "Asia/Bangkok",
            "singapore" to "Asia/Singapore",
            "kuala lumpur" to "Asia/Kuala_Lumpur",
            "jakarta" to "Asia/Jakarta",
            "ho chi minh" to "Asia/Ho_Chi_Minh",
            "saigon" to "Asia/Ho_Chi_Minh",
            "manila" to "Asia/Manila",
            "hong kong" to "Asia/Hong_Kong",
            "taipei" to "Asia/Taipei",
            "tokyo" to "Asia/Tokyo",
            "japan" to "Asia/Tokyo",
            "osaka" to "Asia/Tokyo",
            "seoul" to "Asia/Seoul",
            "korea" to "Asia/Seoul",
            "beijing" to "Asia/Shanghai",
            "shanghai" to "Asia/Shanghai",
            "china" to "Asia/Shanghai",
            "sydney" to "Australia/Sydney",
            "melbourne" to "Australia/Melbourne",
            "brisbane" to "Australia/Brisbane",
            "perth" to "Australia/Perth",
            "auckland" to "Pacific/Auckland",
            "new zealand" to "Pacific/Auckland",
            "toronto" to "America/Toronto",
            "vancouver" to "America/Vancouver",
            "montreal" to "America/Montreal",
            "calgary" to "America/Edmonton",
            "edmonton" to "America/Edmonton",
            "mexico city" to "America/Mexico_City",
            "sao paulo" to "America/Sao_Paulo",
            "rio de janeiro" to "America/Sao_Paulo",
            "buenos aires" to "America/Argentina/Buenos_Aires",
            "bogota" to "America/Bogota",
            "lima" to "America/Lima",
            "santiago" to "America/Santiago",
            "cairo" to "Africa/Cairo",
            "johannesburg" to "Africa/Johannesburg",
            "lagos" to "Africa/Lagos",
            "nairobi" to "Africa/Nairobi",
            "casablanca" to "Africa/Casablanca",
            "tel aviv" to "Asia/Jerusalem",
            "jerusalem" to "Asia/Jerusalem",
            "israel" to "Asia/Jerusalem",
            "riyadh" to "Asia/Riyadh",
            "doha" to "Asia/Qatar",
            "tehran" to "Asia/Tehran",
            "kabul" to "Asia/Kabul",
        )

        /** All IANA zone IDs, lowercased city part → full zone ID. */
        private val ZONE_CITY_MAP: Map<String, String> by lazy {
            val map = mutableMapOf<String, String>()
            for (id in ZoneId.getAvailableZoneIds()) {
                val parts = id.split("/")
                if (parts.size >= 2) {
                    val city = parts.last().replace("_", " ").lowercase()
                    map[city] = id
                }
            }
            map
        }
    }

    /** Returns true if the command is a time query. */
    fun isTimeCommand(command: String): Boolean {
        val c = command.lowercase().trim()
        return TIME_PATTERN.containsMatchIn(c) ||
               TIME_IN_PATTERN.containsMatchIn(c) ||
               c.startsWith("what time") ||
               (c.contains("time") && c.contains(" in "))
    }

    /**
     * Processes a time command and returns a spoken response.
     * Fully synchronous, fully offline.
     */
    fun getTimeResponse(command: String): String {
        val c = command.lowercase().trim()

        // Extract city from command
        val city = extractCity(c)

        return if (city.isNullOrBlank()) {
            // Local time
            val now = ZonedDateTime.now()
            val timeStr = now.format(DATE_TIME_FORMAT)
            Timber.d("TimeHandler: local time → $timeStr")
            "It is $timeStr."
        } else {
            // Remote city time
            val zoneId = resolveZone(city)
            if (zoneId == null) {
                Timber.w("TimeHandler: could not resolve zone for \"$city\"")
                "I don't know the timezone for $city. Try a major city name."
            } else {
                val now = ZonedDateTime.now(zoneId)
                val timeStr = now.format(DATE_TIME_FORMAT)
                val zoneName = zoneId.id.split("/").last().replace("_", " ")
                Timber.d("TimeHandler: time in $city ($zoneId) → $timeStr")
                "In $zoneName, it is $timeStr."
            }
        }
    }

    /** Extracts the city name from a time command, or null for local time. */
    private fun extractCity(c: String): String? {
        // Try "what time is it in <city>"
        TIME_PATTERN.find(c)?.groups?.get(1)?.value?.let {
            return cleanCity(it)
        }
        // Try "time in <city>"
        TIME_IN_PATTERN.find(c)?.groups?.get(1)?.value?.let {
            return cleanCity(it)
        }
        return null
    }

    /** Strip trailing state abbreviations, punctuation, filler words. */
    private fun cleanCity(raw: String): String {
        return raw.trim()
            .replace(Regex("[,.?!]+$"), "")         // trailing punctuation
            .replace(Regex("\\b(right now|now|currently|please)\\b"), "") // filler
            .replace(Regex("\\s+"), " ")            // collapse whitespace
            .trim()
    }

    /** Resolves a city name to a ZoneId, trying aliases → zone map → fuzzy. */
    private fun resolveZone(city: String): ZoneId? {
        val key = city.lowercase().trim()

        // 1. Direct alias match
        CITY_ALIASES[key]?.let {
            Timber.d("TimeHandler: alias match \"$key\" → $it")
            return ZoneId.of(it)
        }

        // 2. Also try without state suffix: "chicago il" → "chicago"
        val withoutState = key.replace(Regex("\\s+[a-z]{2}$"), "").trim()
        if (withoutState != key) {
            CITY_ALIASES[withoutState]?.let {
                Timber.d("TimeHandler: alias match (no state) \"$withoutState\" → $it")
                return ZoneId.of(it)
            }
        }

        // 3. Direct zone city map match
        ZONE_CITY_MAP[key]?.let {
            Timber.d("TimeHandler: zone map match \"$key\" → $it")
            return ZoneId.of(it)
        }
        ZONE_CITY_MAP[withoutState]?.let {
            Timber.d("TimeHandler: zone map match (no state) \"$withoutState\" → $it")
            return ZoneId.of(it)
        }

        // 4. Fuzzy: find any zone city that contains the query
        val fuzzy = ZONE_CITY_MAP.entries.firstOrNull { (zoneCity, _) ->
            zoneCity.contains(key) || key.contains(zoneCity)
        }
        if (fuzzy != null) {
            Timber.d("TimeHandler: fuzzy match \"$key\" → ${fuzzy.value}")
            return ZoneId.of(fuzzy.value)
        }

        // 5. Try each word individually (e.g. "new york city" → "new york")
        val words = key.split(" ")
        if (words.size > 1) {
            // Try first two words
            val twoWord = "${words[0]} ${words[1]}"
            CITY_ALIASES[twoWord]?.let { return ZoneId.of(it) }
            ZONE_CITY_MAP[twoWord]?.let { return ZoneId.of(it) }
            // Try first word
            CITY_ALIASES[words[0]]?.let { return ZoneId.of(it) }
            ZONE_CITY_MAP[words[0]]?.let { return ZoneId.of(it) }
        }

        return null
    }
}
