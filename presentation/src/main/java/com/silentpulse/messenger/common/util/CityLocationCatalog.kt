package com.silentpulse.messenger.common.util

import java.util.Locale

data class ClockCityLocation(
    val label: String,
    val place: String,
    val zoneId: String,
    val country: String,
    val region: String?,
    val qualifiers: List<String>,
    val legacyDefault: Boolean = false
) {
    fun selection() = CityTimeZone(label, zoneId)
}

/** Explicit choices for shared city names; legacy unqualified choices keep their original zones. */
object CityLocationCatalog {
    private fun us(place: String, region: String, code: String, zone: String, legacy: Boolean = false) =
        ClockCityLocation("$place, $region", place, zone, "US", region, listOf(region, code), legacy)

    val locations = listOf(
        us("Lafayette", "Louisiana", "LA", "America/Chicago", true),
        us("Lafayette", "Indiana", "IN", "America/Indiana/Indianapolis"),
        us("Lafayette", "California", "CA", "America/Los_Angeles"),
        us("Lafayette", "Colorado", "CO", "America/Denver"),
        us("Portland", "Oregon", "OR", "America/Los_Angeles", true),
        us("Portland", "Maine", "ME", "America/New_York"),
        us("Birmingham", "Alabama", "AL", "America/Chicago", true),
        ClockCityLocation("Birmingham, United Kingdom", "Birmingham", "Europe/London", "GB", "England",
            listOf("United Kingdom", "UK", "GB", "England")),
        us("Richmond", "Virginia", "VA", "America/New_York", true),
        us("Richmond", "California", "CA", "America/Los_Angeles"),
        ClockCityLocation("Richmond, British Columbia", "Richmond", "America/Vancouver", "CA", "British Columbia",
            listOf("British Columbia", "BC", "Canada")),
        us("Jackson", "Mississippi", "MS", "America/Chicago", true),
        us("Jackson", "Michigan", "MI", "America/Detroit"),
        us("Jackson", "New Jersey", "NJ", "America/New_York"),
        us("Jackson", "Tennessee", "TN", "America/Chicago"),
        us("Jackson", "Wyoming", "WY", "America/Denver"),
        us("Columbus", "Ohio", "OH", "America/New_York", true),
        us("Columbus", "Georgia", "GA", "America/New_York"),
        us("Columbus", "Mississippi", "MS", "America/Chicago"),
        us("San Jose", "California", "CA", "America/Los_Angeles", true),
        ClockCityLocation("San Jose, Costa Rica", "San Jose", "America/Costa_Rica", "CR", "Provincia de San Jos\u00e9",
            listOf("Costa Rica", "CR")),
        ClockCityLocation("London, United Kingdom", "London", "Europe/London", "GB", "England",
            listOf("United Kingdom", "UK", "GB", "England"), true),
        ClockCityLocation("London, Ontario", "London", "America/Toronto", "CA", "Ontario",
            listOf("Ontario", "ON", "Canada")),
        ClockCityLocation("La Paz, Bolivia", "La Paz", "America/La_Paz", "BO", "La Paz Department",
            listOf("Bolivia", "BO"), true),
        ClockCityLocation("La Paz, Mexico", "La Paz", "America/Mazatlan", "MX", "Baja California Sur",
            listOf("Mexico", "MX", "Baja California Sur")),
        ClockCityLocation("Paris, France", "Paris", "Europe/Paris", "FR", "Ile-de-France",
            listOf("France", "FR"), true),
        us("Paris", "Texas", "TX", "America/Chicago")
    )

    fun canonical(city: CityTimeZone): CityTimeZone {
        val key = CityTimeZones.normalize(city.city)
        val inZone = locations.filter { sameZone(it.zoneId, city.zoneId) }
        val exact = inZone.find { CityTimeZones.normalize(it.label) == key }
        val original = inZone.find { it.legacyDefault && CityTimeZones.normalize(it.place) == key }
        val alternate = inZone.filter { CityTimeZones.normalize(it.place) == key }.singleOrNull()
        return (exact ?: original ?: alternate)?.let { city.copy(city = it.label) } ?: city
    }

    fun resolveQualified(query: String): List<CityTimeZone> =
        qualifiedMatches(query).map(ClockCityLocation::selection).distinct()
            .filter { CityTimeZones.isValidZoneId(it.zoneId) }

    fun locationFor(city: CityTimeZone): ClockCityLocation? =
        qualifiedMatches(canonical(city).city).filter { sameZone(it.zoneId, city.zoneId) }.singleOrNull()

    fun isConsistent(city: CityTimeZone): Boolean {
        val matches = qualifiedMatches(city.city)
        return matches.isEmpty() || matches.any { sameZone(it.zoneId, city.zoneId) }
    }

    private fun qualifiedMatches(query: String): List<ClockCityLocation> {
        val key = CityTimeZones.normalize(query)
        return explicitQueries[key] ?: regionQueries[key] ?: emptyList()
    }

    private val explicitQueries by lazy {
        locations.flatMap { location ->
            (listOf(location.label) + location.qualifiers.map { "${location.place} $it" })
                .map { CityTimeZones.normalize(it) to location }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
    }

    private val regionQueries by lazy {
        val queries = mutableListOf<Pair<String, ClockCityLocation>>()
        CityTimeZones.aliases.forEach { (alias, zone) ->
            val hint = cityRegionHints.getValue(alias)
            val country = Locale("", hint.country).getDisplayCountry(Locale.ENGLISH)
            val countries = listOf(country, hint.country) + countryAliases[hint.country].orEmpty()
            val regions = listOf(hint.region) + if (hint.country == "US") {
                stateCodes.filterValues { it == hint.region }.keys
            } else emptySet()
            regions.filter(String::isNotBlank).forEach { qualifier ->
                queries += CityTimeZones.normalize("$alias $qualifier") to ClockCityLocation(
                    "${hint.place}, ${hint.region}", hint.place, zone, hint.country, hint.region, emptyList()
                )
            }
            countries.forEach { qualifier ->
                queries += CityTimeZones.normalize("$alias $qualifier") to ClockCityLocation(
                    "${hint.place}, $country", hint.place, zone, hint.country,
                    hint.region.takeIf(String::isNotBlank), emptyList()
                )
            }
        }
        queries.groupBy({ it.first }, { it.second }).mapValues { entry ->
            entry.value.distinctBy { it.label to CityTimeZones.canonicalZoneId(it.zoneId) }
        }
    }

    private fun sameZone(first: String, second: String) =
        CityTimeZones.canonicalZoneId(first) == CityTimeZones.canonicalZoneId(second)

    private val countryAliases = mapOf(
        "US" to listOf("USA", "United States of America"),
        "GB" to listOf("UK", "Great Britain"),
        "KR" to listOf("Korea"),
        "AE" to listOf("UAE")
    )
    private val stateCodes = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
        "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware",
        "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho",
        "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
        "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
        "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
        "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
        "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
        "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
        "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
        "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah",
        "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia",
        "WI" to "Wisconsin", "WY" to "Wyoming", "DC" to "District of Columbia"
    )
}
