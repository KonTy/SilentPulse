package com.silentpulse.messenger.feature.worldclock.weather

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.silentpulse.messenger.feature.worldclock.WorldClockPreferences
import com.silentpulse.messenger.feature.worldclock.WorldClockWidgetProvider
import java.util.concurrent.TimeUnit

class WorldClockWeatherWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {

    override fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val preferences = WorldClockPreferences(applicationContext)
        val ids = manager.getAppWidgetIds(ComponentName(applicationContext, WorldClockWidgetProvider::class.java))
        val targets = ids.asSequence().mapNotNull { id -> preferences.load(id)?.let { id to it.city } }
            .groupBy({ it.second }, { it.first })
        val forcedId = inputData.getInt(FORCE_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val forcedCity = targets.entries.find { forcedId in it.value }?.key
        val client = OpenMeteoClockWeather()
        val cache = ClockWeatherCache(applicationContext)
        val result = ClockWeatherRefresher(cache, client::fetch).refresh(
            targets.keys, forcedCity, isOnline(),
            isStopped = { isStopped },
            isCurrent = { city -> targets.getValue(city).any { preferences.load(it)?.city == city } },
            onChanged = { city ->
                targets.getValue(city).filter { preferences.load(it)?.city == city }
                    .forEach { WorldClockWidgetProvider.updateWidget(applicationContext, it) }
            }
        )
        return when (result) {
            WeatherRefreshResult.SUCCESS -> Result.success()
            WeatherRefreshResult.RETRY -> if (runAttemptCount < 2) Result.retry() else Result.failure()
            WeatherRefreshResult.FAILURE, WeatherRefreshResult.CANCELLED -> Result.failure()
        }
    }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            manager.activeNetworkInfo?.isConnected == true
        }
    }

    companion object {
        private const val PERIODIC_WORK = "world-clock-weather-periodic"
        private const val REFRESH_WORK = "world-clock-weather-refresh"
        private const val FORCE_WIDGET_ID = "force_widget_id"

        fun requestRefresh(context: Context, widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID) {
            val manager = WorkManager.getInstance(context)
            // Run even when offline so stale conditions become an unavailable icon.
            val periodic = PeriodicWorkRequestBuilder<WorldClockWeatherWorker>(1, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)
            val immediate = OneTimeWorkRequestBuilder<WorldClockWeatherWorker>()
                .setInputData(workDataOf(FORCE_WIDGET_ID to widgetId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniqueWork(REFRESH_WORK, ExistingWorkPolicy.REPLACE, immediate)
        }

        fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(PERIODIC_WORK)
            manager.cancelUniqueWork(REFRESH_WORK)
            ClockWeatherCache(context).clear()
        }
    }
}
