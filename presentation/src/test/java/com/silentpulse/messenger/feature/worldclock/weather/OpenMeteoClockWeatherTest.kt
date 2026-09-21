package com.silentpulse.messenger.feature.worldclock.weather

import com.silentpulse.messenger.common.util.CityTimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.CancellationException

class OpenMeteoClockWeatherTest {

    private val seattle = CityTimeZone("Seattle", "America/Los_Angeles")
    private val seattleCoordinates = WeatherCoordinates(47.60621, -122.33207)
    private val current = """{"current":{"weather_code":3,"is_day":1,"time":1750000200}}"""
    private val regionShortcuts = listOf(
        Triple("Japan", "Tokyo", "Asia/Tokyo"),
        Triple("India", "Kolkata", "Asia/Kolkata"),
        Triple("China", "Shanghai", "Asia/Shanghai"),
        Triple("Korea", "Seoul", "Asia/Seoul"),
        Triple("Israel", "Jerusalem", "Asia/Jerusalem"),
        Triple("New Zealand", "Auckland", "Pacific/Auckland"),
        Triple("Hawaii", "Honolulu", "Pacific/Honolulu"),
        Triple("Alaska", "Anchorage", "America/Anchorage"),
        Triple("Fiji", "Suva", "Pacific/Fiji")
    )

    @Test
    fun `city and full zone must both match even if the first result has a larger population`() {
        val fixture = Fixture(geocoding(
            place("Seattle", "America/New_York", 41.0, -73.0, extra = ""","population":90000000"""),
            place("Los Angeles", "America/Los_Angeles", 34.05, -118.24),
            place("Seattle", "America/Los_Angeles", 47.60621, -122.33207)
        ), current)

        assertEquals(ClockWeather(seattleCoordinates, 3, true, 1750000200000L), fixture.client.fetch(seattle))
        assertEquals("Seattle", query(fixture.urls.first())["name"])
        assertEquals("47.60621", query(fixture.urls.last())["latitude"])
        assertEquals("-122.33207", query(fixture.urls.last())["longitude"])
    }

    @Test
    fun `same name in a different country uses the selected zone not API order`() {
        val fixture = Fixture(geocoding(
            place("London", "America/Toronto", 42.98, -81.23),
            place("London", "Europe/London", 51.51, -0.13)
        ), current)
        assertEquals(
            WeatherCoordinates(51.51, -0.13),
            fixture.client.fetch(CityTimeZone("London", "Europe/London")).coordinates
        )
    }

    @Test
    fun `a zone city is never substituted for another selected city in that zone`() {
        val fixture = Fixture(geocoding(place("Los Angeles", "America/Los_Angeles")), current)
        expect<WeatherLocationUnavailableException> { fixture.client.fetch(seattle) }
        assertEquals(1, fixture.urls.size)
    }

    @Test
    fun `equal offsets and even matching seasonal rules do not identify a zone`() {
        val fixture = Fixture(geocoding(place("Seattle", "America/Vancouver")), current)
        expect<WeatherLocationUnavailableException> { fixture.client.fetch(seattle) }
        assertEquals(1, fixture.urls.size)
    }

    @Test
    fun `explicit IANA links match both directions`() {
        val aliases = listOf(
            Triple("Kyiv", "Europe/Kiev", "Europe/Kyiv"),
            Triple("Kolkata", "Asia/Calcutta", "Asia/Kolkata"),
            Triple("Kathmandu", "Asia/Katmandu", "Asia/Kathmandu"),
            Triple("Buenos Aires", "America/Buenos_Aires", "America/Argentina/Buenos_Aires"),
            Triple("Montreal", "America/Montreal", "America/Toronto")
        )
        aliases.forEach { (name, oldZone, newZone) ->
            listOf(oldZone to newZone, newZone to oldZone).forEach { (selected, returned) ->
                val fixture = Fixture(geocoding(place(name, returned)), current)
                assertEquals(3, fixture.client.fetch(CityTimeZone(name, selected)).weatherCode)
            }
        }
    }

