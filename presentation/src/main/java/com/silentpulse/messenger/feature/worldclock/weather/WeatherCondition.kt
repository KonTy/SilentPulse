package com.silentpulse.messenger.feature.worldclock.weather

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.silentpulse.messenger.R

enum class WeatherCondition(@DrawableRes val icon: Int, @StringRes val description: Int) {
    CLEAR_DAY(R.drawable.ic_weather_sun, R.string.world_clock_weather_clear),
    CLEAR_NIGHT(R.drawable.ic_weather_moon, R.string.world_clock_weather_clear_night),
    PARTLY_CLOUDY_DAY(R.drawable.ic_weather_partly_sunny, R.string.world_clock_weather_partly_cloudy),
    PARTLY_CLOUDY_NIGHT(R.drawable.ic_weather_partly_night, R.string.world_clock_weather_partly_cloudy),
    CLOUDY(R.drawable.ic_weather_cloudy, R.string.world_clock_weather_cloudy),
    FOG(R.drawable.ic_weather_fog, R.string.world_clock_weather_fog),
    RAIN(R.drawable.ic_weather_rain, R.string.world_clock_weather_rain),
    SLEET(R.drawable.ic_weather_sleet, R.string.world_clock_weather_sleet),
    SNOW(R.drawable.ic_weather_snow, R.string.world_clock_weather_snow),
    THUNDERSTORM(R.drawable.ic_weather_storm, R.string.world_clock_weather_storm),
    UNAVAILABLE(R.drawable.ic_weather_unavailable, R.string.world_clock_weather_unavailable);

    companion object {
        fun fromCode(code: Int, isDay: Boolean): WeatherCondition = when (code) {
            0, 1 -> if (isDay) CLEAR_DAY else CLEAR_NIGHT
            2 -> if (isDay) PARTLY_CLOUDY_DAY else PARTLY_CLOUDY_NIGHT
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55, 61, 63, 65, 80, 81, 82 -> RAIN
            56, 57, 66, 67 -> SLEET
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> THUNDERSTORM
            else -> UNAVAILABLE
        }
    }
}
