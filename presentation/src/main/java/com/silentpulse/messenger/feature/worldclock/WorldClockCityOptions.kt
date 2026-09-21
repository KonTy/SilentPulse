package com.silentpulse.messenger.feature.worldclock

import com.silentpulse.messenger.common.util.CityTimeZone
import com.silentpulse.messenger.common.util.CityTimeZones
import com.silentpulse.messenger.feature.worldclock.weather.OpenMeteoClockWeather

object WorldClockCityOptions {
    fun canonical(city: CityTimeZone): CityTimeZone =
        city.copy(city = OpenMeteoClockWeather.weatherLocationLabel(city))

    fun search(query: String): List<CityTimeZone> {
        val key = CityTimeZones.normalize(query)
        return CityTimeZones.search(query).map { original ->
            val city = canonical(original)
            val labels = listOf(original.city, city.city).map(CityTimeZones::normalize)
            val rank = when {
                key in labels -> 0
                labels.any { it.startsWith(key) } -> 1
                else -> 2
            }
            city to rank
        }.sortedWith(compareBy<Pair<CityTimeZone, Int>> { it.second }
            .thenBy { it.first.city }.thenBy { it.first.zoneId })
            .map { it.first }
            .distinct()
    }
}