    @Test
    fun `common city abbreviations and historical spellings use meaningful city queries`() {
        val aliases = listOf(
            listOf("NYC", "New York", "New York City", "America/New_York"),
            listOf("LA", "Los Angeles", "Los Angeles", "America/Los_Angeles"),
            listOf("SF", "San Francisco", "San Francisco", "America/Los_Angeles"),
            listOf("DC", "Washington", "Washington D.C.", "America/New_York"),
            listOf("Kiev", "Kyiv", "Kyiv", "Europe/Kiev"),
            listOf("Calcutta", "Kolkata", "Kolkata", "Asia/Calcutta"),
            listOf("Katmandu", "Kathmandu", "Kathmandu", "Asia/Katmandu"),
            listOf("BuenosAires", "Buenos Aires", "Buenos Aires", "America/Buenos_Aires")
        )
        aliases.forEach { (label, search, returned, zone) ->
            val fixture = Fixture(geocoding(place(returned, zone)), current)
            fixture.client.fetch(CityTimeZone(label, zone))
            assertEquals(label, search, query(fixture.urls.first())["name"])
        }
    }

    @Test
    fun `DC does not select another Washington in the same zone`() {
        listOf("DC", "Washington", "Washington DC").forEach { label ->
            val fixture = Fixture(geocoding(
                place("Washington", "America/New_York", 40.17, -80.25, "PPLA2"),
                place("Washington D.C.", "America/New_York", 38.90, -77.04, "PPLC")
            ), current)
            assertEquals(
                WeatherCoordinates(38.90, -77.04),
                fixture.client.fetch(CityTimeZone(label, "America/New_York")).coordinates
            )
        }
        val noCapital = Fixture(geocoding(place("Washington", "America/New_York")), current)
        expect<WeatherLocationUnavailableException> {
            noCapital.client.fetch(CityTimeZone("DC", "America/New_York"))
        }
    }

    @Test
    fun `DC accepts a capital called Washington without punctuation`() {
        val fixture = Fixture(geocoding(place("Washington", "America/New_York", feature = "PPLC")), current)
        assertEquals(3, fixture.client.fetch(CityTimeZone("DC", "America/New_York")).weatherCode)
    }

    @Test
    fun `place names normalize accents punctuation spacing and locale independently`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val fixture = Fixture(geocoding(place("São Paulo", "America/Sao_Paulo")), current)
            fixture.client.fetch(CityTimeZone("SAO_PAULO", "America/Sao_Paulo"))
            assertEquals(2, fixture.urls.size)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `populated places are preferred over administrative areas and landmarks`() {
        val fixture = Fixture(geocoding(
            place("Seattle", "America/Los_Angeles", 10.0, 10.0, "ADM1"),
            place("Seattle", "America/Los_Angeles", 20.0, 20.0, "AIRP"),
            place("Seattle", "America/Los_Angeles", 47.60621, -122.33207, "PPLA2")
        ), current)
        assertEquals(seattleCoordinates, fixture.client.fetch(seattle).coordinates)
    }

    @Test
    fun `ambiguous populated places fail without selecting by population or ordering`() {
        val fixture = Fixture(geocoding(
            place("Seattle", "America/Los_Angeles", 40.0, -120.0, extra = ""","population":10000000"""),
            place("Seattle", "America/Los_Angeles", 47.60621, -122.33207, extra = ""","population":100""")
        ), current)
        expect<WeatherLocationUnavailableException> { fixture.client.fetch(seattle) }
        assertEquals(1, fixture.urls.size)
    }

    @Test
    fun `duplicate records at the same coordinates are not ambiguous`() {
        val duplicate = place("Seattle", "America/Los_Angeles", 47.60621, -122.33207)
        val fixture = Fixture(geocoding(duplicate, duplicate), current)
        assertEquals(seattleCoordinates, fixture.client.fetch(seattle).coordinates)
    }

