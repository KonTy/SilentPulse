package com.silentpulse.messenger.feature.assistant

import com.silentpulse.messenger.common.util.CityTimeZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TimeHandlerCityAliasesTest {

    private val handler = TimeHandler()

    @Test
    fun `voice resolver reuses the shared aliases rather than a copy`() {
        val field = TimeHandler::class.java.getDeclaredField("CITY_ALIASES")
        field.isAccessible = true
        assertSame(CityTimeZones.aliases, field.get(null))
    }

    @Test
    fun `every shared alias retains its exact zone and existing spoken label`() {
        CityTimeZones.aliases.forEach { (alias, zoneId) ->
            val result = resolveZone(alias)
            assertEquals(alias, zoneId, (result.first as ZoneId).id)
            assertEquals(alias, zoneId.substringAfterLast('/').replace('_', ' '), result.second)
        }
    }

    @Test
    fun `major city voice commands retain their original response labels`() {
        val commands = mapOf(
            "What time is it in Seattle please?" to "Los Angeles",
            "time in New York" to "New York",
            "Tell me the time in Tokyo" to "Tokyo",
            "What's the time in Kathmandu" to "Kathmandu",
            "time in Mumbai" to "Kolkata"
        )
        commands.forEach { (command, label) ->
            assertTrue(command, handler.isTimeCommand(command))
            assertTrue(command, handler.getTimeResponse(command).startsWith("In $label, it is "))
        }
    }

    @Test
    fun `alias abbreviations and fuzzy city resolution still work`() {
        val locations = mapOf(
            "nyc" to "America/New_York",
            "sf" to "America/Los_Angeles",
            "seattl" to "America/Los_Angeles",
            "tokoyo" to "Asia/Tokyo"
        )
        locations.forEach { (location, zoneId) ->
            assertEquals(location, zoneId, (resolveZone(location).first as ZoneId).id)
        }
    }

    private fun resolveZone(location: String): Pair<*, *> {
        val method = TimeHandler::class.java.getDeclaredMethod("resolveZone", String::class.java)
        method.isAccessible = true
        return method.invoke(handler, location) as Pair<*, *>
    }
}
