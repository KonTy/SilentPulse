package com.silentpulse.messenger.feature.worldclock.weather

import com.silentpulse.messenger.common.util.CityTimeZone
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class ClockWeatherRefresherTest {
    private val store = WeatherCacheTestStore()
    private val city = CityTimeZone("Seattle", "America/Los_Angeles")
    private var now = 1_700_000_000_000L
    private var weather = ClockWeather(WeatherCoordinates(47.61, -122.33), 2, true, now)
    private val requests = mutableListOf<Pair<CityTimeZone, WeatherCoordinates?>>()
    private val updates = mutableListOf<CityTimeZone>()
    private var error: Exception? = null
    private var stopped = false
    private var current = true
    private var afterFetch: () -> Unit = {}
    private val refresher = ClockWeatherRefresher(store.cache, { requested, coordinates ->
        requests += requested to coordinates
        error?.let { throw it }
        afterFetch()
        weather
    }, { now })

    private fun refresh(online: Boolean = true, force: Boolean = false) = refresher.refresh(
        setOf(city), if (force) city else null, online,
        { stopped }, { current }, { updates += it }
    )

    @Test
    fun `fresh shared city cache avoids redundant network requests`() {
        assertEquals(WeatherRefreshResult.SUCCESS, refresh())
        assertEquals(WeatherRefreshResult.SUCCESS, refresh())
        assertEquals(1, requests.size)
        assertEquals(weather, store.cache.load(city)?.displayWeather(now))
    }

    @Test
    fun `hourly refresh reuses coordinates and replaces conditions`() {
        refresh()
        now += TimeUnit.HOURS.toMillis(1)
        weather = weather.copy(weatherCode = 61, observedAtMillis = now)

        assertEquals(WeatherRefreshResult.SUCCESS, refresh())
        assertEquals(weather.coordinates, requests.last().second)
        assertEquals(weather, store.cache.load(city)?.weather)
    }

    @Test
    fun `manual refresh is allowed after a minute but rapid taps use the fresh cache`() {
        refresh()
        refresh(force = true)
        assertEquals(1, requests.size)

        now += TimeUnit.MINUTES.toMillis(2)
        refresh(force = true)
        assertEquals(2, requests.size)
    }

    @Test
    fun `offline refresh shows unavailable and requests a retry without contacting servers`() {
        assertEquals(WeatherRefreshResult.RETRY, refresh(online = false))
        assertTrue(requests.isEmpty())
        assertEquals(WeatherFetchState.UNAVAILABLE, store.cache.load(city)?.state)
    }

    @Test
    fun `fresh conditions remain available offline without retrying`() {
        refresh()
        assertEquals(WeatherRefreshResult.SUCCESS, refresh(online = false))
        assertEquals(1, requests.size)
        assertEquals(weather, store.cache.load(city)?.displayWeather(now))
    }

    @Test
    fun `network errors are retryable and explicitly unavailable`() {
        error = IOException("Network unavailable")
        assertEquals(WeatherRefreshResult.RETRY, refresh())
        assertEquals(WeatherFetchState.UNAVAILABLE, store.cache.load(city)?.state)
    }

    @Test
    fun `missing location and malformed or stale data fail without inventing conditions`() {
        error = WeatherLocationUnavailableException()
        assertEquals(WeatherRefreshResult.FAILURE, refresh())
        error = WeatherResponseException("Invalid weather")
        assertEquals(WeatherRefreshResult.FAILURE, refresh())
        error = null
        weather = weather.copy(observedAtMillis = now - TimeUnit.HOURS.toMillis(3))
        assertEquals(WeatherRefreshResult.FAILURE, refresh())
        assertNull(store.cache.load(city)?.displayWeather(now))
    }

    @Test
    fun `deleted or reconfigured clocks do not receive a late network result`() {
        afterFetch = { current = false }
        assertEquals(WeatherRefreshResult.SUCCESS, refresh())
        assertNull(store.cache.load(city)?.weather)
    }

    @Test
    fun `cancelled work cannot publish a late network result`() {
        afterFetch = { stopped = true }
        assertEquals(WeatherRefreshResult.CANCELLED, refresh())
        assertNull(store.cache.load(city)?.weather)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is not swallowed as a weather failure`() {
        error = CancellationException()
        refresh()
    }
}
