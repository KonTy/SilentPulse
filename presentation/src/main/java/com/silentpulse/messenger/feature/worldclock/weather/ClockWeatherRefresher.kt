package com.silentpulse.messenger.feature.worldclock.weather

import com.silentpulse.messenger.common.util.CityTimeZone
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class WeatherRefreshResult { SUCCESS, RETRY, FAILURE, CANCELLED }

class ClockWeatherRefresher(
    private val cache: ClockWeatherCache,
    private val fetch: (CityTimeZone, WeatherCoordinates?) -> ClockWeather,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun refresh(
        cities: Set<CityTimeZone>,
        forcedCity: CityTimeZone?,
        online: Boolean,
        isStopped: () -> Boolean,
        isCurrent: (CityTimeZone) -> Boolean,
        onChanged: (CityTimeZone) -> Unit
    ): WeatherRefreshResult {
        var failed = false
        var retry = false
        for (city in cities) {
            if (isStopped()) return WeatherRefreshResult.CANCELLED
            if (!isCurrent(city)) continue
            val entry = cache.load(city)
            val time = now()
            val force = city == forcedCity &&
                (entry == null || time - entry.fetchedAtMillis >= TimeUnit.MINUTES.toMillis(1))
            if (entry?.isFresh(time) == true && !force) {
                onChanged(city)
                continue
            }
            if (!online) {
                Timber.w("World clock weather unavailable: no network connection")
                cache.markState(city, WeatherFetchState.UNAVAILABLE, time)
                onChanged(city)
                retry = true
                continue
            }

            cache.markState(city, WeatherFetchState.LOADING, time)
            onChanged(city)
            try {
                val weather = fetch(city, entry?.weather?.coordinates)
                if (isStopped()) return WeatherRefreshResult.CANCELLED
                if (!isCurrent(city)) continue
                val condition = WeatherCondition.fromCode(weather.weatherCode, weather.isDay)
                if (condition == WeatherCondition.UNAVAILABLE ||
                    now() - weather.observedAtMillis !in
                    -TimeUnit.MINUTES.toMillis(15)..CachedClockWeather.MAX_AGE_MILLIS) {
                    throw WeatherResponseException("Invalid or stale current weather observation")
                }
                cache.save(city, weather, now())
            } catch (e: WeatherLocationUnavailableException) {
                Timber.w("World clock weather unavailable: location could not be resolved")
                failed = true
                if (!isStopped() && isCurrent(city)) {
                    cache.markState(city, WeatherFetchState.UNAVAILABLE, now())
                }
            } catch (e: WeatherResponseException) {
                Timber.w("World clock weather unavailable: invalid response")
                failed = true
                if (!isStopped() && isCurrent(city)) {
                    cache.markState(city, WeatherFetchState.UNAVAILABLE, now())
                }
            } catch (e: IOException) {
                Timber.w("World clock weather request failed (%s)", e.javaClass.simpleName)
                retry = true
                if (!isStopped() && isCurrent(city)) {
                    cache.markState(city, WeatherFetchState.UNAVAILABLE, now())
                }
            }
            if (isStopped()) return WeatherRefreshResult.CANCELLED
            if (isCurrent(city)) onChanged(city)
        }
        return when {
            retry -> WeatherRefreshResult.RETRY
            failed -> WeatherRefreshResult.FAILURE
            else -> WeatherRefreshResult.SUCCESS
        }
    }
}
