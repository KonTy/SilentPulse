package com.silentpulse.messenger.feature.worldclock.weather

import com.silentpulse.messenger.common.util.CityTimeZone
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ClockWeatherCacheTest {
    private val store = WeatherCacheTestStore()
    private val city = CityTimeZone("Seattle", "America/Los_Angeles")
    private val now = 1_700_000_000_000L
    private val weather = ClockWeather(WeatherCoordinates(47.61, -122.33), 2, true, now)

    @Test
    fun `cities sharing a time zone do not share the wrong weather`() {
        val losAngeles = CityTimeZone("Los Angeles", city.zoneId)
        val sunny = weather.copy(coordinates = WeatherCoordinates(34.05, -118.24), weatherCode = 0)
        store.cache.save(city, weather, now)
        store.cache.save(losAngeles, sunny, now)

        assertEquals(weather, store.cache.load(city)?.displayWeather(now))
        assertEquals(sunny, store.cache.load(losAngeles)?.displayWeather(now))
        assertEquals(weather, ClockWeatherCache(store.preferences).load(city)?.weather)
    }

    @Test
    fun `same city label in different zones remains separate`() {
        store.cache.save(CityTimeZone("London", "Europe/London"), weather, now)
        assertNull(store.cache.load(CityTimeZone("London", "America/Toronto")))
    }

    @Test
    fun `cache becomes due hourly and hides old observations`() {
        store.cache.save(city, weather, now)
        val entry = store.cache.load(city)!!

        assertTrue(entry.isFresh(now))
        assertFalse(entry.isFresh(now + TimeUnit.HOURS.toMillis(1)))
        assertNotNull(entry.displayWeather(now + TimeUnit.HOURS.toMillis(1)))
        assertNull(entry.displayWeather(now + TimeUnit.HOURS.toMillis(2) + 1))
        assertNull(entry.displayWeather(now - 1))
    }

    @Test
    fun `failed request does not present cached conditions as current but keeps coordinates`() {
        store.cache.save(city, weather, now)
        store.cache.markState(city, WeatherFetchState.UNAVAILABLE, now + 1)
        val entry = store.cache.load(city)!!

        assertNull(entry.displayWeather(now + 1))
        assertFalse(entry.isFresh(now + 1))
        assertEquals(weather.coordinates, entry.weather?.coordinates)
    }

    @Test
    fun `loading state is bounded and may retain a recent icon while refreshing`() {
        store.cache.markState(city, WeatherFetchState.LOADING, now)
        assertTrue(store.cache.load(city)!!.isLoading(now))
        assertNull(store.cache.load(city)!!.weather)
        assertFalse(store.cache.load(city)!!.isLoading(now + TimeUnit.MINUTES.toMillis(6)))

        store.cache.save(city, weather, now)
        store.cache.markState(city, WeatherFetchState.LOADING, now + 1)
        assertEquals(weather, store.cache.load(city)?.displayWeather(now + 1))
    }

    @Test
    fun `late older network responses cannot replace newer observations`() {
        store.cache.save(city, weather, now)
        store.cache.save(city, weather.copy(weatherCode = 71, observedAtMillis = now - 60_000), now + 1)

        assertEquals(weather, store.cache.load(city)?.weather)
        assertEquals(now, store.cache.load(city)?.fetchedAtMillis)
    }

    @Test
    fun `corrupt cached values do not fall back to clear skies or zero coordinates`() {
        store.cache.save(city, weather, now)
        val prefix = "${city.city.length}:${city.city}:${city.zoneId}"
        store.values["$prefix.latitude"] = "NaN"
        assertNull(store.cache.load(city))

        store.cache.save(city, weather, now)
        store.values["$prefix.code"] = 999
        assertNull(store.cache.load(city))
    }

    @Test
    fun `removing the last clock can clear the separate weather cache`() {
        store.cache.save(city, weather, now)
        store.cache.clear()
        assertNull(store.cache.load(city))
    }
}
