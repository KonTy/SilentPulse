package com.silentpulse.messenger.feature.worldclock.weather

import com.silentpulse.messenger.common.util.CityTimeZone
import com.silentpulse.messenger.common.util.CityTimeZones
import com.silentpulse.messenger.feature.assistant.OpenMeteoService
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.io.IOException
import java.lang.reflect.Type
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

data class WeatherCoordinates(val latitude: Double, val longitude: Double)

data class ClockWeather(
    val coordinates: WeatherCoordinates,
    val weatherCode: Int,
    val isDay: Boolean,
    val observedAtMillis: Long
)

class WeatherLocationUnavailableException : Exception("No unambiguous weather location")

class WeatherResponseException(message: String = "Invalid weather response") : IOException(message)

/** Blocking, key-free requests for a selected clock city; call from a background thread. */
class OpenMeteoClockWeather(
    private val request: (String) -> String = {
        OpenMeteoService.httpGetText(it, followRedirects = false)
    }
) {

    fun fetch(city: CityTimeZone, coordinates: WeatherCoordinates? = null): ClockWeather {
        val weatherCity = weatherLocation(city) ?: throw WeatherLocationUnavailableException()
        val name = placeName(weatherCity.city)
        if (name.isEmpty() || name in nonPlaceNames ||
            weatherCity.zoneId.substringBefore('/') !in geographicRegions ||
            !CityTimeZones.isValidZoneId(weatherCity.zoneId)
        ) {
            throw WeatherLocationUnavailableException()
        }

        val location = coordinates?.validated() ?: geocode(weatherCity, name)
        val url = "${OpenMeteoService.FORECAST_URL}" +
            "?latitude=${location.latitude}&longitude=${location.longitude}" +
            "&current=weather_code,is_day&timeformat=unixtime&forecast_days=1"
        val forecast = parse(forecastAdapter, request(url))
        if (forecast.error == true) throw WeatherResponseException()
        val current = forecast.current ?: throw WeatherResponseException()
        val code = current.weatherCode
        val day = current.isDay
        val seconds = current.time
        if (code == null || code !in weatherCodes || day == null || day !in 0..1 ||
            seconds == null || seconds <= 0L || seconds > Long.MAX_VALUE / 1_000L
        ) {
            throw WeatherResponseException()
        }
        return ClockWeather(location, code, day == 1, seconds * 1_000L)
    }

    private fun geocode(city: CityTimeZone, name: String): WeatherCoordinates {
        val search = if (name == "washingtondc") "Washington" else cityNames[name] ?: city.city.trim()
        val url = "${OpenMeteoService.GEOCODE_URL}?name=${URLEncoder.encode(search, "UTF-8")}" +
            "&count=20&language=en&format=json"
        val response = parse(geocodingAdapter, request(url))
        if (response.error == true) throw WeatherResponseException()
        val matches = response.results.orEmpty().map { it ?: throw WeatherResponseException() }.filter { result ->
            result.featureCode in populatedPlaces &&
                canonicalZone(result.timezone) == canonicalZone(city.zoneId) &&
                matchesName(result, name)
        }.map { result ->
            WeatherCoordinates(
                result.latitude ?: throw WeatherResponseException(),
                result.longitude ?: throw WeatherResponseException()
            ).validated()
        }.distinct()
        // Population and API ordering cannot disambiguate two places with the same name and zone.
        return matches.singleOrNull() ?: throw WeatherLocationUnavailableException()
    }

    private fun matchesName(result: ClockGeocodingPlace, name: String): Boolean {
        val rawName = normalize(result.name.orEmpty())
        val resultName = if (rawName == "washington") rawName else placeName(result.name.orEmpty())
        if (resultName == name) return true
        return name == "washingtondc" && rawName == "washington" &&
            (result.featureCode == "PPLC" || normalize(result.admin1.orEmpty()) == "districtofcolumbia")
    }

    private fun WeatherCoordinates.validated(): WeatherCoordinates {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0
        ) {
            throw WeatherResponseException("Invalid weather coordinates")
        }
        return this
    }

    private fun <T> parse(adapter: JsonAdapter<T>, body: String): T {
        // Only parsing failures are translated; transport IO and cancellation pass through untouched.
        return try {
            adapter.fromJson(body) ?: throw WeatherResponseException()
        } catch (_: JsonDataException) {
            throw WeatherResponseException()
        } catch (_: IOException) {
            throw WeatherResponseException()
        }
    }

    companion object {
        /** The city represented by a catalog region shortcut, without changing the clock's label. */
        fun weatherLocationLabel(city: CityTimeZone): String = weatherLocation(city)?.city ?: city.city

        private fun weatherLocation(city: CityTimeZone): CityTimeZone? {
            val representative = regionAliases[normalize(city.city)] ?: return city
            return if (canonicalZone(city.zoneId) == canonicalZone(representative.zoneId)) {
                city.copy(city = representative.city)
            } else {
                null
            }
        }

        private val regionAliases = mapOf(
            "japan" to CityTimeZone("Tokyo", "Asia/Tokyo"),
            "india" to CityTimeZone("Kolkata", "Asia/Kolkata"),
            "china" to CityTimeZone("Shanghai", "Asia/Shanghai"),
            "korea" to CityTimeZone("Seoul", "Asia/Seoul"),
            "israel" to CityTimeZone("Jerusalem", "Asia/Jerusalem"),
            "newzealand" to CityTimeZone("Auckland", "Pacific/Auckland"),
            "hawaii" to CityTimeZone("Honolulu", "Pacific/Honolulu"),
            "alaska" to CityTimeZone("Anchorage", "America/Anchorage"),
            "fiji" to CityTimeZone("Suva", "Pacific/Fiji")
        )
        private val moshi = Moshi.Builder().add(StrictWeatherNumbers).build()
        private val geocodingAdapter: JsonAdapter<ClockGeocodingResponse> =
            moshi.adapter(ClockGeocodingResponse::class.java)
        private val forecastAdapter: JsonAdapter<ClockForecastResponse> =
            moshi.adapter(ClockForecastResponse::class.java)
        private val weatherCodes = setOf(
            0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67,
            71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99
        )
        private val geographicRegions = setOf(
            "Africa", "America", "Antarctica", "Arctic", "Asia",
            "Atlantic", "Australia", "Europe", "Indian", "Pacific"
        )
        private val nonPlaceNames = setOf("utc", "gmt")
        private val populatedPlaces = setOf(
            "PPL", "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLA5", "PPLC",
            "PPLG", "PPLL", "PPLR", "PPLS", "PPLX", "STLMT"
        )
        private val nameAliases = mapOf(
            "nyc" to "New York", "newyorkcity" to "New York",
            "la" to "Los Angeles", "sf" to "San Francisco",
            "dc" to "Washington DC", "washington" to "Washington DC",
            "kiev" to "Kyiv", "calcutta" to "Kolkata", "katmandu" to "Kathmandu",
            "rio" to "Rio de Janeiro", "saigon" to "Ho Chi Minh City",
            "hochiminh" to "Ho Chi Minh City", "bangalore" to "Bengaluru",
            "stlouis" to "Saint Louis", "stpetersburg" to "Saint Petersburg",
            "stjohns" to "Saint Johns"
        )
        private val nameSeparators = Regex("[^\\p{L}\\p{N}]")
        private val cityNames = (nameAliases.values + listOf("Buenos Aires"))
            .associateBy(::normalize)
        private val zoneAliases = mapOf(
            "Europe/Kiev" to "Europe/Kyiv",
            "Asia/Calcutta" to "Asia/Kolkata",
            "Asia/Katmandu" to "Asia/Kathmandu",
            "America/Buenos_Aires" to "America/Argentina/Buenos_Aires",
            "America/Catamarca" to "America/Argentina/Catamarca",
            "America/Cordoba" to "America/Argentina/Cordoba",
            "America/Jujuy" to "America/Argentina/Jujuy",
            "America/Mendoza" to "America/Argentina/Mendoza",
            "America/Montreal" to "America/Toronto",
            "America/Indianapolis" to "America/Indiana/Indianapolis",
            "America/Louisville" to "America/Kentucky/Louisville",
            "Asia/Chongqing" to "Asia/Shanghai",
            "Asia/Chungking" to "Asia/Shanghai",
            "Asia/Harbin" to "Asia/Shanghai",
            "Asia/Tel_Aviv" to "Asia/Jerusalem",
            "Asia/Rangoon" to "Asia/Yangon",
            "Asia/Saigon" to "Asia/Ho_Chi_Minh"
        )

        private fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFD)
                .lowercase(Locale.ROOT)
                .replace(nameSeparators, "")

        private fun placeName(value: String): String {
            val normalized = normalize(value)
            return nameAliases[normalized]?.let(::normalize) ?: normalized
        }

        // Explicit IANA links, not equal offsets (or even equal current DST rules).
        private fun canonicalZone(value: String?): String? = zoneAliases[value] ?: value
    }
}

