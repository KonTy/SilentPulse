package com.silentpulse.messenger.feature.stocks

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.silentpulse.messenger.R
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class StockWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = StockRows(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        intent.getIntExtra(EXTRA_COLUMNS, 1).coerceIn(1, 2)
    )

    companion object {
        const val EXTRA_COLUMNS = "columns"
    }
}

internal class StockRows(
    private val context: Context,
    widgetId: Int,
    private val columns: Int,
    private val loadSettings: () -> StockWidgetSettings? = {
        if (StockWidgetProvider.isOwnWidget(context, widgetId)) StockWidgetPreferences(context).load(widgetId) else null
    },
    private val loadQuote: (String) -> CachedStockQuote = StockQuoteCache(context)::load,
    private val loadTheme: (StockWidgetSettings) -> StockThemeColors = {
        StockWidgetTheme.load(context, it.followAppTheme, it.dark)
    }
) : RemoteViewsService.RemoteViewsFactory {
    private var settings: StockWidgetSettings? = null
    private var rows: List<StockDisplayRow> = emptyList()
    private var quotes: Map<String, CachedStockQuote> = emptyMap()
    private lateinit var theme: StockThemeColors

    override fun onCreate() = onDataSetChanged()

    override fun onDataSetChanged() {
        settings = loadSettings()
        quotes = settings?.symbols.orEmpty().associateWith(loadQuote)
        rows = settings?.let {
            theme = loadTheme(it)
            StockWidgetRows.build(it, columns)
        }.orEmpty()
    }

    override fun getCount() = rows.size
    override fun getViewTypeCount() = 3
    override fun hasStableIds() = false
    override fun getItemId(position: Int) = position.toLong()
    override fun getLoadingView(): RemoteViews = RemoteViews(context.packageName, R.layout.stock_widget_loading)
    override fun onDestroy() { rows = emptyList(); quotes = emptyMap() }

    override fun getViewAt(position: Int): RemoteViews? {
        val setting = settings ?: return null
        val row = rows.getOrNull(position) ?: return null
        val separator = row.separator || (setting.groupStyle == StockGroupStyle.LINES && position > 0)
        val content = if (row.groupName != null) {
            RemoteViews(context.packageName, R.layout.stock_widget_group_header).apply {
                setTextViewText(R.id.stock_group_title, row.groupName)
                setTextColor(R.id.stock_group_title, theme.text)
                setViewVisibility(R.id.stock_group_rule, if (separator) View.VISIBLE else View.GONE)
                setInt(R.id.stock_group_rule, "setBackgroundColor", theme.secondaryText)
            }
        } else {
            RemoteViews(context.packageName,
                if (setting.multiline) R.layout.stock_widget_row else R.layout.stock_widget_dense_row).apply {
                removeAllViews(R.id.stock_cell_left)
                removeAllViews(R.id.stock_cell_right)
                val left = row.symbols[0]
                addView(R.id.stock_cell_left, stockView(left, quotes.getValue(left), setting))
                val right = row.symbols.getOrNull(1)
                setViewVisibility(R.id.stock_cell_right, when {
                    columns != 2 -> View.GONE
                    right == null -> View.INVISIBLE
                    else -> View.VISIBLE
                })
                setViewVisibility(R.id.stock_column_gap, if (columns == 2) View.VISIBLE else View.GONE)
                if (right != null) {
                    addView(R.id.stock_cell_right, stockView(right, quotes.getValue(right), setting))
                }
            }
        }
        val groups = StockWatchlist.effectiveGroups(setting)
        val collectionRow = if (groups.size == 1 && groups[0].name.isEmpty()) content else
            RemoteViews(context.packageName, R.layout.stock_widget_group_row).apply {
            removeAllViews(R.id.stock_group_content)
            addView(R.id.stock_group_content, content)
            val border = setting.groupStyle == StockGroupStyle.BORDERS
            val top = row.groupStart && (border || (row.groupName == null && separator))
            val visible = mapOf(
                R.id.stock_group_top to top,
                R.id.stock_group_left to border,
                R.id.stock_group_right to border,
                R.id.stock_group_bottom to (border && row.groupEnd)
            )
            visible.forEach { (id, show) ->
                setViewVisibility(id, if (show) View.VISIBLE else View.GONE)
                setInt(id, "setBackgroundColor", theme.secondaryText)
            }
        }
        // Bind child targets on the returned collection row, even when group wrappers are present.
        row.symbols.firstOrNull()?.let {
            collectionRow.setOnClickFillInIntent(R.id.stock_cell_left, detailsIntent(it))
        }
        row.symbols.getOrNull(1)?.let {
            collectionRow.setOnClickFillInIntent(R.id.stock_cell_right, detailsIntent(it))
        }
        return collectionRow
    }

    private fun detailsIntent(symbol: String) = Intent().apply {
        putExtra(StockDetailActivity.EXTRA_SYMBOL, symbol)
    }

    private fun stockView(symbol: String, entry: CachedStockQuote, setting: StockWidgetSettings): RemoteViews {
        val quote = entry.quote
        val views = RemoteViews(context.packageName, when {
            setting.multiline -> R.layout.stock_widget_card
            setting.dense -> R.layout.stock_widget_dense
            else -> R.layout.stock_widget_compact
        })
        val textColor = theme.text
        val changeColor = StockWidgetDisplay.changeColor(quote?.change, theme.dark, theme.text)
        val now = System.currentTimeMillis()
        val stale = entry.isStale(now, TimeUnit.MINUTES.toMillis(setting.refreshMinutes.toLong()))
        val price = quote?.let {
            val amount = StockWidgetDisplay.number(it.price)
            if (setting.showCurrency) "${it.currency} $amount" else amount
        }
            ?: context.getString(if (entry.failed) R.string.stock_unavailable else R.string.stock_pending)
        val change = quote?.change?.let(StockWidgetDisplay::signed)
        val percent = quote?.changePercent?.let { "${StockWidgetDisplay.signed(it)}%" }
        val changes = if (change != null && percent != null) "$change ($percent)"
            else context.getString(R.string.stock_change_unavailable)
        views.setInt(R.id.stock_item, "setBackgroundColor",
            if (setting.dense) 0 else StockWidgetDisplay.background(setting, theme))
        views.setTextViewText(R.id.stock_symbol, symbol)
        views.setTextViewText(R.id.stock_price, price)
        views.setTextColor(R.id.stock_symbol, textColor)
        views.setTextColor(R.id.stock_price, textColor)
        views.setTextColor(R.id.stock_change, changeColor)
        val symbolSize = when {
            setting.multiline -> 16f
            setting.dense -> 14f
            else -> 12f
        }
        val valueSize = when {
            setting.multiline -> 12f
            setting.dense -> 14f
            else -> 11f
        }
        views.setTextViewTextSize(R.id.stock_symbol, TypedValue.COMPLEX_UNIT_SP, StockWidgetDisplay.fontSize(setting, symbolSize))
        views.setTextViewTextSize(R.id.stock_price, TypedValue.COMPLEX_UNIT_SP, StockWidgetDisplay.fontSize(setting, valueSize))
        views.setTextViewTextSize(R.id.stock_change, TypedValue.COMPLEX_UNIT_SP, StockWidgetDisplay.fontSize(setting, valueSize))
        views.setTextViewText(R.id.stock_change, when {
            setting.multiline -> if (change != null && percent != null) "$percent\n$change" else changes
            setting.dense -> quote?.changePercent?.let { StockWidgetDisplay.densePercent(it) }
                ?: context.getString(R.string.stock_dense_unknown)
            else -> changes
        })
        val timestamp = quote?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.marketTimeMillis))
        }
        val status = when {
            quote == null -> price
            stale -> context.getString(R.string.stock_cached_as_of, timestamp)
            else -> context.getString(R.string.stock_as_of, timestamp)
        }
        views.setContentDescription(
            R.id.stock_item,
            "$symbol. ${quote?.name.orEmpty()}. $price${if (!setting.showCurrency && quote != null) " ${quote.currency}" else ""}. $changes. $status"
        )
        if (setting.multiline) {
            views.setTextViewTextSize(R.id.stock_company, TypedValue.COMPLEX_UNIT_SP, StockWidgetDisplay.fontSize(setting, 12f))
            views.setTextViewTextSize(R.id.stock_quote_time, TypedValue.COMPLEX_UNIT_SP, StockWidgetDisplay.fontSize(setting, 9f))
            views.setTextViewTextSize(R.id.stock_chart_empty, TypedValue.COMPLEX_UNIT_SP, StockWidgetDisplay.fontSize(setting, 10f))
            views.setTextViewText(R.id.stock_company, quote?.name ?: symbol)
            views.setTextColor(R.id.stock_company, theme.secondaryText)
            views.setTextViewText(R.id.stock_quote_time, status)
            views.setTextColor(R.id.stock_quote_time, theme.secondaryText)
            val chart = if (setting.charts && quote != null) StockChartRenderer.render(quote.points, changeColor) else null
            views.setViewVisibility(R.id.stock_chart, if (chart != null) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.stock_chart_empty,
                if (setting.charts && chart == null) View.VISIBLE else View.GONE)
            views.setTextColor(R.id.stock_chart_empty, textColor)
            if (chart != null) {
                views.setImageViewBitmap(R.id.stock_chart, chart)
                views.setContentDescription(R.id.stock_chart, context.getString(R.string.stock_chart_description, symbol))
            }
        } else {
            views.setTextViewTextSize(R.id.stock_cached, TypedValue.COMPLEX_UNIT_SP,
                StockWidgetDisplay.fontSize(setting, if (setting.dense) 12f else 9f))
            views.setViewVisibility(R.id.stock_cached, if (quote != null && stale) View.VISIBLE else View.GONE)
            views.setTextColor(R.id.stock_cached, textColor)
        }
        return views
    }
}
