package com.silentpulse.messenger.feature.assistant

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Fetches weather data from two open-source providers (no Google, no tracking):
 *
 * 1. **Open-Meteo** — structured JSON API, geocoding + forecast
 *    - Source: https://github.com/open-meteo/open-meteo
 *    - No API key, open-source, GDPR-compliant
 *
 * 2. **wttr.in** — compact text weather, used as fallback
 *    - Source: https://github.com/chubin/wttr.in
 *    - No API key, open-source
 *
 * Uses only [HttpURLConnection] from the JDK — zero third-party HTTP libraries.
 * All network calls are whitelisted via `network_security_config.xml`; connections
 * to any other domain (Google, etc.) fail at the TLS handshake.
 *
 * All methods run on the calling thread — caller must use a background thread.
 */
object OpenMeteoService {

    private const val TAG = "OpenMeteo"
    private const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val WTTR_URL = "https://wttr.in"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch current weather + daily forecast for a location name.
     * Tries Open-Meteo first, falls back to wttr.in if that fails.
     * @param location Human-readable location, e.g. "Seattle WA"
     * @param days Number of forecast days (1-16)
     * @return [WeatherResult] with spoken-friendly text, or an error message.
     */
    fun getWeather(location: String, days: Int = 1): WeatherResult {
        // ── Try Open-Meteo first (structured JSON) ───────────────────────
        try {
            val geo = geocode(location)
            if (geo != null) {
                val forecast = fetchForecast(geo.lat, geo.lon, days)
                return WeatherResult.Success(
                    location = geo.displayName,
                    spokenSummary = forecast
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo failed for '$location', trying wttr.in", e)
        }

        // ── Fallback: wttr.in (plain text) ───────────────────────────────
        try {
            val wttrText = fetchWttr(location, days)
            return WeatherResult.Success(
                location = location,
                spokenSummary = wttrText
            )
        } catch (e: Exception) {
            Log.e(TAG, "wttr.in also failed for '$location'", e)
        }

        return WeatherResult.Error("Could not fetch weather for $location from any source.")
    }

    /**
     * Fetch weather for the device's approximate location (IP-based).
     * Uses wttr.in without a location parameter — it auto-detects from IP.
     */
    fun getWeatherHere(days: Int = 1): WeatherResult {
        return try {
            val wttrText = fetchWttr("", days)
            WeatherResult.Success(
                location = "your area",
                spokenSummary = wttrText
            )
        } catch (e: Exception) {
            Log.e(TAG, "wttr.in auto-location failed", e)
            WeatherResult.Error("Could not determine your location for weather. Try specifying a city, like: weather in Seattle.")
        }
    }

    /**
     * Fetch weather for multiple locations (e.g. corridor).
     * @return A single spoken summary covering all locations.
     */
    fun getCorridorWeather(locations: List<String>, days: Int = 1): WeatherResult {
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (loc in locations) {
            when (val result = getWeather(loc, days)) {
                is WeatherResult.Success -> results.add(result.spokenSummary)
                is WeatherResult.Error -> errors.add("$loc: ${result.message}")
            }
        }

        return if (results.isNotEmpty()) {
            WeatherResult.Success(
                location = "corridor",
                spokenSummary = results.joinToString(" ... Next, ")
            )
        } else {
            WeatherResult.Error("Could not fetch weather: ${errors.joinToString("; ")}")
        }
    }

    // ── Geocoding ─────────────────────────────────────────────────────────────

    private data class GeoResult(val lat: Double, val lon: Double, val displayName: String)

    private fun geocode(location: String): GeoResult? {
        val url = "$GEOCODE_URL?name=${URLEncoder.encode(location, "UTF-8")}&count=1&language=en&format=json"
        Log.d(TAG, "Geocoding: $url")

        val json = httpGet(url)
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        val first = results.getJSONObject(0)
        val name = first.optString("name", location)
        val admin1 = first.optString("admin1", "")
        val country = first.optString("country_code", "")
        val display = if (admin1.isNotEmpty()) "$name, $admin1" else "$name, $country"

        return GeoResult(
            lat = first.getDouble("latitude"),
            lon = first.getDouble("longitude"),
            displayName = display
        )
    }

    // ── Forecast ──────────────────────────────────────────────────────────────

    private fun fetchForecast(lat: Double, lon: Double, days: Int): String {
        val params = buildString {
            append("latitude=$lat&longitude=$lon")
            append("&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            append("&temperature_unit=fahrenheit")
            append("&wind_speed_unit=mph")
            append("&precipitation_unit=inch")
            append("&timezone=auto")
            append("&forecast_days=$days")
        }
        val url = "$FORECAST_URL?$params"
        Log.d(TAG, "Forecast: $url")

        val json = httpGet(url)
        return buildSpokenForecast(json, days)
    }

    private fun buildSpokenForecast(json: JSONObject, days: Int): String {
        val sb = StringBuilder()

        // ── Current conditions ────────────────────────────────────────────
        val current = json.optJSONObject("current")
        if (current != null) {
            val temp = current.optDouble("temperature_2m", Double.NaN)
            val humidity = current.optInt("relative_humidity_2m", -1)
            val windSpeed = current.optDouble("wind_speed_10m", Double.NaN)
            val weatherCode = current.optInt("weather_code", -1)
            val condition = weatherCodeToText(weatherCode)

            sb.append("Currently: $condition, ${temp.roundToInt()} degrees")
            if (humidity >= 0) sb.append(", humidity $humidity percent")
            if (!windSpeed.isNaN()) sb.append(", wind ${windSpeed.roundToInt()} miles per hour")
            sb.append(". ")
        }

        // ── Daily forecast ────────────────────────────────────────────────
        val daily = json.optJSONObject("daily")
        if (daily != null && days > 1) {
            val dates = daily.optJSONArray("time")
            val codes = daily.optJSONArray("weather_code")
            val maxTemps = daily.optJSONArray("temperature_2m_max")
            val minTemps = daily.optJSONArray("temperature_2m_min")
            val rainChance = daily.optJSONArray("precipitation_probability_max")

            val count = minOf(days, dates?.length() ?: 0)
            for (i in 0 until count) {
                val dateStr = dates?.optString(i) ?: continue
                val dayName = dateToDayName(dateStr, i)
                val code = codes?.optInt(i, -1) ?: -1
                val high = maxTemps?.optDouble(i, Double.NaN) ?: Double.NaN
                val low = minTemps?.optDouble(i, Double.NaN) ?: Double.NaN
                val rain = rainChance?.optInt(i, -1) ?: -1
                val condition = weatherCodeToText(code)

                sb.append("$dayName: $condition, high ${high.roundToInt()}, low ${low.roundToInt()}")
                if (rain >= 0) sb.append(", $rain percent chance of rain")
                sb.append(". ")
            }
        } else if (daily != null && days == 1) {
            // Just show today's high/low
            val maxTemps = daily.optJSONArray("temperature_2m_max")
            val minTemps = daily.optJSONArray("temperature_2m_min")
            val rainChance = daily.optJSONArray("precipitation_probability_max")
            if (maxTemps != null && maxTemps.length() > 0) {
                val high = maxTemps.optDouble(0, Double.NaN)
                val low = minTemps?.optDouble(0, Double.NaN) ?: Double.NaN
                val rain = rainChance?.optInt(0, -1) ?: -1
                sb.append("Today's high ${high.roundToInt()}, low ${low.roundToInt()}")
                if (rain >= 0) sb.append(", $rain percent chance of rain")
                sb.append(".")
            }
        }

        return sb.toString().trim()
    }

    // ── WMO Weather Code → English ───────────────────────────────────────────

    private fun weatherCodeToText(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51 -> "light drizzle"
        53 -> "moderate drizzle"
        55 -> "dense drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "moderate rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "moderate snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "light rain showers"
        81 -> "moderate rain showers"
        82 -> "violent rain showers"
        85 -> "light snow showers"
        86 -> "heavy snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "unknown conditions"
    }

    private fun dateToDayName(isoDate: String, index: Int): String {
        if (index == 0) return "Today"
        if (index == 1) return "Tomorrow"
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            val date = sdf.parse(isoDate) ?: return isoDate
            val dayFormat = SimpleDateFormat("EEEE", Locale.US)
            dayFormat.format(date)
        } catch (e: Exception) {
            isoDate
        }
    }

    // ── wttr.in fallback ────────────────────────────────────────────────────

    /**
     * Fetch weather from wttr.in in a TTS-friendly format.
     *
     * wttr.in supports a custom format string:
     *   %C = condition, %t = temp, %h = humidity, %w = wind,
     *   %m = moon phase, %P = precipitation
     *
     * We request Fahrenheit (?u) and a concise spoken-friendly format.
     */
    private fun fetchWttr(location: String, days: Int): String {
        val encodedLoc = URLEncoder.encode(location, "UTF-8")

        // For multi-day: use ?format=v2 which gives a compact text forecast
        // For single day: use custom format for TTS
        return if (days <= 1) {
            val format = URLEncoder.encode("%C, %t, humidity %h, wind %w", "UTF-8")
            val url = "$WTTR_URL/$encodedLoc?format=$format&u"
            Log.d(TAG, "wttr.in single: $url")
            val text = httpGetText(url).trim()
            "Currently: $text."
        } else {
            // Use the JSON API for multi-day from wttr.in
            val url = "$WTTR_URL/$encodedLoc?format=j1"
            Log.d(TAG, "wttr.in multi-day: $url")
            val json = httpGet(url)
            parseWttrJson(json, days)
        }
    }

    /**
     * Parse wttr.in JSON format (format=j1) for multi-day forecast.
     */
    private fun parseWttrJson(json: JSONObject, days: Int): String {
        val sb = StringBuilder()

        // Current conditions
        val currentArr = json.optJSONArray("current_condition")
        if (currentArr != null && currentArr.length() > 0) {
            val current = currentArr.getJSONObject(0)
            val tempF = current.optString("temp_F", "?")
            val humidity = current.optString("humidity", "?")
            val windMph = current.optString("windspeedMiles", "?")
            val descArr = current.optJSONArray("weatherDesc")
            val desc = descArr?.optJSONObject(0)?.optString("value", "unknown") ?: "unknown"
            sb.append("Currently: $desc, $tempF degrees, humidity $humidity percent, wind $windMph miles per hour. ")
        }

        // Daily forecast
        val weatherArr = json.optJSONArray("weather")
        if (weatherArr != null) {
            val count = minOf(days, weatherArr.length())
            for (i in 0 until count) {
                val day = weatherArr.getJSONObject(i)
                val date = day.optString("date", "")
                val maxF = day.optString("maxtempF", "?")
                val minF = day.optString("mintempF", "?")
                val hourly = day.optJSONArray("hourly")
                val desc = if (hourly != null && hourly.length() > 4) {
                    // Use midday (index 4 = noon) for the day's description
                    val noon = hourly.getJSONObject(4)
                    noon.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value", "unknown") ?: "unknown"
                } else "unknown"

                val dayName = when (i) {
                    0 -> "Today"
                    1 -> "Tomorrow"
                    else -> dateToDayName(date, i)
                }
                sb.append("$dayName: $desc, high $maxF, low $minF. ")
            }
        }

        return sb.toString().trim()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun httpGet(urlString: String): JSONObject {
        return JSONObject(httpGetText(urlString))
    }

    private fun httpGetText(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "SilentPulse/1.0 (Android; offline-first)")

        try {
            val code = conn.responseCode
            if (code != 200) {
                throw RuntimeException("HTTP $code from ${url.host}")
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val body = reader.readText()
            reader.close()
            return body
        } finally {
            conn.disconnect()
        }
    }

    // ── Result types ──────────────────────────────────────────────────────────

    sealed class WeatherResult {
        data class Success(val location: String, val spokenSummary: String) : WeatherResult()
        data class Error(val message: String) : WeatherResult()
    }
}
