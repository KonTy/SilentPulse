package com.silentpulse.messenger.common.util

import java.text.Normalizer
import java.util.Locale
import java.util.TimeZone

data class CityTimeZone(val city: String, val zoneId: String)

/** Offline city catalog backed by the device's time-zone database, including on API 21. */
object CityTimeZones {

    // Keep the voice aliases and their order unchanged: order breaks fuzzy-match ties.
    val aliases: Map<String, String> = mapOf(
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

    private val geographicRegions = setOf(
        "Africa", "America", "Antarctica", "Arctic", "Asia",
        "Atlantic", "Australia", "Europe", "Indian", "Pacific"
    )
    private val acronyms = setOf("nyc", "la", "sf", "dc")
    private val searchSeparators = Regex("[\\p{M}\\p{Z}\\s_]+")
    private val availableZoneIds by lazy { TimeZone.getAvailableIDs().toSet() }

    val cities: List<CityTimeZone> by lazy {
        val friendlyCities = aliases.asSequence().map { (alias, zoneId) ->
            val zoneCity = zoneId.substringAfterLast('/').replace('_', ' ')
            val city = if (normalize(alias) == normalize(zoneCity)) zoneCity else {
                alias.split(' ').joinToString(" ") {
                    if (it in acronyms) it.uppercase(Locale.ROOT)
                    else it.replaceFirstChar { char -> char.titlecase(Locale.ROOT) }
                }
            }
            CityTimeZone(city, zoneId)
        }
        val ianaCities = availableZoneIds.asSequence()
            .filter { it.substringBefore('/') in geographicRegions }
            .map { CityTimeZone(it.substringAfterLast('/').replace('_', ' '), it) }

        (friendlyCities + ianaCities + sequenceOf(CityTimeZone("UTC", "UTC")))
            .filter { isValidZoneId(it.zoneId) }
            .distinct()
            .sortedWith(compareBy({ it.city }, { it.zoneId }))
            .toList()
    }

    private val searchIndex by lazy {
        cities.map { Triple(it, normalize(it.city), normalize(it.zoneId)) }
    }

    fun search(query: String): List<CityTimeZone> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return cities
        return searchIndex.filter { (_, city, zoneId) ->
            city.contains(normalizedQuery) || zoneId.contains(normalizedQuery)
        }.map { it.first }
    }

    /** Unlike TimeZone.getTimeZone, unknown IDs are rejected instead of becoming GMT. */
    fun isValidZoneId(zoneId: String): Boolean = zoneId in availableZoneIds

    internal fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .lowercase(Locale.ROOT)
            .replace(searchSeparators, "")
}
