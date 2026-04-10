package com.silentpulse.messenger.feature.assistant

import timber.log.Timber
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Handles "what time is it" / "what's the time in Chicago" voice queries.
 *
 * Fully offline — uses Java's built-in IANA timezone database (java.time.ZoneId)
 * which ships on every Android device (API 26+).  No network calls.
 *
 * Resolution chain (stops at first match):
 *   1. Direct city alias (150+ major world cities)
 *   2. IANA zone-city exact match (~600 cities)
 *   3. US state name/abbreviation → timezone
 *   4. Country name → timezone (150+ countries)
 *   5. Levenshtein fuzzy match on city aliases + zone map (tolerates typos)
 *   6. Substring containment match
 *
 * Supports:
 *   - "What time is it" → local device time
 *   - "What time is it in Tokyo" → Asia/Tokyo
 *   - "Time in Lafayette Indiana" → Indiana → America/Indiana/Indianapolis
 *   - "Time in some town in Argentina" → Argentina → America/Argentina/Buenos_Aires
 *   - "Time in Tokoyo" (misspelled) → fuzzy → Asia/Tokyo
 */
class TimeHandler {

    companion object {
        private val TIME_PATTERN = Regex(
            "(?:what(?:'s| is) the time|what time is it|tell me the time|current time)" +
            "(?:\\s+in\\s+(.+))?",
            RegexOption.IGNORE_CASE
        )

        private val TIME_IN_PATTERN = Regex(
            "^time\\s+in\\s+(.+)",
            RegexOption.IGNORE_CASE
        )

        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern(
            "EEEE, MMMM d, h:mm a", Locale.US
        )

        /** Max Levenshtein distance for fuzzy city match. */
        private const val MAX_FUZZY_DIST = 2

        // ── City aliases (major world cities) ──────────────────────────────

        private val CITY_ALIASES = mapOf(
            // --- North America - US ---
            "new york" to "America/New_York",
            "nyc" to "America/New_York",
            "los angeles" to "America/Los_Angeles",
            "la" to "America/Los_Angeles",
            "san francisco" to "America/Los_Angeles",
            "sf" to "America/Los_Angeles",
            "san diego" to "America/Los_Angeles",
            "chicago" to "America/Chicago",
            "dallas" to "America/Chicago",
            "houston" to "America/Chicago",
            "san antonio" to "America/Chicago",
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
            "saint louis" to "America/Chicago",
            "kansas city" to "America/Chicago",
            "salt lake city" to "America/Denver",
            "las vegas" to "America/Los_Angeles",
            "honolulu" to "Pacific/Honolulu",
            "hawaii" to "Pacific/Honolulu",
            "anchorage" to "America/Anchorage",
            "alaska" to "America/Anchorage",
            "nashville" to "America/Chicago",
            "memphis" to "America/Chicago",
            "louisville" to "America/Kentucky/Louisville",
            "indianapolis" to "America/Indiana/Indianapolis",
            "columbus" to "America/New_York",
            "charlotte" to "America/New_York",
            "pittsburgh" to "America/New_York",
            "baltimore" to "America/New_York",
            "washington" to "America/New_York",
            "dc" to "America/New_York",
            "washington dc" to "America/New_York",
            "orlando" to "America/New_York",
            "tampa" to "America/New_York",
            "jacksonville" to "America/New_York",
            "milwaukee" to "America/Chicago",
            "oklahoma city" to "America/Chicago",
            "albuquerque" to "America/Denver",
            "tucson" to "America/Phoenix",
            "raleigh" to "America/New_York",
            "richmond" to "America/New_York",
            "new orleans" to "America/Chicago",
            "lafayette" to "America/Chicago",
            "baton rouge" to "America/Chicago",
            "birmingham" to "America/Chicago",
            "omaha" to "America/Chicago",
            "des moines" to "America/Chicago",
            "little rock" to "America/Chicago",
            "jackson" to "America/Chicago",
            "wichita" to "America/Chicago",
            "tulsa" to "America/Chicago",
            "el paso" to "America/Denver",
            "boise" to "America/Boise",
            "reno" to "America/Los_Angeles",
            "sacramento" to "America/Los_Angeles",
            "fresno" to "America/Los_Angeles",
            "oakland" to "America/Los_Angeles",
            "san jose" to "America/Los_Angeles",
            "spokane" to "America/Los_Angeles",
            // --- North America - Canada ---
            "toronto" to "America/Toronto",
            "vancouver" to "America/Vancouver",
            "montreal" to "America/Montreal",
            "ottawa" to "America/Toronto",
            "calgary" to "America/Edmonton",
            "edmonton" to "America/Edmonton",
            "winnipeg" to "America/Winnipeg",
            "halifax" to "America/Halifax",
            "st johns" to "America/St_Johns",
            "saint johns" to "America/St_Johns",
            // --- North America - Mexico ---
            "mexico city" to "America/Mexico_City",
            "guadalajara" to "America/Mexico_City",
            "monterrey" to "America/Monterrey",
            "cancun" to "America/Cancun",
            "tijuana" to "America/Tijuana",
            // --- South America ---
            "sao paulo" to "America/Sao_Paulo",
            "rio de janeiro" to "America/Sao_Paulo",
            "rio" to "America/Sao_Paulo",
            "buenos aires" to "America/Argentina/Buenos_Aires",
            "bogota" to "America/Bogota",
            "lima" to "America/Lima",
            "santiago" to "America/Santiago",
            "caracas" to "America/Caracas",
            "quito" to "America/Guayaquil",
            "montevideo" to "America/Montevideo",
            "asuncion" to "America/Asuncion",
            "la paz" to "America/La_Paz",
            "medellin" to "America/Bogota",
            // --- Europe ---
            "london" to "Europe/London",
            "paris" to "Europe/Paris",
            "berlin" to "Europe/Berlin",
            "munich" to "Europe/Berlin",
            "frankfurt" to "Europe/Berlin",
            "hamburg" to "Europe/Berlin",
            "rome" to "Europe/Rome",
            "milan" to "Europe/Rome",
            "madrid" to "Europe/Madrid",
            "barcelona" to "Europe/Madrid",
            "amsterdam" to "Europe/Amsterdam",
            "brussels" to "Europe/Brussels",
            "vienna" to "Europe/Vienna",
            "zurich" to "Europe/Zurich",
            "geneva" to "Europe/Zurich",
            "stockholm" to "Europe/Stockholm",
            "oslo" to "Europe/Oslo",
            "copenhagen" to "Europe/Copenhagen",
            "helsinki" to "Europe/Helsinki",
            "warsaw" to "Europe/Warsaw",
            "krakow" to "Europe/Warsaw",
            "prague" to "Europe/Prague",
            "budapest" to "Europe/Budapest",
            "bucharest" to "Europe/Bucharest",
            "athens" to "Europe/Athens",
            "istanbul" to "Europe/Istanbul",
            "moscow" to "Europe/Moscow",
            "saint petersburg" to "Europe/Moscow",
            "st petersburg" to "Europe/Moscow",
            "kiev" to "Europe/Kiev",
            "kyiv" to "Europe/Kiev",
            "dublin" to "Europe/Dublin",
            "edinburgh" to "Europe/London",
            "lisbon" to "Europe/Lisbon",
            "belgrade" to "Europe/Belgrade",
            "zagreb" to "Europe/Zagreb",
            "sofia" to "Europe/Sofia",
            "riga" to "Europe/Riga",
            "tallinn" to "Europe/Tallinn",
            "vilnius" to "Europe/Vilnius",
            "minsk" to "Europe/Minsk",
            // --- Middle East ---
            "dubai" to "Asia/Dubai",
            "abu dhabi" to "Asia/Dubai",
            "tel aviv" to "Asia/Jerusalem",
            "jerusalem" to "Asia/Jerusalem",
            "israel" to "Asia/Jerusalem",
            "riyadh" to "Asia/Riyadh",
            "jeddah" to "Asia/Riyadh",
            "doha" to "Asia/Qatar",
            "tehran" to "Asia/Tehran",
            "baghdad" to "Asia/Baghdad",
            "beirut" to "Asia/Beirut",
            "amman" to "Asia/Amman",
            "kuwait city" to "Asia/Kuwait",
            "muscat" to "Asia/Muscat",
            // --- South / SE Asia ---
            "mumbai" to "Asia/Kolkata",
            "delhi" to "Asia/Kolkata",
            "new delhi" to "Asia/Kolkata",
            "india" to "Asia/Kolkata",
            "bangalore" to "Asia/Kolkata",
            "kolkata" to "Asia/Kolkata",
            "chennai" to "Asia/Kolkata",
            "hyderabad" to "Asia/Kolkata",
            "karachi" to "Asia/Karachi",
            "islamabad" to "Asia/Karachi",
            "lahore" to "Asia/Karachi",
            "dhaka" to "Asia/Dhaka",
            "bangkok" to "Asia/Bangkok",
            "singapore" to "Asia/Singapore",
            "kuala lumpur" to "Asia/Kuala_Lumpur",
            "jakarta" to "Asia/Jakarta",
            "ho chi minh" to "Asia/Ho_Chi_Minh",
            "saigon" to "Asia/Ho_Chi_Minh",
            "hanoi" to "Asia/Ho_Chi_Minh",
            "manila" to "Asia/Manila",
            "colombo" to "Asia/Colombo",
            "yangon" to "Asia/Yangon",
            "phnom penh" to "Asia/Phnom_Penh",
            "kathmandu" to "Asia/Kathmandu",
            // --- East Asia ---
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
            "shenzhen" to "Asia/Shanghai",
            "guangzhou" to "Asia/Shanghai",
            "ulaanbaatar" to "Asia/Ulaanbaatar",
            // --- Oceania ---
            "sydney" to "Australia/Sydney",
            "melbourne" to "Australia/Melbourne",
            "brisbane" to "Australia/Brisbane",
            "perth" to "Australia/Perth",
            "adelaide" to "Australia/Adelaide",
            "auckland" to "Pacific/Auckland",
            "new zealand" to "Pacific/Auckland",
            "wellington" to "Pacific/Auckland",
            "fiji" to "Pacific/Fiji",
            // --- Africa ---
            "cairo" to "Africa/Cairo",
            "johannesburg" to "Africa/Johannesburg",
            "cape town" to "Africa/Johannesburg",
            "lagos" to "Africa/Lagos",
            "nairobi" to "Africa/Nairobi",
            "casablanca" to "Africa/Casablanca",
            "addis ababa" to "Africa/Addis_Ababa",
            "dar es salaam" to "Africa/Dar_es_Salaam",
            "accra" to "Africa/Accra",
            "dakar" to "Africa/Dakar",
            // --- Central Asia ---
            "kabul" to "Asia/Kabul",
            "tashkent" to "Asia/Tashkent",
            "almaty" to "Asia/Almaty",
            "baku" to "Asia/Baku",
            "yerevan" to "Asia/Yerevan",
            "tbilisi" to "Asia/Tbilisi",
        )

        // ── US states → timezone ───────────────────────────────────

        private val US_STATE_ZONES = mapOf(
            // Eastern
            "connecticut" to "America/New_York",
            "delaware" to "America/New_York",
            "maine" to "America/New_York",
            "maryland" to "America/New_York",
            "massachusetts" to "America/New_York",
            "new hampshire" to "America/New_York",
            "new jersey" to "America/New_York",
            "new york" to "America/New_York",
            "north carolina" to "America/New_York",
            "ohio" to "America/New_York",
            "pennsylvania" to "America/New_York",
            "rhode island" to "America/New_York",
            "south carolina" to "America/New_York",
            "vermont" to "America/New_York",
            "virginia" to "America/New_York",
            "west virginia" to "America/New_York",
            "district of columbia" to "America/New_York",
            // Eastern (split states — majority Eastern)
            "florida" to "America/New_York",
            "georgia" to "America/New_York",
            "indiana" to "America/Indiana/Indianapolis",
            "michigan" to "America/Detroit",
            "kentucky" to "America/Kentucky/Louisville",
            // Central
            "tennessee" to "America/Chicago",
            "alabama" to "America/Chicago",
            "arkansas" to "America/Chicago",
            "illinois" to "America/Chicago",
            "iowa" to "America/Chicago",
            "kansas" to "America/Chicago",
            "louisiana" to "America/Chicago",
            "minnesota" to "America/Chicago",
            "mississippi" to "America/Chicago",
            "missouri" to "America/Chicago",
            "nebraska" to "America/Chicago",
            "north dakota" to "America/Chicago",
            "oklahoma" to "America/Chicago",
            "south dakota" to "America/Chicago",
            "texas" to "America/Chicago",
            "wisconsin" to "America/Chicago",
            // Mountain
            "arizona" to "America/Phoenix",
            "colorado" to "America/Denver",
            "idaho" to "America/Boise",
            "montana" to "America/Denver",
            "new mexico" to "America/Denver",
            "utah" to "America/Denver",
            "wyoming" to "America/Denver",
            // Pacific
            "california" to "America/Los_Angeles",
            "nevada" to "America/Los_Angeles",
            "oregon" to "America/Los_Angeles",
            "washington state" to "America/Los_Angeles",
            // Non-contiguous
            "alaska" to "America/Anchorage",
            "hawaii" to "Pacific/Honolulu",
        )

        /** 2-letter abbreviation → zone. */
        private val US_STATE_ABBREV = mapOf(
            "ct" to "America/New_York", "de" to "America/New_York",
            "me" to "America/New_York", "md" to "America/New_York",
            "ma" to "America/New_York", "nh" to "America/New_York",
            "nj" to "America/New_York", "ny" to "America/New_York",
            "nc" to "America/New_York", "oh" to "America/New_York",
            "pa" to "America/New_York", "ri" to "America/New_York",
            "sc" to "America/New_York", "vt" to "America/New_York",
            "va" to "America/New_York", "wv" to "America/New_York",
            "dc" to "America/New_York",
            "fl" to "America/New_York", "ga" to "America/New_York",
            "in" to "America/Indiana/Indianapolis",
            "mi" to "America/Detroit",
            "ky" to "America/Kentucky/Louisville",
            "tn" to "America/Chicago", "al" to "America/Chicago",
            "ar" to "America/Chicago", "il" to "America/Chicago",
            "ia" to "America/Chicago", "ks" to "America/Chicago",
            "la" to "America/Chicago", "mn" to "America/Chicago",
            "ms" to "America/Chicago", "mo" to "America/Chicago",
            "ne" to "America/Chicago", "nd" to "America/Chicago",
            "ok" to "America/Chicago", "sd" to "America/Chicago",
            "tx" to "America/Chicago", "wi" to "America/Chicago",
            "az" to "America/Phoenix", "co" to "America/Denver",
            "id" to "America/Boise", "mt" to "America/Denver",
            "nm" to "America/Denver", "ut" to "America/Denver",
            "wy" to "America/Denver",
            "ca" to "America/Los_Angeles", "nv" to "America/Los_Angeles",
            "or" to "America/Los_Angeles", "wa" to "America/Los_Angeles",
            "ak" to "America/Anchorage", "hi" to "Pacific/Honolulu",
        )

        /** Abbreviation → full name (for spoken output). */
        private val ABBREV_TO_STATE = mapOf(
            "al" to "Alabama", "ak" to "Alaska", "az" to "Arizona",
            "ar" to "Arkansas", "ca" to "California", "co" to "Colorado",
            "ct" to "Connecticut", "de" to "Delaware",
            "dc" to "District of Columbia", "fl" to "Florida",
            "ga" to "Georgia", "hi" to "Hawaii", "id" to "Idaho",
            "il" to "Illinois", "in" to "Indiana", "ia" to "Iowa",
            "ks" to "Kansas", "ky" to "Kentucky", "la" to "Louisiana",
            "me" to "Maine", "md" to "Maryland", "ma" to "Massachusetts",
            "mi" to "Michigan", "mn" to "Minnesota", "ms" to "Mississippi",
            "mo" to "Missouri", "mt" to "Montana", "ne" to "Nebraska",
            "nv" to "Nevada", "nh" to "New Hampshire", "nj" to "New Jersey",
            "nm" to "New Mexico", "ny" to "New York", "nc" to "North Carolina",
            "nd" to "North Dakota", "oh" to "Ohio", "ok" to "Oklahoma",
            "or" to "Oregon", "pa" to "Pennsylvania", "ri" to "Rhode Island",
            "sc" to "South Carolina", "sd" to "South Dakota",
            "tn" to "Tennessee", "tx" to "Texas", "ut" to "Utah",
            "vt" to "Vermont", "va" to "Virginia", "wa" to "Washington",
            "wv" to "West Virginia", "wi" to "Wisconsin", "wy" to "Wyoming",
        )

        // ── Country → timezone ─────────────────────────────────────

        private val COUNTRY_ZONES = mapOf(
            "afghanistan" to "Asia/Kabul",
            "albania" to "Europe/Tirane",
            "algeria" to "Africa/Algiers",
            "argentina" to "America/Argentina/Buenos_Aires",
            "armenia" to "Asia/Yerevan",
            "australia" to "Australia/Sydney",
            "austria" to "Europe/Vienna",
            "azerbaijan" to "Asia/Baku",
            "bahamas" to "America/Nassau",
            "bahrain" to "Asia/Bahrain",
            "bangladesh" to "Asia/Dhaka",
            "barbados" to "America/Barbados",
            "belarus" to "Europe/Minsk",
            "belgium" to "Europe/Brussels",
            "belize" to "America/Belize",
            "bolivia" to "America/La_Paz",
            "bosnia" to "Europe/Sarajevo",
            "botswana" to "Africa/Gaborone",
            "brazil" to "America/Sao_Paulo",
            "brunei" to "Asia/Brunei",
            "bulgaria" to "Europe/Sofia",
            "cambodia" to "Asia/Phnom_Penh",
            "cameroon" to "Africa/Douala",
            "canada" to "America/Toronto",
            "chile" to "America/Santiago",
            "china" to "Asia/Shanghai",
            "colombia" to "America/Bogota",
            "costa rica" to "America/Costa_Rica",
            "croatia" to "Europe/Zagreb",
            "cuba" to "America/Havana",
            "cyprus" to "Asia/Nicosia",
            "czech republic" to "Europe/Prague",
            "czechia" to "Europe/Prague",
            "denmark" to "Europe/Copenhagen",
            "dominican republic" to "America/Santo_Domingo",
            "ecuador" to "America/Guayaquil",
            "egypt" to "Africa/Cairo",
            "el salvador" to "America/El_Salvador",
            "estonia" to "Europe/Tallinn",
            "ethiopia" to "Africa/Addis_Ababa",
            "fiji" to "Pacific/Fiji",
            "finland" to "Europe/Helsinki",
            "france" to "Europe/Paris",
            "germany" to "Europe/Berlin",
            "ghana" to "Africa/Accra",
            "greece" to "Europe/Athens",
            "guatemala" to "America/Guatemala",
            "haiti" to "America/Port-au-Prince",
            "honduras" to "America/Tegucigalpa",
            "hungary" to "Europe/Budapest",
            "iceland" to "Atlantic/Reykjavik",
            "india" to "Asia/Kolkata",
            "indonesia" to "Asia/Jakarta",
            "iran" to "Asia/Tehran",
            "iraq" to "Asia/Baghdad",
            "ireland" to "Europe/Dublin",
            "israel" to "Asia/Jerusalem",
            "italy" to "Europe/Rome",
            "ivory coast" to "Africa/Abidjan",
            "jamaica" to "America/Jamaica",
            "japan" to "Asia/Tokyo",
            "jordan" to "Asia/Amman",
            "kazakhstan" to "Asia/Almaty",
            "kenya" to "Africa/Nairobi",
            "korea" to "Asia/Seoul",
            "south korea" to "Asia/Seoul",
            "north korea" to "Asia/Pyongyang",
            "kuwait" to "Asia/Kuwait",
            "laos" to "Asia/Vientiane",
            "latvia" to "Europe/Riga",
            "lebanon" to "Asia/Beirut",
            "libya" to "Africa/Tripoli",
            "lithuania" to "Europe/Vilnius",
            "luxembourg" to "Europe/Luxembourg",
            "malaysia" to "Asia/Kuala_Lumpur",
            "maldives" to "Indian/Maldives",
            "mexico" to "America/Mexico_City",
            "moldova" to "Europe/Chisinau",
            "mongolia" to "Asia/Ulaanbaatar",
            "montenegro" to "Europe/Podgorica",
            "morocco" to "Africa/Casablanca",
            "mozambique" to "Africa/Maputo",
            "myanmar" to "Asia/Yangon",
            "namibia" to "Africa/Windhoek",
            "nepal" to "Asia/Kathmandu",
            "netherlands" to "Europe/Amsterdam",
            "new zealand" to "Pacific/Auckland",
            "nicaragua" to "America/Managua",
            "nigeria" to "Africa/Lagos",
            "norway" to "Europe/Oslo",
            "oman" to "Asia/Muscat",
            "pakistan" to "Asia/Karachi",
            "panama" to "America/Panama",
            "papua new guinea" to "Pacific/Port_Moresby",
            "paraguay" to "America/Asuncion",
            "peru" to "America/Lima",
            "philippines" to "Asia/Manila",
            "poland" to "Europe/Warsaw",
            "portugal" to "Europe/Lisbon",
            "qatar" to "Asia/Qatar",
            "romania" to "Europe/Bucharest",
            "russia" to "Europe/Moscow",
            "saudi arabia" to "Asia/Riyadh",
            "senegal" to "Africa/Dakar",
            "serbia" to "Europe/Belgrade",
            "singapore" to "Asia/Singapore",
            "slovakia" to "Europe/Bratislava",
            "slovenia" to "Europe/Ljubljana",
            "somalia" to "Africa/Mogadishu",
            "south africa" to "Africa/Johannesburg",
            "spain" to "Europe/Madrid",
            "sri lanka" to "Asia/Colombo",
            "sudan" to "Africa/Khartoum",
            "sweden" to "Europe/Stockholm",
            "switzerland" to "Europe/Zurich",
            "syria" to "Asia/Damascus",
            "taiwan" to "Asia/Taipei",
            "tanzania" to "Africa/Dar_es_Salaam",
            "thailand" to "Asia/Bangkok",
            "trinidad" to "America/Port_of_Spain",
            "tunisia" to "Africa/Tunis",
            "turkey" to "Europe/Istanbul",
            "turkiye" to "Europe/Istanbul",
            "uganda" to "Africa/Kampala",
            "ukraine" to "Europe/Kiev",
            "united arab emirates" to "Asia/Dubai",
            "uae" to "Asia/Dubai",
            "united kingdom" to "Europe/London",
            "uk" to "Europe/London",
            "england" to "Europe/London",
            "scotland" to "Europe/London",
            "wales" to "Europe/London",
            "united states" to "America/New_York",
            "usa" to "America/New_York",
            "uruguay" to "America/Montevideo",
            "uzbekistan" to "Asia/Tashkent",
            "venezuela" to "America/Caracas",
            "vietnam" to "Asia/Ho_Chi_Minh",
            "yemen" to "Asia/Aden",
            "zambia" to "Africa/Lusaka",
            "zimbabwe" to "Africa/Harare",
        )

        // ── IANA zone city map (built from system DB) ──────────────────

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

        /** Combined lookup keys for Levenshtein fuzzy matching. */
        private val ALL_CITY_KEYS: Map<String, String> by lazy {
            val map = mutableMapOf<String, String>()
            map.putAll(ZONE_CITY_MAP)
            map.putAll(CITY_ALIASES) // aliases win on collision
            map
        }
    }

