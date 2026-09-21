package com.silentpulse.messenger.feature.worldclock

import com.silentpulse.messenger.common.util.CityTimeZone
import org.junit.Assert.*
import org.junit.Test

class WorldClockCityOptionsTest {
    @Test
    fun `Japan shortcuts display the actual city Tokyo`() {
        assertEquals(CityTimeZone("Tokyo", "Asia/Tokyo"),
            WorldClockCityOptions.canonical(CityTimeZone("Japan", "Asia/Tokyo")))
        assertEquals(CityTimeZone("Tokyo", "Asia/Tokyo"),
            WorldClockCityOptions.search("Japan").first())
    }

    @Test
    fun `Tokyo is first for a Tokyo search with no duplicate Japan entry`() {
        val choices = WorldClockCityOptions.search("Tokyo")
        assertEquals(CityTimeZone("Tokyo", "Asia/Tokyo"), choices.first())
        assertEquals(1, choices.count { it == CityTimeZone("Tokyo", "Asia/Tokyo") })
        assertFalse(choices.any { it.city == "Japan" })
        assertEquals(choices, WorldClockCityOptions.search("  TOKYO  "))
    }

    @Test
    fun `real cities sharing zones keep their chosen labels`() {
        val seattle = CityTimeZone("Seattle", "America/Los_Angeles")
        assertEquals(seattle, WorldClockCityOptions.canonical(seattle))
        assertEquals(seattle, WorldClockCityOptions.search("Seattle").first())
        assertEquals(CityTimeZone("Kolkata", "Asia/Kolkata"), WorldClockCityOptions.search("India").first())
    }
}