    @Test
    fun `missing or unmatched locations never make a forecast request`() {
        listOf(
            "{}",
            """{"results":[]}""",
            """{"results":null}""",
            geocoding(place("Seattle", "America/New_York")),
            geocoding(place("Seattle", "America/Los_Angeles", feature = "PCLI")),
            geocoding(place("Seattle", "America/Los_Angeles", feature = "PPLH")),
            geocoding(place("Seattle", "America/Los_Angeles").replace("America/Los_Angeles", "")),
            geocoding("""{"name":"Seattle","latitude":47.6,"longitude":-122.3,"feature_code":"PPL"}"""),
            geocoding("""{"name":"Seattle","latitude":47.6,"longitude":-122.3,"timezone":"America/Los_Angeles"}""")
        ).forEach { body ->
            val fixture = Fixture(body, current)
            expect<WeatherLocationUnavailableException> { fixture.client.fetch(seattle) }
            assertEquals(body, 1, fixture.urls.size)
        }
    }

    @Test
    fun `catalog region shortcuts use the named representative without changing the clock label`() {
        regionShortcuts.forEach { (label, representative, zone) ->
            val city = CityTimeZone(label, zone)
            val fixture = Fixture(geocoding(
                place(label, zone, 1.0, 1.0, "PCLI"),
                place("Another city", zone, 2.0, 2.0, "PPLC"),
                place(representative, zone, 47.60621, -122.33207)
            ), current)

            assertEquals(representative, OpenMeteoClockWeather.weatherLocationLabel(city))
            assertEquals(seattleCoordinates, fixture.client.fetch(city).coordinates)
            assertEquals(representative, query(fixture.urls.first())["name"])
            assertEquals(label, city.city)
            assertEquals(zone, city.zoneId)
        }
    }

    @Test
    fun `catalog region shortcuts must match their selected canonical zone even with cached coordinates`() {
        regionShortcuts.forEach { (label, _, zone) ->
            val wrongZone = if (zone == "Asia/Tokyo") "Asia/Seoul" else "Asia/Tokyo"
            val city = CityTimeZone(label, wrongZone)
            val fixture = Fixture()
            expect<WeatherLocationUnavailableException> { fixture.client.fetch(city) }
            expect<WeatherLocationUnavailableException> { fixture.client.fetch(city, seattleCoordinates) }
            assertEquals(label, OpenMeteoClockWeather.weatherLocationLabel(city))
            assertTrue(fixture.urls.isEmpty())
        }
    }

    @Test
    fun `region shortcut resolution accepts explicit IANA links and normalized labels`() {
        listOf(
            Triple(" INDIA ", "Kolkata", "Asia/Calcutta"),
            Triple("China", "Shanghai", "Asia/Chongqing"),
            Triple("China", "Shanghai", "Asia/Chungking"),
            Triple("China", "Shanghai", "Asia/Harbin"),
            Triple("Israel", "Jerusalem", "Asia/Tel_Aviv"),
            Triple("NewZealand", "Auckland", "Pacific/Auckland"),
            Triple("NEW_ZEALAND", "Auckland", "Pacific/Auckland")
        ).forEach { (label, representative, selectedZone) ->
            val returnedZone = regionShortcuts.single { it.second == representative }.third
            val city = CityTimeZone(label, selectedZone)
            val fixture = Fixture(geocoding(place(representative, returnedZone)), current)
            assertEquals(representative, OpenMeteoClockWeather.weatherLocationLabel(city))
            assertEquals(3, fixture.client.fetch(city).weatherCode)
            assertEquals(representative, query(fixture.urls.first())["name"])
        }
    }

    @Test
    fun `region shortcut geocoding still rejects mismatched names zones and ambiguity`() {
        val japan = CityTimeZone("Japan", "Asia/Tokyo")
        listOf(
            geocoding(place("Osaka", "Asia/Tokyo")),
            geocoding(place("Tokyo", "Asia/Seoul")),
            geocoding(place("Japan", "Asia/Tokyo", feature = "PCLI")),
            geocoding(
                place("Tokyo", "Asia/Tokyo", 35.6895, 139.69171),
                place("Tokyo", "Asia/Tokyo", 36.0, 140.0)
            )
        ).forEach { response ->
            val fixture = Fixture(response, current)
            expect<WeatherLocationUnavailableException> { fixture.client.fetch(japan) }
            assertEquals("Tokyo", query(fixture.urls.single())["name"])
        }
    }

