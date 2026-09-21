package com.silentpulse.messenger.feature.worldclock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.util.CityTimeZone
import com.silentpulse.messenger.feature.worldclock.weather.ClockWeather
import com.silentpulse.messenger.feature.worldclock.weather.WeatherCacheTestStore
import com.silentpulse.messenger.feature.worldclock.weather.WeatherCoordinates
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class WorldClockWidgetProviderTest {

    private val context = mock(Context::class.java)
    private val preferences = mock(SharedPreferences::class.java)
    private val manager = mock(AppWidgetManager::class.java)
    private val pendingIntent = mock(PendingIntent::class.java)
    private val refreshIntent = mock(PendingIntent::class.java)
    private val weatherStore = WeatherCacheTestStore()
    private lateinit var managers: MockedStatic<AppWidgetManager>
    private lateinit var pendingIntents: MockedStatic<PendingIntent>
    private lateinit var uris: MockedStatic<Uri>
    private lateinit var views: MockedConstruction<RemoteViews>
    private lateinit var intents: MockedConstruction<Intent>

    @Before
    fun setUp() {
        `when`(context.packageName).thenReturn("com.silentpulse.messenger")
        `when`(context.getSharedPreferences("world_clock_widgets", Context.MODE_PRIVATE))
            .thenReturn(preferences)
        `when`(context.getSharedPreferences("world_clock_weather", Context.MODE_PRIVATE))
            .thenReturn(weatherStore.preferences)
        `when`(context.getString(anyInt())).thenReturn("Weather")
        `when`(context.getString(R.string.world_clock_choose_city)).thenReturn("Choose a city")
        `when`(context.getString(eq(R.string.world_clock_weather_refresh_hint), anyString()))
            .thenReturn("Weather. Tap to refresh weather.")
        `when`(context.getString(eq(R.string.world_clock_weather_condition_place), anyString(), anyString()))
            .thenReturn("Weather in city")
        managers = mockStatic(AppWidgetManager::class.java)
        managers.`when`<AppWidgetManager> { AppWidgetManager.getInstance(context) }.thenReturn(manager)
        pendingIntents = mockStatic(PendingIntent::class.java)
        pendingIntents.`when`<PendingIntent> {
            PendingIntent.getActivity(eq(context), anyInt(), any(Intent::class.java), anyInt())
        }.thenReturn(pendingIntent)
        pendingIntents.`when`<PendingIntent> {
            PendingIntent.getBroadcast(eq(context), anyInt(), any(Intent::class.java), anyInt())
        }.thenReturn(refreshIntent)
        uris = mockStatic(Uri::class.java)
        uris.`when`<Uri> { Uri.parse(anyString()) }.thenReturn(mock(Uri::class.java))
        views = mockConstruction(RemoteViews::class.java)
        intents = mockConstruction(Intent::class.java)
    }

    @After
    fun tearDown() {
        intents.close()
        views.close()
        uris.close()
        pendingIntents.close()
        managers.close()
    }

    @Test
    fun `each clock renders its own time zone color and configuration intent`() {
        `when`(preferences.getString("clock_1.city", null)).thenReturn("Seattle")
        `when`(preferences.getString("clock_1.zone", null)).thenReturn("America/Los_Angeles")
        `when`(preferences.getString("clock_2.city", null)).thenReturn("Tokyo")
        `when`(preferences.getString("clock_2.zone", null)).thenReturn("Asia/Tokyo")
        `when`(preferences.getBoolean("clock_2.dark", false)).thenReturn(true)

        WorldClockWidgetProvider.updateWidget(context, 1)
        WorldClockWidgetProvider.updateWidget(context, 2)

        val seattle = views.constructed()[0]
        val tokyo = views.constructed()[1]
        verify(seattle).setString(R.id.world_clock_time, "setTimeZone", "America/Los_Angeles")
        verify(tokyo).setString(R.id.world_clock_time, "setTimeZone", "Asia/Tokyo")
        verify(seattle).setTextViewText(R.id.world_clock_city, "Seattle")
        verify(tokyo).setTextViewText(R.id.world_clock_city, "Tokyo")
        verify(seattle).setTextColor(R.id.world_clock_time, Color.WHITE)
        verify(tokyo).setTextColor(R.id.world_clock_time, Color.BLACK)
        verify(manager).updateAppWidget(1, seattle)
        verify(manager).updateAppWidget(2, tokyo)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        for (index in 0..1) {
            val id = index + 1
            val refresh = intents.constructed()[index * 2]
            val configure = intents.constructed()[index * 2 + 1]
            verify(configure).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            verify(refresh).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            pendingIntents.verify { PendingIntent.getActivity(context, id, configure, flags) }
            pendingIntents.verify { PendingIntent.getBroadcast(context, id, refresh, flags) }
            uris.verify { Uri.parse("silentpulse://world-clock/$id") }
            uris.verify { Uri.parse("silentpulse://world-clock-weather/$id") }
        }
    }

    @Test
    fun `unconfigured widget asks for a city instead of showing the wrong local time`() {
        WorldClockWidgetProvider.updateWidget(context, 1)

        val view = views.constructed().single()
        verify(view).setTextViewText(R.id.world_clock_city, "Choose a city")
        verify(view).setViewVisibility(R.id.world_clock_time, View.GONE)
        verify(view).setViewVisibility(R.id.world_clock_weather, View.GONE)
        verify(view, never()).setString(eq(R.id.world_clock_time), eq("setTimeZone"), anyString())
        verify(view).setOnClickPendingIntent(R.id.world_clock_root, pendingIntent)
    }

    @Test
    fun `each clock renders its chosen city weather and refresh action`() {
        `when`(preferences.getString("clock_1.city", null)).thenReturn("Seattle")
        `when`(preferences.getString("clock_1.zone", null)).thenReturn("America/Los_Angeles")
        `when`(preferences.getString("clock_2.city", null)).thenReturn("Tokyo")
        `when`(preferences.getString("clock_2.zone", null)).thenReturn("Asia/Tokyo")
        val now = System.currentTimeMillis()
        weatherStore.cache.save(CityTimeZone("Seattle", "America/Los_Angeles"),
            ClockWeather(WeatherCoordinates(47.61, -122.33), 0, true, now), now)
        weatherStore.cache.save(CityTimeZone("Tokyo", "Asia/Tokyo"),
            ClockWeather(WeatherCoordinates(35.68, 139.69), 71, false, now), now)

        WorldClockWidgetProvider.updateWidget(context, 1)
        WorldClockWidgetProvider.updateWidget(context, 2)

        verify(views.constructed()[0]).setImageViewResource(R.id.world_clock_weather, R.drawable.ic_weather_sun)
        verify(views.constructed()[1]).setImageViewResource(R.id.world_clock_weather, R.drawable.ic_weather_snow)
        views.constructed().forEach {
            verify(it).setOnClickPendingIntent(R.id.world_clock_weather, refreshIntent)
        }
    }

    @Test
    fun `legacy Japan shortcut displays Tokyo without rewriting stored widget data`() {
        `when`(preferences.getString("clock_1.city", null)).thenReturn("Japan")
        `when`(preferences.getString("clock_1.zone", null)).thenReturn("Asia/Tokyo")
        val now = System.currentTimeMillis()
        weatherStore.cache.save(CityTimeZone("Japan", "Asia/Tokyo"),
            ClockWeather(WeatherCoordinates(35.68, 139.69), 0, false, now), now)

        WorldClockWidgetProvider.updateWidget(context, 1)

        verify(views.constructed().single()).setTextViewText(R.id.world_clock_city, "Tokyo")
        verify(context).getString(R.string.world_clock_weather_condition_place, "Weather", "Tokyo")
        verify(preferences, never()).edit()
    }
}