/** Moshi normally coerces numeric strings, but these API fields must be JSON numbers. */
private object StrictWeatherNumbers : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (annotations.isNotEmpty() || type !in setOf(
                Int::class.javaObjectType, Long::class.javaObjectType, Double::class.javaObjectType
            )
        ) return null
        val delegate = moshi.nextAdapter<Any>(this, type, annotations)
        return object : JsonAdapter<Any>() {
            override fun fromJson(reader: JsonReader): Any? {
                if (reader.peek() != JsonReader.Token.NUMBER) throw JsonDataException("Expected number")
                return delegate.fromJson(reader)
            }

            override fun toJson(writer: JsonWriter, value: Any?) = delegate.toJson(writer, value)
        }.nullSafe()
    }
}

@JsonClass(generateAdapter = true)
internal data class ClockGeocodingResponse(
    val results: List<ClockGeocodingPlace?>? = null,
    val error: Boolean? = null
)

@JsonClass(generateAdapter = true)
internal data class ClockGeocodingPlace(
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    @Json(name = "feature_code") val featureCode: String? = null,
    val admin1: String? = null
)

@JsonClass(generateAdapter = true)
internal data class ClockForecastResponse(
    val current: ClockCurrentWeather? = null,
    val error: Boolean? = null
)

@JsonClass(generateAdapter = true)
internal data class ClockCurrentWeather(
    @Json(name = "weather_code") val weatherCode: Int? = null,
    @Json(name = "is_day") val isDay: Int? = null,
    val time: Long? = null
)