    @Test
    fun `valid region shortcuts can reuse validated cached coordinates`() {
        val city = CityTimeZone("Japan", "Asia/Tokyo")
        val tokyo = WeatherCoordinates(35.6895, 139.69171)
        val fixture = Fixture(current)
        assertEquals(tokyo, fixture.client.fetch(city, tokyo).coordinates)
        assertEquals("api.open-meteo.com", URI(fixture.urls.single()).host)
        assertEquals("35.6895", query(fixture.urls.single())["latitude"])
        expect<WeatherResponseException> {
            Fixture().client.fetch(city, WeatherCoordinates(Double.NaN, 139.69171))
        }
    }

    @Test
    fun `weather location labels leave ordinary cities and unavailable selections unchanged`() {
        listOf(
            seattle,
            CityTimeZone("Tokyo", "Asia/Tokyo"),
            CityTimeZone("NYC", "America/New_York"),
            CityTimeZone("Kyiv", "Europe/Kiev"),
            CityTimeZone("UTC", "UTC"),
            CityTimeZone("GMT", "GMT"),
            CityTimeZone("France", "Europe/Paris")
        ).forEach { city ->
            assertEquals(city.city, OpenMeteoClockWeather.weatherLocationLabel(city))
        }
    }

    @Test
    fun `arbitrary regions do not fall back to capitals or their zone city`() {
        listOf(
            Triple("France", "Paris", "Europe/Paris"),
            Triple("Brazil", "Sao Paulo", "America/Sao_Paulo"),
            Triple("California", "Los Angeles", "America/Los_Angeles")
        ).forEach { (region, otherCity, zone) ->
            val fixture = Fixture(geocoding(
                place(region, zone, feature = "ADM1"),
                place(region, zone, feature = "PCLI"),
                place(otherCity, zone)
            ), current)
            expect<WeatherLocationUnavailableException> {
                fixture.client.fetch(CityTimeZone(region, zone))
            }
            assertEquals(region, query(fixture.urls.single())["name"])
        }
    }

    @Test
    fun `UTC GMT and invalid selections do not use the network even with cached coordinates`() {
        val places = listOf(
            CityTimeZone("UTC", "UTC"),
            CityTimeZone("UTC", "Etc/UTC"),
            CityTimeZone("GMT", "GMT"),
            CityTimeZone("GMT", "Etc/GMT"),
            CityTimeZone("UTC", "Asia/Tokyo"),
            CityTimeZone("GMT", "Europe/London"),
            CityTimeZone("", "America/Los_Angeles"),
            CityTimeZone("Seattle", "America/Not_A_City")
        )
        val fixture = Fixture()
        places.forEach { city ->
            expect<WeatherLocationUnavailableException> { fixture.client.fetch(city) }
            expect<WeatherLocationUnavailableException> { fixture.client.fetch(city, seattleCoordinates) }
        }
        assertTrue(fixture.urls.isEmpty())
    }

    @Test
    fun `cached coordinates skip geocoding and preserve the real observation time`() {
        val fixture = Fixture("""{"current":{"weather_code":0,"is_day":0,"time":1750000200}}""")
        assertEquals(
            ClockWeather(seattleCoordinates, 0, false, 1750000200000L),
            fixture.client.fetch(seattle, seattleCoordinates)
        )
        assertEquals(1, fixture.urls.size)
        assertEquals("api.open-meteo.com", URI(fixture.urls.single()).host)
    }

    @Test
    fun `invalid cached coordinates fail without network access`() {
        val fixture = Fixture()
        listOf(
            WeatherCoordinates(Double.NaN, 0.0),
            WeatherCoordinates(0.0, Double.NaN),
            WeatherCoordinates(Double.POSITIVE_INFINITY, 0.0),
            WeatherCoordinates(0.0, Double.NEGATIVE_INFINITY),
            WeatherCoordinates(-90.01, 0.0),
            WeatherCoordinates(90.01, 0.0),
            WeatherCoordinates(0.0, -180.01),
            WeatherCoordinates(0.0, 180.01)
        ).forEach { coordinates ->
            expect<WeatherResponseException> { fixture.client.fetch(seattle, coordinates) }
        }
        assertTrue(fixture.urls.isEmpty())
    }

