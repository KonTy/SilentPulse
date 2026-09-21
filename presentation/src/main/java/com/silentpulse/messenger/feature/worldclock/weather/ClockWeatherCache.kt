package com.silentpulse.messenger.feature.worldclock.weather

import android.content.Context
import android.content.SharedPreferences
import com.silentpulse.messenger.common.util.CityTimeZone
import timber.log.Timber
import java.util.concurrent.TimeUnit

enum class WeatherFetchState { LOADING, READY, UNAVAILABLE }

data class CachedClockWeather(
    val weather: ClockWeather?,
    val fetchedAtMillis: Long,
    val attemptedAtMillis: Long,
    val state: WeatherFetchState
) {
    fun displayWeather(now: Long): ClockWeather? = weather?.takeIf {
        state != WeatherFetchState.UNAVAILABLE &&
            now - fetchedAtMillis in 0..MAX_AGE_MILLIS &&
            now - it.observedAtMillis in -TimeUnit.MINUTES.toMillis(15)..MAX_AGE_MILLIS
    }

    fun isFresh(now: Long): Boolean = state == WeatherFetchState.READY &&
        displayWeather(now) != null && now - fetchedAtMillis < REFRESH_MILLIS

    fun isLoading(now: Long): Boolean = state == WeatherFetchState.LOADING &&
        now - attemptedAtMillis in 0..TimeUnit.MINUTES.toMillis(5)

    companion object {
        val REFRESH_MILLIS = TimeUnit.HOURS.toMillis(1)
        val MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(2)
    }
}

class ClockWeatherCache(private val preferences: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences("world_clock_weather", Context.MODE_PRIVATE)
    )

    fun load(city: CityTimeZone): CachedClockWeather? {
        val prefix = key(city)
        val stateName = preferences.getString("$prefix.state", null) ?: return null
        val state = WeatherFetchState.entries.find { it.name == stateName }
        if (state == null) {
            Timber.w("Invalid world clock weather cache state")
            return null
        }
        val fetched = preferences.getLong("$prefix.fetched", 0)
        val attempted = preferences.getLong("$prefix.attempted", 0)
        val weather = if (preferences.contains("$prefix.code")) {
            val latitude = preferences.getString("$prefix.latitude", null)?.toDoubleOrNull()
            val longitude = preferences.getString("$prefix.longitude", null)?.toDoubleOrNull()
            val observed = preferences.getLong("$prefix.observed", 0)
            val code = preferences.getInt("$prefix.code", -1)
            val day = preferences.getBoolean("$prefix.day", false)
            if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || observed <= 0 ||
                fetched <= 0 || !preferences.contains("$prefix.day") ||
                WeatherCondition.fromCode(code, day) == WeatherCondition.UNAVAILABLE) {
                Timber.w("Invalid world clock cached conditions")
                return null
            }
            ClockWeather(
                WeatherCoordinates(latitude, longitude),
                code,
                day,
                observed
            )
        } else null
        if (state == WeatherFetchState.READY && weather == null) {
            Timber.w("World clock weather cache is missing its conditions")
            return null
        }
        return CachedClockWeather(weather, fetched, attempted, state)
    }

    fun save(city: CityTimeZone, weather: ClockWeather, now: Long) {
        val existing = load(city)?.weather
        if (existing != null && existing.observedAtMillis > weather.observedAtMillis) {
            Timber.w("Ignoring an older world clock weather observation")
            markState(city, WeatherFetchState.READY, now)
            return
        }
        val prefix = key(city)
        preferences.edit()
            .putString("$prefix.state", WeatherFetchState.READY.name)
            .putString("$prefix.latitude", weather.coordinates.latitude.toString())
            .putString("$prefix.longitude", weather.coordinates.longitude.toString())
            .putInt("$prefix.code", weather.weatherCode)
            .putBoolean("$prefix.day", weather.isDay)
            .putLong("$prefix.observed", weather.observedAtMillis)
            .putLong("$prefix.fetched", now)
            .putLong("$prefix.attempted", now)
            .apply()
    }

    fun markState(city: CityTimeZone, state: WeatherFetchState, now: Long) {
        val prefix = key(city)
        preferences.edit()
            .putString("$prefix.state", state.name)
            .putLong("$prefix.attempted", now)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(city: CityTimeZone) = "${city.city.length}:${city.city}:${city.zoneId}"
}
