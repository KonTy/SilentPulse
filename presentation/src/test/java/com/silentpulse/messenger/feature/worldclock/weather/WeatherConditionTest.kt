package com.silentpulse.messenger.feature.worldclock.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WeatherConditionTest {
    @Test
    fun `all supported WMO codes have meaningful icons`() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67,
            71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        for (code in codes) {
            assertNotEquals(WeatherCondition.UNAVAILABLE, WeatherCondition.fromCode(code, true))
            assertNotEquals(WeatherCondition.UNAVAILABLE, WeatherCondition.fromCode(code, false))
        }
    }

    @Test
    fun `night conditions use moon icons not sunshine`() {
        assertEquals(WeatherCondition.CLEAR_NIGHT, WeatherCondition.fromCode(0, false))
        assertEquals(WeatherCondition.CLEAR_DAY, WeatherCondition.fromCode(0, true))
        assertEquals(WeatherCondition.PARTLY_CLOUDY_NIGHT, WeatherCondition.fromCode(2, false))
        assertEquals(WeatherCondition.PARTLY_CLOUDY_DAY, WeatherCondition.fromCode(2, true))
    }

    @Test
    fun `rain snow freezing rain and storms stay distinct`() {
        assertEquals(WeatherCondition.RAIN, WeatherCondition.fromCode(61, true))
        assertEquals(WeatherCondition.SNOW, WeatherCondition.fromCode(71, true))
        assertEquals(WeatherCondition.SLEET, WeatherCondition.fromCode(66, true))
        assertEquals(WeatherCondition.THUNDERSTORM, WeatherCondition.fromCode(95, true))
        assertEquals(WeatherCondition.FOG, WeatherCondition.fromCode(45, true))
    }

    @Test
    fun `unknown codes never default to sunny`() {
        for (code in listOf(-1, 4, 100, 999)) {
            assertEquals(WeatherCondition.UNAVAILABLE, WeatherCondition.fromCode(code, true))
        }
    }
}