    @Test
    fun `coordinate boundaries and real zero coordinates are valid without defaults`() {
        listOf(
            WeatherCoordinates(-90.0, -180.0),
            WeatherCoordinates(90.0, 180.0),
            WeatherCoordinates(0.0, 0.0)
        ).forEach { coordinates ->
            val fixture = Fixture(current)
            assertEquals(coordinates, fixture.client.fetch(seattle, coordinates).coordinates)
        }
    }

    @Test
    fun `missing invalid or nonfinite geocoded coordinates fail instead of defaulting`() {
        listOf(
            place("Seattle", "America/Los_Angeles", 91.0, 0.0),
            place("Seattle", "America/Los_Angeles", 0.0, 181.0),
            place("Seattle", "America/Los_Angeles").replace("\"latitude\":47.6,", ""),
            place("Seattle", "America/Los_Angeles").replace("\"longitude\":-122.3,", ""),
            place("Seattle", "America/Los_Angeles").replace("\"latitude\":47.6", "\"latitude\":null"),
            place("Seattle", "America/Los_Angeles").replace("\"latitude\":47.6", "\"latitude\":\"47.6\""),
            place("Seattle", "America/Los_Angeles").replace("\"latitude\":47.6", "\"latitude\":1e400")
        ).forEach { body ->
            val fixture = Fixture(geocoding(body), current)
            expect<WeatherResponseException> { fixture.client.fetch(seattle) }
            assertEquals(1, fixture.urls.size)
        }
    }

    @Test
    fun `malformed JSON and API errors become response errors for both endpoints`() {
        listOf(
            "", "null", "{", "[]", """{"error":true,"reason":"bad request"}""",
            """{"current":[],"results":{}}""",
            """{"results":[null],"current":true}"""
        ).forEach { body ->
            expect<WeatherResponseException> { Fixture(body).client.fetch(seattle) }
            expect<WeatherResponseException> { Fixture(body).client.fetch(seattle, seattleCoordinates) }
        }
    }

    @Test
    fun `all documented WMO weather codes are accepted`() {
        val codes = listOf(
            0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67,
            71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99
        )
        codes.forEach { code ->
            val fixture = Fixture("""{"current":{"weather_code":$code,"is_day":1,"time":1750000200}}""")
            assertEquals(code, fixture.client.fetch(seattle, seattleCoordinates).weatherCode)
        }
    }

    @Test
    fun `missing unknown invalid or fractional weather codes cannot become sunny`() {
        listOf(
            """{"current":{"is_day":1,"time":1750000200}}""",
            """{"current":null}""",
            "{}"
        ).forEach { body ->
            expect<WeatherResponseException> { Fixture(body).client.fetch(seattle, seattleCoordinates) }
        }
        listOf("null", "-1", "4", "49", "100", "999", "1.5", "true", "\"sunny\"", "\"0\"", "[]").forEach { code ->
            val fixture = Fixture("""{"current":{"weather_code":$code,"is_day":1,"time":1750000200}}""")
            expect<WeatherResponseException> { fixture.client.fetch(seattle, seattleCoordinates) }
        }
    }

    @Test
    fun `is day must be present and zero or one`() {
        expect<WeatherResponseException> {
            Fixture("""{"current":{"weather_code":3,"time":1750000200}}""")
                .client.fetch(seattle, seattleCoordinates)
        }
        listOf("null", "-1", "2", "0.5", "true", "\"day\"", "\"1\"", "{}").forEach { day ->
            val fixture = Fixture("""{"current":{"weather_code":3,"is_day":$day,"time":1750000200}}""")
            expect<WeatherResponseException> { fixture.client.fetch(seattle, seattleCoordinates) }
        }
    }

