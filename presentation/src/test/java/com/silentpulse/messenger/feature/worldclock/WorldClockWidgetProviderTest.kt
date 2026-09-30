package com.silentpulse.messenger.feature.worldclock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.DisplayMetrics
import android.util.TypedValue
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
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Calendar
import java.util.Locale

class WorldClockWidgetProviderTest {

    private val context = mock(Context::class.java)
    private val preferences = mock(SharedPreferences::class.java)
    private val manager = mock(AppWidgetManager::class.java)
    private val pendingIntent = mock(PendingIntent::class.java)
    private val refreshIntent = mock(PendingIntent::class.java)
    private val weatherStore = WeatherCacheTestStore()
    private val metrics = mock(DisplayMetrics::class.java)
    private lateinit var managers: MockedStatic<AppWidgetManager>
    private lateinit var pendingIntents: MockedStatic<PendingIntent>
    private lateinit var uris: MockedStatic<Uri>
    private lateinit var views: MockedConstruction<RemoteViews>
    private lateinit var intents: MockedConstruction<Intent>
    private lateinit var paints: MockedConstruction<Paint>
    private lateinit var dateFormats: MockedStatic<DateFormat>

    @Before
    fun setUp() {
        val resources = mock(Resources::class.java)
        val configuration = mock(Configuration::class.java).apply { locale = Locale.US }
        metrics.density = 1f
        metrics.scaledDensity = 1f
        `when`(context.resources).thenReturn(resources)
        `when`(resources.displayMetrics).thenReturn(metrics)
        `when`(resources.configuration).thenReturn(configuration)
        paints = mockConstruction(Paint::class.java) { paint, _ ->
            val fontMetrics = mock(Paint.FontMetrics::class.java).apply {
                ascent = -80f
                descent = 20f
            }
            `when`(paint.fontMetrics).thenReturn(fontMetrics)
            `when`(paint.measureText(anyString())).thenAnswer { it.getArgument<String>(0).length * 50f }
        }
        dateFormats = mockStatic(DateFormat::class.java)
        dateFormats.`when`<String> {
            DateFormat.getBestDateTimePattern(eq(Locale.US), anyString())
        }.thenAnswer { it.getArgument<String>(1) }
        dateFormats.`when`<CharSequence> {
            DateFormat.format(anyString(), any(Calendar::class.java))
        }.thenAnswer {
            if (it.getArgument<String>(0) == "Hm") "23:59"
            else if (it.getArgument<Calendar>(1).get(Calendar.HOUR_OF_DAY) < 12) "12:59 AM"
            else "12:59 PM"
        }
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
        dateFormats.close()
        paints.close()
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
        verify(seattle).setTextColor(R.id.world_clock_city, Color.WHITE)
        verify(tokyo).setTextColor(R.id.world_clock_city, Color.BLACK)
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
    fun `legacy narrow clock fits both time formats even with large system fonts`() {
        metrics.scaledDensity = 2f
        val options = options(40, 40)
        `when`(manager.getAppWidgetOptions(1)).thenReturn(options)

        WorldClockWidgetProvider.updateWidget(context, 1)

        val view = views.constructed().single()
        verify(view).setTextViewTextSize(R.id.world_clock_time, TypedValue.COMPLEX_UNIT_PX, 9.75f)
        verify(view).setTextViewTextSize(R.id.world_clock_city, TypedValue.COMPLEX_UNIT_PX, 8f)
        dateFormats.verify { DateFormat.getBestDateTimePattern(Locale.US, "hm") }
        dateFormats.verify { DateFormat.getBestDateTimePattern(Locale.US, "Hm") }
        verify(view, never()).setCharSequence(
            eq(R.id.world_clock_time), eq("setFormat12Hour"), any<CharSequence>()
        )
        verify(view, never()).setCharSequence(
            eq(R.id.world_clock_time), eq("setFormat24Hour"), any<CharSequence>()
        )
        verify(view, never()).setTextViewText(eq(R.id.world_clock_time), any<CharSequence>())
    }

    @Test
    fun `resizing legacy clock uses fresh bounds and grows again on a larger tile`() {
        val provider = mock(WorldClockWidgetProvider::class.java, CALLS_REAL_METHODS)

        provider.onAppWidgetOptionsChanged(context, manager, 1, options(40, 40))
        provider.onAppWidgetOptionsChanged(context, manager, 1, options(220, 100))

        verify(manager, never()).getAppWidgetOptions(anyInt())
        verify(views.constructed()[0])
            .setTextViewTextSize(R.id.world_clock_time, TypedValue.COMPLEX_UNIT_PX, 9.75f)
        verify(views.constructed()[1])
            .setTextViewTextSize(R.id.world_clock_time, TypedValue.COMPLEX_UNIT_PX, 28f)
    }

    @Test
    fun `legacy sizing measures widest localized time across the full day`() {
        dateFormats.`when`<CharSequence> {
            DateFormat.format(eq("hm"), any(Calendar::class.java))
        }.thenAnswer {
            val calendar = it.getArgument<Calendar>(1)
            if (calendar.get(Calendar.HOUR_OF_DAY) == 23 && calendar.get(Calendar.MINUTE) == 59) {
                "11:59 LONG DAY PERIOD"
            } else "1:00 AM"
        }

        WorldClockWidgetProvider.updateWidget(context, 1, options(40, 40))

        verify(views.constructed().single())
            .setTextViewTextSize(R.id.world_clock_time, TypedValue.COMPLEX_UNIT_PX, 39f / 10.5f)
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

    private fun options(width: Int, height: Int) = mock(Bundle::class.java).also {
        `when`(it.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)).thenReturn(width)
        `when`(it.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)).thenReturn(width)
        `when`(it.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)).thenReturn(height)
        `when`(it.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)).thenReturn(height)
    }
}
