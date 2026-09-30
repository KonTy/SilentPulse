package com.silentpulse.messenger.feature.stocks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.silentpulse.messenger.R
import com.silentpulse.messenger.manager.WidgetManager
import timber.log.Timber
import java.text.DateFormat
import java.util.Date

class StockWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, it) }
        if (ids.isNotEmpty()) StockWidgetWorker.requestRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: Bundle
    ) {
        updateWidget(context, widgetId)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val preferences = StockWidgetPreferences(context)
        ids.forEach(preferences::remove)
        StockQuoteCache(context).retain(StockWidgetWorker.activeSettings(context).values.flatMap { it.symbols }.toSet())
    }

    override fun onDisabled(context: Context) = StockWidgetWorker.cancel(context)

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        StockWidgetPreferences(context).restore(oldWidgetIds, newWidgetIds)
        onUpdate(context, AppWidgetManager.getInstance(context), newWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            WidgetManager.ACTION_STOCK_THEME_CHANGED -> updateAll(context)
            ACTION_REFRESH -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (isOwnWidget(context, id)) {
                    StockWidgetWorker.requestRefresh(context, id)
                } else Timber.w("Cannot refresh a missing stock widget")
            }
            Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val manager = AppWidgetManager.getInstance(context)
                onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, StockWidgetProvider::class.java)))
            }
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.silentpulse.messenger.REFRESH_STOCK_WIDGET"

        fun isOwnWidget(context: Context, id: Int): Boolean = id > 0 &&
            AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider ==
            ComponentName(context, StockWidgetProvider::class.java)

        fun updateAll(context: Context) {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, StockWidgetProvider::class.java))
                .forEach { updateWidget(context, it) }
        }

        @Suppress("DEPRECATION")
        fun updateWidget(context: Context, id: Int) {
            val manager = AppWidgetManager.getInstance(context)
            val settings = StockWidgetPreferences(context).load(id)
            val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val columns = settings?.let {
                StockWidgetDisplay.columns(it, width, context.resources.configuration.fontScale)
            } ?: 1
            val views = RemoteViews(context.packageName, R.layout.stock_widget)
            val theme = StockWidgetTheme.load(context, settings?.followAppTheme ?: true, settings?.dark == true)
            val color = theme.text
            views.setInt(R.id.stock_widget_root, "setBackgroundColor", StockWidgetDisplay.background(settings, theme))
            views.setTextColor(R.id.stock_status, color)
            views.setTextColor(R.id.stock_empty, color)
            views.setInt(R.id.stock_refresh, "setColorFilter", color)
            views.setInt(R.id.stock_settings, "setColorFilter", color)
            val cache = StockQuoteCache(context)
            val entries = settings?.symbols.orEmpty().map(cache::load)
            val checked = entries.map { it.attemptedAt }.filter { it > 0 }.minOrNull()
            val status = when {
                settings == null -> context.getString(R.string.stock_configure_title)
                entries.any { it.failed } -> context.getString(R.string.stock_status_unavailable)
                checked == null || entries.any { it.quote == null } -> context.getString(R.string.stock_pending)
                else -> context.getString(R.string.stock_checked,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(checked)))
            }
            views.setTextViewText(R.id.stock_status, status)
            val adapter = Intent(context, StockWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(StockWidgetService.EXTRA_COLUMNS, columns)
                data = Uri.parse("silentpulse://stocks/rows/$id/$columns/${settings?.multiline}/${settings?.charts}" +
                    "/${settings?.dark}/${settings?.dense}/${settings?.transparent}/${settings?.showCurrency}" +
                    "/${settings?.groupStyle}/${settings?.fontScalePercent}/${theme.background}/${theme.text}/${theme.separator}")
            }
            views.setRemoteAdapter(R.id.stock_list, adapter)
            views.setEmptyView(R.id.stock_list, R.id.stock_empty)
            val details = Intent(context, StockDetailActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("silentpulse://stocks/details/$id")
            }
            // A collection template must be mutable for the clicked cell's symbol to be filled in.
            views.setPendingIntentTemplate(R.id.stock_list, PendingIntent.getActivity(
                context, id, details, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            ))
            val configure = Intent(context, StockWidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("silentpulse://stocks/configure/$id")
            }
            val configureIntent = PendingIntent.getActivity(
                context, id, configure, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.stock_settings, configureIntent)
            views.setOnClickPendingIntent(R.id.stock_status, configureIntent)
            views.setOnClickPendingIntent(R.id.stock_empty, configureIntent)
            val refresh = Intent(context, StockWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("silentpulse://stocks/refresh/$id")
            }
            views.setOnClickPendingIntent(R.id.stock_refresh, PendingIntent.getBroadcast(
                context, id, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.stock_list)
        }
    }
}