    @Test
    fun `observation time must be a positive integral epoch and cannot overflow milliseconds`() {
        expect<WeatherResponseException> {
            Fixture("""{"current":{"weather_code":3,"is_day":1}}""")
                .client.fetch(seattle, seattleCoordinates)
        }
        listOf("null", "0", "-1", "1.5", "9223372036854776", "1e50", "\"2026-09-20T12:00\"", "\"1750000200\"", "true").forEach { time ->
            val fixture = Fixture("""{"current":{"weather_code":3,"is_day":1,"time":$time}}""")
            expect<WeatherResponseException> { fixture.client.fetch(seattle, seattleCoordinates) }
        }
    }

    @Test
    fun `network failures and cancellation propagate unchanged`() {
        listOf(IOException("offline"), CancellationException("cancelled")).forEach { failure ->
            val client = OpenMeteoClockWeather { throw failure }
            assertSame(failure, expect<Exception> { client.fetch(seattle) })
            assertSame(failure, expect<Exception> { client.fetch(seattle, seattleCoordinates) })
            val failingForecast = OpenMeteoClockWeather { url ->
                if (URI(url).host == "geocoding-api.open-meteo.com") {
                    geocoding(place("Seattle", "America/Los_Angeles"))
                } else {
                    throw failure
                }
            }
            assertSame(failure, expect<Exception> { failingForecast.fetch(seattle) })
        }
    }

    @Test
    fun `only approved HTTPS endpoints and condition fields are requested without keys or temperature`() {
        val fixture = Fixture(geocoding(place("Seattle", "America/Los_Angeles")), current)
        fixture.client.fetch(seattle)
        val geocode = URI(fixture.urls[0])
        val forecast = URI(fixture.urls[1])
        assertEquals("https", geocode.scheme)
        assertEquals("https", forecast.scheme)
        assertEquals("geocoding-api.open-meteo.com", geocode.host)
        assertEquals("api.open-meteo.com", forecast.host)
        assertEquals("/v1/search", geocode.path)
        assertEquals("/v1/forecast", forecast.path)
        assertEquals(
            mapOf("name" to "Seattle", "count" to "20", "language" to "en", "format" to "json"),
            query(fixture.urls[0])
        )
        assertEquals(
            mapOf(
                "latitude" to "47.6", "longitude" to "-122.3", "current" to "weather_code,is_day",
                "timeformat" to "unixtime", "forecast_days" to "1"
            ),
            query(fixture.urls[1])
        )
        fixture.urls.forEach { url ->
            listOf("temperature", "wttr", "google", "apikey", "api_key", "ip_address").forEach {
                assertFalse(url, url.contains(it))
            }
        }
    }

    @Test
    fun `city names are encoded as data and cannot inject parameters or endpoints`() {
        val name = "Seattle&count=1?https://example.com"
        val fixture = Fixture(geocoding(place(name, "America/Los_Angeles")), current)
        fixture.client.fetch(CityTimeZone(name, "America/Los_Angeles"))
        val parameters = query(fixture.urls.first())
        assertEquals(name, parameters["name"])
        assertEquals("20", parameters["count"])
        assertEquals(4, parameters.size)
        assertEquals("geocoding-api.open-meteo.com", URI(fixture.urls.first()).host)
    }

    private class Fixture(vararg responses: String) {
        val urls = mutableListOf<String>()
        val client = OpenMeteoClockWeather { url ->
            urls.add(url)
            check(urls.size <= responses.size) { "Unexpected request" }
            responses[urls.lastIndex]
        }
    }

    private fun place(
        name: String,
        zone: String,
        latitude: Double = 47.6,
        longitude: Double = -122.3,
        feature: String = "PPL",
        extra: String = ""
    ): String = """{"name":"$name","timezone":"$zone","latitude":$latitude,"longitude":$longitude,"feature_code":"$feature"$extra}"""

    private fun geocoding(vararg places: String): String = """{"results":[${places.joinToString(",")}]}"""

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val (key, value) = it.split('=', limit = 2)
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${failure::class.java.simpleName}", failure)
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("Unreachable")
    }
}