    // ── Public API ──────────────────────────────────────────

    fun isTimeCommand(command: String): Boolean {
        val c = command.lowercase().trim()
        return TIME_PATTERN.containsMatchIn(c) ||
               TIME_IN_PATTERN.containsMatchIn(c) ||
               c.startsWith("what time") ||
               (c.contains("time") && c.contains(" in "))
    }

    fun getTimeResponse(command: String): String {
        val c = command.lowercase().trim()
        val city = extractCity(c)

        return if (city.isNullOrBlank()) {
            val now = ZonedDateTime.now()
            val timeStr = now.format(DATE_TIME_FORMAT)
            Timber.d("TimeHandler: local time -> %s", timeStr)
            "It is $timeStr."
        } else {
            val result = resolveZone(city)
            if (result == null) {
                Timber.w("TimeHandler: no zone for \"%s\"", city)
                "I don't know the timezone for $city. " +
                    "Try including a state or country name."
            } else {
                val (zoneId, label) = result
                val now = ZonedDateTime.now(zoneId)
                val timeStr = now.format(DATE_TIME_FORMAT)
                Timber.d("TimeHandler: %s (%s via %s) -> %s", city, zoneId, label, timeStr)
                "In $label, it is $timeStr."
            }
        }
    }

    // ── Extraction ──────────────────────────────────────────

