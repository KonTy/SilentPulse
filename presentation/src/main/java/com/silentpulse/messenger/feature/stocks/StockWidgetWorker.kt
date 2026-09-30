package com.silentpulse.messenger.feature.stocks

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.silentpulse.messenger.feature.stocks.data.YahooStockClient
import java.util.concurrent.TimeUnit

class StockWidgetWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = synchronized(refreshLock) {
        if (isStopped) return@synchronized Result.failure()
        val context = applicationContext
        val settings = activeSettings(context)
        val intervals = intervalsFor(settings.values)
        val forced = settings[inputData.getInt(FORCE_ID, -1)]?.symbols.orEmpty().toSet()
        val cache = StockQuoteCache(context)
        cache.retain(intervals.keys)
        StockQuoteRefresher(cache, YahooStockClient()::fetch).refresh(
            intervals, forced, isOnline(),
            isStopped = { isStopped },
            isCurrent = { symbol -> activeSettings(context).values.any { symbol in it.symbols } },
            onChanged = { StockWidgetProvider.updateAll(context) }
        )
        cache.retain(activeSettings(context).values.flatMap { it.symbols }.toSet())
        StockWidgetProvider.updateAll(context)
        Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            manager.activeNetworkInfo?.isConnected == true
        }
    }

    companion object {
        private const val PERIODIC_WORK = "stock-widget-periodic"
        private const val REFRESH_WORK = "stock-widget-refresh"
        private const val FORCE_ID = "force_widget_id"
        private val refreshLock = Any()

        internal fun intervalsFor(settings: Collection<StockWidgetSettings>): Map<String, Long> =
            settings.flatMap { setting ->
                setting.symbols.map { it to TimeUnit.MINUTES.toMillis(setting.refreshMinutes.toLong()) }
            }.groupBy({ it.first }, { it.second }).mapValues { it.value.minOrNull()!! }

        fun activeSettings(context: Context): Map<Int, StockWidgetSettings> {
            val manager = AppWidgetManager.getInstance(context)
            val prefs = StockWidgetPreferences(context)
            return manager.getAppWidgetIds(ComponentName(context, StockWidgetProvider::class.java))
                .asSequence().mapNotNull { id -> prefs.load(id)?.let { id to it } }.toMap()
        }

        fun requestRefresh(context: Context, widgetId: Int = -1) {
            val work = WorkManager.getInstance(context)
            work.enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StockWidgetWorker>(15, TimeUnit.MINUTES)
                    .setInitialDelay(15, TimeUnit.MINUTES).build()
            )
            work.enqueueUniqueWork(
                REFRESH_WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<StockWidgetWorker>()
                    .setInputData(workDataOf(FORCE_ID to widgetId)).build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC_WORK)
                cancelUniqueWork(REFRESH_WORK)
            }
            StockQuoteCache(context).retain(emptySet())
        }
    }
}
