package com.silentpulse.messenger.feature.worldclock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.silentpulse.messenger.R
import com.silentpulse.messenger.feature.worldclock.weather.ClockWeatherCache
import com.silentpulse.messenger.feature.worldclock.weather.OpenMeteoClockWeather
import com.silentpulse.messenger.feature.worldclock.weather.WeatherCondition
import com.silentpulse.messenger.feature.worldclock.weather.WorldClockWeatherWorker
import timber.log.Timber
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class WorldClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, it) }
        if (ids.isNotEmpty()) WorldClockWeatherWorker.requestRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle
    ) {
        updateWidget(context, widgetId, newOptions)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val preferences = WorldClockPreferences(context)
        ids.forEach(preferences::remove)
    }

    override fun onDisabled(context: Context) {
        WorldClockWeatherWorker.cancel(context)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        WorldClockPreferences(context).restore(oldWidgetIds, newWidgetIds)
        newWidgetIds.forEach { updateWidget(context, it) }
        if (newWidgetIds.isNotEmpty()) WorldClockWeatherWorker.requestRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WEATHER) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetInfo(id)?.provider ==
                ComponentName(context, WorldClockWidgetProvider::class.java)) {
                WorldClockWeatherWorker.requestRefresh(context, id)
            } else {
                Timber.w("Cannot refresh weather for missing world clock widget %d", id)
            }
        } else if (intent.action == Intent.ACTION_LOCALE_CHANGED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, manager.getAppWidgetIds(
                ComponentName(context, WorldClockWidgetProvider::class.java)
            ))
        }
    }

    companion object {
        private const val ACTION_REFRESH_WEATHER = "com.silentpulse.messenger.REFRESH_CLOCK_WEATHER"

        fun updateWidget(context: Context, widgetId: Int, options: Bundle? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val settings = WorldClockPreferences(context).load(widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_world_clock)
            val city = settings?.city?.let { WorldClockCityOptions.canonical(it).city }
                ?: context.getString(R.string.world_clock_choose_city)
            views.setTextViewText(R.id.world_clock_city, city)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                fitLegacyText(context, views, city, options ?: manager.getAppWidgetOptions(widgetId))
            }
            views.setViewVisibility(R.id.world_clock_time, if (settings == null) View.GONE else View.VISIBLE)
            if (settings != null) {
                // TextClock ticks inside the launcher and handles DST and 12/24-hour
                // settings without a service, alarms, or per-minute app wakeups.
                views.setString(R.id.world_clock_time, "setTimeZone", settings.city.zoneId)
            }
            val color = if (settings?.darkText == true) Color.BLACK else Color.WHITE
            views.setTextColor(R.id.world_clock_time, color)
            views.setTextColor(R.id.world_clock_city, color)

            val now = System.currentTimeMillis()
            val cached = settings?.let { ClockWeatherCache(context).load(it.city) }
            val weather = cached?.displayWeather(now)
            val condition = weather?.let { WeatherCondition.fromCode(it.weatherCode, it.isDay) }
                ?: WeatherCondition.UNAVAILABLE
            views.setViewVisibility(R.id.world_clock_weather, if (settings == null) View.GONE else View.VISIBLE)
            views.setImageViewResource(R.id.world_clock_weather, condition.icon)
            val loading = cached == null || cached.isLoading(now)
            views.setFloat(R.id.world_clock_weather, "setAlpha", if (loading) 0.6f else 1f)
            val description = when {
                loading -> context.getString(R.string.world_clock_weather_loading)
                weather != null && settings != null -> context.getString(
                    R.string.world_clock_weather_condition_place,
                    context.getString(condition.description),
                    OpenMeteoClockWeather.weatherLocationLabel(settings.city)
                )
                else -> context.getString(R.string.world_clock_weather_unavailable)
            }
            views.setContentDescription(
                R.id.world_clock_weather,
                context.getString(R.string.world_clock_weather_refresh_hint, description)
            )
            val refresh = Intent(context, WorldClockWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WEATHER
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse("silentpulse://world-clock-weather/$widgetId")
            }
            views.setOnClickPendingIntent(
                R.id.world_clock_weather,
                PendingIntent.getBroadcast(
                    context, widgetId, refresh,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val configure = Intent(context, WorldClockConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse("silentpulse://world-clock/$widgetId")
            }
            views.setOnClickPendingIntent(
                R.id.world_clock_root,
                PendingIntent.getActivity(
                    context, widgetId, configure,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            manager.updateAppWidget(widgetId, views)
        }

        @Suppress("DEPRECATION")
        private fun fitLegacyText(context: Context, views: RemoteViews, city: String, options: Bundle?) {
            // Framework TextClock only gains autosizing in API 26. On older hosts,
            // fit both formats so a later system 12/24-hour change still fits.
            val metrics = context.resources.displayMetrics
            val locale = context.resources.configuration.locale ?: Locale.getDefault()
            val width = WorldClockWidgetSizing.smallestDimension(
                options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0,
                options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0,
                110
            ) * metrics.density
            val height = WorldClockWidgetSizing.smallestDimension(
                options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0,
                options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0,
                40
            ) * metrics.density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 100f }
            val lineHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent) / 100f
            val timeSize = WorldClockWidgetSizing.fitText(
                width, height / 2f, 28f * metrics.scaledDensity,
                widestClockText(paint, locale) / 100f, lineHeight
            )
            val citySize = WorldClockWidgetSizing.fitText(
                width, height / 4f, 12f * metrics.scaledDensity,
                paint.measureText(city) / 100f, lineHeight,
                minimumWidthSize = 8f * metrics.density
            )
            views.setTextViewTextSize(R.id.world_clock_time, TypedValue.COMPLEX_UNIT_PX, timeSize)
            views.setTextViewTextSize(R.id.world_clock_city, TypedValue.COMPLEX_UNIT_PX, citySize)
        }

        private fun widestClockText(paint: Paint, locale: Locale): Float {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), locale)
            var widest = 0f
            for (skeleton in listOf("hm", "Hm")) {
                val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
                for (hour in 0..23) {
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    for (minute in 0..59) {
                        calendar.set(Calendar.MINUTE, minute)
                        widest = maxOf(widest, paint.measureText(DateFormat.format(pattern, calendar).toString()))
                    }
                }
            }
            return widest
        }
    }
}

internal object WorldClockWidgetSizing {
    fun smallestDimension(minimum: Int, maximum: Int, fallback: Int): Int =
        listOf(minimum, maximum).filter { it > 0 }.minOrNull() ?: fallback

    fun fitText(
        width: Float,
        height: Float,
        preferredSize: Float,
        widthPerPixel: Float,
        heightPerPixel: Float,
        minimumWidthSize: Float = 1f
    ): Float = minOf(
        preferredSize,
        ((width - 1f).coerceAtLeast(1f) / widthPerPixel.coerceAtLeast(0.01f))
            .coerceAtLeast(minimumWidthSize),
        (height - 1f).coerceAtLeast(1f) / heightPerPixel.coerceAtLeast(0.01f)
    ).coerceAtLeast(1f)
}
