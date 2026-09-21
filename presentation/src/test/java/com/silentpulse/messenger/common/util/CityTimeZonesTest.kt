package com.silentpulse.messenger.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

class CityTimeZonesTest {

    @Test
    fun `major friendly aliases and UTC are available`() {
        val expected = listOf(
            CityTimeZone("Seattle", "America/Los_Angeles"),
            CityTimeZone("New York", "America/New_York"),
            CityTimeZone("Tokyo", "Asia/Tokyo"),
            CityTimeZone("Kathmandu", "Asia/Kathmandu"),
            CityTimeZone("Mumbai", "Asia/Kolkata"),
            CityTimeZone("UTC", "UTC")
        )
        expected.forEach { assertTrue("Missing $it", it in CityTimeZones.cities) }
    }

    @Test
    fun `IANA cities beyond voice aliases have readable labels and full zone IDs`() {
        val expected = listOf(
            CityTimeZone("Abidjan", "Africa/Abidjan"),
            CityTimeZone("McMurdo", "Antarctica/McMurdo"),
            CityTimeZone("Knox", "America/Indiana/Knox"),
            CityTimeZone("Port-au-Prince", "America/Port-au-Prince")
        )
        expected.forEach { assertTrue("Missing $it", it in CityTimeZones.cities) }
    }

    @Test
    fun `results are stable sorted and deduplicated by city and zone`() {
        val cities = CityTimeZones.cities
        assertSame(cities, CityTimeZones.cities)
        assertEquals(cities.distinct(), cities)
        assertEquals(cities.sortedWith(compareBy({ it.city }, { it.zoneId })), cities)
        assertEquals(1, cities.count { it == CityTimeZone("Tokyo", "Asia/Tokyo") })
        assertEquals(
            1,
            cities.count { it == CityTimeZone("Dar es Salaam", "Africa/Dar_es_Salaam") }
        )
        assertEquals(CityTimeZones.search("america"), CityTimeZones.search("AMERICA"))
    }

    @Test
    fun `different cities sharing a zone remain separately selectable`() {
        val cities = CityTimeZones.cities.filter { it.zoneId == "America/Los_Angeles" }
        listOf("Seattle", "Los Angeles", "San Francisco", "San Diego").forEach { label ->
            assertTrue(label, cities.any { it.city == label })
        }
    }

    @Test
    fun `same city labels with different full zone IDs are not collapsed`() {
        val cities = CityTimeZones.search("Buenos Aires")
        assertTrue(CityTimeZone("Buenos Aires", "America/Buenos_Aires") in cities)
        assertTrue(CityTimeZone("Buenos Aires", "America/Argentina/Buenos_Aires") in cities)
    }

    @Test
    fun `all suggestions are supported with no silent GMT fallback`() {
        val availableIds = TimeZone.getAvailableIDs().toSet()
        CityTimeZones.cities.forEach { city ->
            assertTrue(city.zoneId, city.zoneId in availableIds)
            assertTrue(city.zoneId, CityTimeZones.isValidZoneId(city.zoneId))
            assertEquals(city.zoneId, TimeZone.getTimeZone(city.zoneId).id)
        }
    }

    @Test
    fun `invalid and noncanonical IDs are rejected rather than interpreted as GMT`() {
        assertEquals("GMT", TimeZone.getTimeZone("America/Not_A_City").id)
        listOf(
            "", "America/Not_A_City", "Seattle", "america/new_york",
            " America/New_York ", "GMT+25:00", "GMT+01:23"
        ).forEach { assertFalse(it, CityTimeZones.isValidZoneId(it)) }
        assertTrue(CityTimeZones.isValidZoneId("UTC"))
        assertTrue(CityTimeZones.isValidZoneId("Asia/Kathmandu"))
    }

    @Test
    fun `suggestions omit fixed offsets and ambiguous legacy abbreviations`() {
        assertTrue(CityTimeZones.cities.none { it.zoneId.startsWith("Etc/") })
        assertTrue(CityTimeZones.cities.all { it.zoneId == "UTC" || '/' in it.zoneId })
        listOf("EST", "CST", "PST", "GMT+5", "Etc/GMT+5", "US/Eastern").forEach { query ->
            assertTrue(query, CityTimeZones.cities.none { it.zoneId == query })
        }
        assertTrue(CityTimeZones.search("Etc/GMT").isEmpty())
    }

    @Test
    fun `search ignores case whitespace and underscores including compact city names`() {
        val expected = CityTimeZones.search("New York")
        assertTrue(CityTimeZone("New York", "America/New_York") in expected)
        listOf("NEW_YORK", "newyork", " \tNeW__ YoRk\n", "New\u00a0York").forEach {
            assertEquals(it, expected, CityTimeZones.search(it))
        }
    }

    @Test
    fun `search matches full zone IDs as well as friendly city labels`() {
        val expected = CityTimeZones.cities.filter { it.zoneId == "America/Los_Angeles" }
        assertEquals(expected, CityTimeZones.search("aMeRiCa / lOs__AnGeLeS"))
        assertTrue(
            CityTimeZone("Knox", "America/Indiana/Knox") in
                CityTimeZones.search("AMERICA / INDIANA / KNOX")
        )
        assertTrue(
            CityTimeZone("Seattle", "America/Los_Angeles") in CityTimeZones.search("Seattle")
        )
    }

    @Test
    fun `search ignores precomposed and decomposed accents`() {
        val expected = CityTimeZones.search("sao paulo")
        assertTrue(CityTimeZone("Sao Paulo", "America/Sao_Paulo") in expected)
        assertEquals(expected, CityTimeZones.search("SÃO_PAULO"))
        assertEquals(expected, CityTimeZones.search("sa\u0303o paulo"))
        assertEquals(CityTimeZones.search("bogota"), CityTimeZones.search("Bogotá"))
        assertEquals(CityTimeZones.search("zurich"), CityTimeZones.search("ZÜRICH"))
    }

    @Test
    fun `search does not depend on the device locale`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals(CityTimeZones.search("fiji"), CityTimeZones.search("FIJI"))
            assertTrue(
                CityTimeZone("Fiji", "Pacific/Fiji") in CityTimeZones.search("PACIFIC/FIJI")
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `blank queries return the complete catalog and unknown queries return nothing`() {
        assertSame(CityTimeZones.cities, CityTimeZones.search(""))
        assertSame(CityTimeZones.cities, CityTimeZones.search(" \t_\n"))
        assertTrue(CityTimeZones.search("not a real city or timezone").isEmpty())
    }

    @Test
    fun `all original voice aliases and their iteration order are preserved`() {
        // Fingerprint the original 214-entry map, including fuzzy-match tie-breaking order.
        val originalMap = CityTimeZones.aliases.entries.joinToString("") { (alias, zoneId) ->
            "$alias=$zoneId\n"
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(originalMap.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(214, CityTimeZones.aliases.size)
        assertEquals(
            "12b9f447d328223f48067ecd34abd69fd5ba938c404bef5671507d88d3bd765a",
            fingerprint
        )
    }
}
