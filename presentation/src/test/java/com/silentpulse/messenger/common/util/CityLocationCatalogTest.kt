package com.silentpulse.messenger.common.util

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CityLocationCatalogTest {
    @Test
    fun `every explicit alias has audited region metadata`() {
        assertEquals(CityTimeZones.aliases.keys, cityRegionHints.keys)
        assertEquals(214, cityRegionHints.size)
        cityRegionHints.forEach { (alias, hint) ->
            assertTrue(alias, hint.country.matches(Regex("[A-Z]{2}")))
            assertTrue(alias, hint.place.isNotBlank())
        }
    }

    @Test
    fun `Lafayette choices identify Indiana Louisiana California and Colorado explicitly`() {
        val choices = CityTimeZones.search("Lafayette")
        listOf(
            CityTimeZone("Lafayette, Indiana", "America/Indiana/Indianapolis"),
            CityTimeZone("Lafayette, Louisiana", "America/Chicago"),
            CityTimeZone("Lafayette, California", "America/Los_Angeles"),
            CityTimeZone("Lafayette, Colorado", "America/Denver")
        ).forEach { assertTrue(it.toString(), it in choices) }
        assertFalse(choices.any { it.city == "Lafayette" })
        val indiana = CityLocationCatalog.resolveQualified("Lafayette IN").single()
        assertEquals("America/Indiana/Indianapolis", indiana.zoneId)
        val instant = Instant.parse("2026-09-30T06:36:00Z")
        assertEquals(2, instant.atZone(ZoneId.of(indiana.zoneId)).hour)
        assertEquals(1, instant.atZone(ZoneId.of("America/Chicago")).hour)
    }

    @Test
    fun `legacy Louisiana clock is relabeled rather than silently shifted an hour`() {
        val old = CityTimeZone("Lafayette", "America/Chicago")
        assertEquals(CityTimeZone("Lafayette, Louisiana", "America/Chicago"), CityLocationCatalog.canonical(old))
        assertEquals("America/Chicago", old.zoneId)
        assertTrue(CityLocationCatalog.isConsistent(old))
        assertFalse(CityLocationCatalog.isConsistent(CityTimeZone("Lafayette, Indiana", "America/Chicago")))
    }

    @Test
    fun `other ambiguous names and explicit qualifiers select their actual location`() {
        val expected = mapOf(
            "Portland Maine" to "America/New_York",
            "Portland OR" to "America/Los_Angeles",
            "Birmingham England" to "Europe/London",
            "Richmond CA" to "America/Los_Angeles",
            "Jackson NJ" to "America/New_York",
            "San Jose Costa Rica" to "America/Costa_Rica",
            "London Ontario" to "America/Toronto",
            "La Paz Mexico" to "America/Mazatlan",
            "El Paso TX" to "America/Denver",
            "Perth Australia" to "Australia/Perth",
            "Seattle WA" to "America/Los_Angeles"
        )
        expected.forEach { (query, zone) ->
            assertEquals(query, zone, CityLocationCatalog.resolveQualified(query).single().zoneId)
        }
    }

    @Test
    fun `winter summer and non-hour offsets use zone rules rather than fixed offsets`() {
        val january = Instant.parse("2026-01-15T12:00:00Z")
        val july = Instant.parse("2026-07-15T12:00:00Z")
        val expectedMinutes = mapOf(
            "America/Indiana/Indianapolis" to (-300 to -240),
            "America/Chicago" to (-360 to -300),
            "America/Phoenix" to (-420 to -420),
            "Pacific/Honolulu" to (-600 to -600),
            "Europe/London" to (0 to 60),
            "Australia/Sydney" to (660 to 600),
            "Australia/Adelaide" to (630 to 570),
            "Asia/Kathmandu" to (345 to 345),
            "Asia/Kolkata" to (330 to 330),
            "Asia/Almaty" to (300 to 300),
            "America/Mexico_City" to (-360 to -360)
        )
        expectedMinutes.forEach { (zone, offsets) ->
            val rules = ZoneId.of(zone).rules
            assertEquals(zone, offsets.first, rules.getOffset(january).totalSeconds / 60)
            assertEquals(zone, offsets.second, rules.getOffset(july).totalSeconds / 60)
        }
    }
}