    private fun extractCity(c: String): String? {
        TIME_PATTERN.find(c)?.groups?.get(1)?.value?.let { return cleanCity(it) }
        TIME_IN_PATTERN.find(c)?.groups?.get(1)?.value?.let { return cleanCity(it) }
        return null
    }

    private fun cleanCity(raw: String): String {
        return raw.trim()
            .replace(Regex("[,.?!]+$"), "")
            .replace(Regex("\\b(right now|now|currently|please)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ── Resolution chain ────────────────────────────────────────

    /**
     * Resolves a spoken location to a ZoneId.
     * Returns (ZoneId, humanLabel) or null.
     *
     * Chain:
     *   1. City alias exact
     *   2. Strip trailing 2-letter state abbrev → city alias + state zone
     *   3. IANA zone-city exact
     *   4. Scan words for US state names / abbreviations
     *   5. Scan words for country names
     *   6. Levenshtein fuzzy (distance ≤ 2)
     *   7. Substring containment
     */
    private fun resolveZone(city: String): Pair<ZoneId, String>? {
        val key = city.lowercase().trim()
        val words = key.split(" ").filter { it.isNotEmpty() }

        // 1. Direct city alias
        CITY_ALIASES[key]?.let {
            return ZoneId.of(it) to friendly(it)
        }

        // 2. Trailing 2-letter state abbrev: "lafayette in" → "lafayette" + state "in"
        if (words.size >= 2) {
            val last = words.last()
            val rest = words.dropLast(1).joinToString(" ")
            if (last.length == 2) {
                US_STATE_ABBREV[last]?.let { stateZone ->
                    val stateName = ABBREV_TO_STATE[last] ?: last.uppercase()
                    Timber.d("TimeHandler: state abbrev '%s' -> %s (city: %s)", last, stateZone, rest)
                    return ZoneId.of(stateZone) to "$rest, $stateName"
                }
            }
            // Also try rest as city alias (e.g. "new york state" → rest = "new york")
            CITY_ALIASES[rest]?.let {
                return ZoneId.of(it) to friendly(it)
            }
        }

        // 3. IANA zone-city exact
        ZONE_CITY_MAP[key]?.let {
            return ZoneId.of(it) to friendly(it)
        }

        // 4. Scan words for US state names
        scanForState(words)?.let { return it }

        // 5. Scan words for country names
        scanForCountry(words)?.let { return it }

        // 6. Levenshtein fuzzy match
        fuzzyMatch(key, words)?.let { return it }

        // 7. Substring containment (last resort)
        for ((candidate, zone) in ALL_CITY_KEYS) {
            if (candidate.contains(key) || key.contains(candidate)) {
                Timber.d("TimeHandler: substring '%s' <-> '%s' -> %s", key, candidate, zone)
                return ZoneId.of(zone) to friendly(zone)
            }
        }

        return null
    }

    // ── State / country scanning ────────────────────────────────────

    /** Scan words (and adjacent pairs) for US state names. */
    private fun scanForState(words: List<String>): Pair<ZoneId, String>? {
        // Two-word states first (more specific)
        for (i in 0 until words.size - 1) {
            val pair = "${words[i]} ${words[i + 1]}"
            US_STATE_ZONES[pair]?.let { zone ->
                return ZoneId.of(zone) to titleCase(pair)
            }
        }
        // Single-word states
        for (w in words) {
            US_STATE_ZONES[w]?.let { zone ->
                return ZoneId.of(zone) to titleCase(w)
            }
            // 2-letter abbreviation
            if (w.length == 2) {
                US_STATE_ABBREV[w]?.let { zone ->
                    val name = ABBREV_TO_STATE[w] ?: w.uppercase()
                    return ZoneId.of(zone) to name
                }
            }
        }
        return null
    }

    /** Scan words (and adjacent pairs/triples) for country names. */
    private fun scanForCountry(words: List<String>): Pair<ZoneId, String>? {
        // Three-word countries first
        for (i in 0 until words.size - 2) {
            val tri = "${words[i]} ${words[i + 1]} ${words[i + 2]}"
            COUNTRY_ZONES[tri]?.let { zone ->
                return ZoneId.of(zone) to titleCase(tri)
            }
        }
        // Two-word countries
        for (i in 0 until words.size - 1) {
            val pair = "${words[i]} ${words[i + 1]}"
            COUNTRY_ZONES[pair]?.let { zone ->
                return ZoneId.of(zone) to titleCase(pair)
            }
        }
        // Single-word countries
        for (w in words) {
            COUNTRY_ZONES[w]?.let { zone ->
                return ZoneId.of(zone) to titleCase(w)
            }
        }
        return null
    }

    // ── Levenshtein fuzzy matching ──────────────────────────────────

    private fun fuzzyMatch(key: String, words: List<String>): Pair<ZoneId, String>? {
        var bestMatch: String? = null
        var bestZone: String? = null
        var bestDist = MAX_FUZZY_DIST + 1

        for ((candidate, zone) in ALL_CITY_KEYS) {
            // Full input vs candidate
            val d = levenshtein(key, candidate)
            if (d < bestDist) {
                bestDist = d; bestMatch = candidate; bestZone = zone
            }
            // First word vs candidate (for "tokoyo japan" → "tokyo")
            if (words.size > 1) {
                val d2 = levenshtein(words[0], candidate)
                if (d2 < bestDist) {
                    bestDist = d2; bestMatch = candidate; bestZone = zone
                }
            }
        }

        if (bestZone != null && bestMatch != null) {
            Timber.d(
                "TimeHandler: fuzzy '%s' -> '%s' dist=%d -> %s",
                key, bestMatch, bestDist, bestZone
            )
            return ZoneId.of(bestZone) to friendly(bestZone)
        }
        return null
    }

    /**
     * Levenshtein edit distance.  O(n*m) but inputs are short city names
     * (< 20 chars typically), so this is sub-microsecond.
     */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        // Quick reject if lengths differ too much
        if (abs(a.length - b.length) > MAX_FUZZY_DIST) return MAX_FUZZY_DIST + 1

        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    // ── Helpers ────────────────────────────────────────────

    /** "America/New_York" → "New York" */
    private fun friendly(zoneId: String): String {
        return zoneId.split("/").last().replace("_", " ")
    }

    /** "north carolina" → "North Carolina" */
    private fun titleCase(s: String): String {
        return s.split(" ").joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercase() }
        }
    }
}
