package com.silentpulse.messenger.feature.stocks

import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkThemedActivity
import com.silentpulse.messenger.databinding.StockDetailActivityBinding
import com.silentpulse.messenger.databinding.StockNewsItemBinding
import com.silentpulse.messenger.feature.stocks.data.StockChartPoint
import com.silentpulse.messenger.feature.stocks.data.StockHistoryRange
import com.silentpulse.messenger.feature.stocks.data.StockNewsArticle
import com.silentpulse.messenger.feature.stocks.data.StockNewsLinks
import com.silentpulse.messenger.feature.stocks.data.StockQuote
import com.silentpulse.messenger.feature.stocks.data.YahooStockClient
import com.silentpulse.messenger.feature.stocks.data.YahooStockNews
import dagger.android.AndroidInjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.text.DateFormat
import java.util.Date

class StockDetailActivity : QkThemedActivity() {
    private lateinit var binding: StockDetailActivityBinding
    private lateinit var palette: StockThemeColors
    private lateinit var symbol: String
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var quote: StockQuote? = null
    private var range = StockHistoryRange.MONTH
    private var points: List<StockChartPoint> = emptyList()
    private val history = mutableMapOf<StockHistoryRange, List<StockChartPoint>>()
    private val client = YahooStockClient()
    private var quoteJob: Job? = null
    private var historyJob: Job? = null
    private var newsJob: Job? = null
    private var newsArticles: List<StockNewsArticle>? = null
    private var newsExplanation: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val selected = StockDetailSelection.resolve(intent.getStringExtra(EXTRA_SYMBOL), StockWidgetPreferences(this).load(widgetId))
        if (selected == null || !StockWidgetProvider.isOwnWidget(this, widgetId)) {
            invalidSelection()
            return
        }
        symbol = selected
        range = StockHistoryRange.values().find { it.name == savedInstanceState?.getString(STATE_RANGE) }
            ?: StockHistoryRange.MONTH
        setContentView(R.layout.stock_detail_activity)
        binding = StockDetailActivityBinding.bind(findViewById(R.id.stock_detail_root))
        palette = StockWidgetTheme.load(this)
        title = symbol
        showBackButton(true)
        binding.stockDetailRoot.setBackgroundColor(palette.background)
        binding.toolbar.setBackgroundColor(palette.background)
        binding.toolbar.setTitleTextColor(palette.text)
        listOf(binding.stockDetailName, binding.stockDetailPrice, binding.stockDetailNewsTitle).forEach {
            it.setTextColor(palette.text)
        }
        listOf(binding.stockDetailMetadata, binding.stockDetailQuoteTime, binding.stockDetailQuoteStatus,
            binding.stockDetailChartStatus, binding.stockDetailChartDates, binding.stockDetailNewsStatus,
            binding.stockDetailNotice).forEach { it.setTextColor(palette.secondaryText) }
        binding.stockDetailName.text = symbol
        binding.stockDetailRefresh.setOnClickListener { refresh() }
        binding.stockDetailRange.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(R.string.stock_range_day, R.string.stock_range_week, R.string.stock_range_month, R.string.stock_range_year)
                .map(::getString)
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.stockDetailRange.setSelection(range.ordinal)
        binding.stockDetailRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedRange = StockHistoryRange.values()[position]
                if (range != selectedRange && selectionIsCurrent()) {
                    range = selectedRange
                    loadHistory()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val cached = StockQuoteCache(this).load(symbol)
        quote = cached.quote
        quote?.let {
            history[StockHistoryRange.DAY] = it.points
            renderQuote(it)
            binding.stockDetailQuoteStatus.setText(R.string.stock_detail_cached)
        }
        binding.stockDetailChart.doOnLayout { renderChart() }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_RANGE, range.name)
        super.onSaveInstanceState(outState)
    }

    private fun selectionIsCurrent(): Boolean {
        val current = StockDetailSelection.resolve(symbol, StockWidgetPreferences(this).load(widgetId))
        if (current == null || !StockWidgetProvider.isOwnWidget(this, widgetId)) {
            invalidSelection()
            return false
        }
        return true
    }

    private fun refresh() {
        if (!selectionIsCurrent()) return
        history.clear()
        loadQuote()
        loadHistory()
        loadNews()
    }

    private fun loadQuote() {
        quoteJob?.cancel()
        binding.stockDetailQuoteStatus.setText(R.string.stock_detail_loading_quote)
        quoteJob = lifecycleScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { client.fetch(symbol) }
                ensureActive()
                quote = latest
                history[StockHistoryRange.DAY] = latest.points
                renderQuote(latest)
                binding.stockDetailQuoteStatus.setText(R.string.stock_detail_source)
                if (range == StockHistoryRange.DAY) {
                    historyJob?.cancel()
                    showHistory(latest.points)
                }
            } catch (_: IOException) {
                ensureActive()
                Timber.w("Stock detail quote unavailable")
                if (quote == null) binding.stockDetailPrice.setText(R.string.stock_unavailable)
                binding.stockDetailQuoteStatus.setText(
                    if (quote == null) R.string.stock_detail_quote_failed else R.string.stock_detail_cached_failed
                )
            }
        }
    }

    private fun renderQuote(value: StockQuote) {
        binding.stockDetailName.text = value.name
        val type = when (value.instrumentType) {
            "EQUITY" -> getString(R.string.stock_type_equity)
            "ETF" -> getString(R.string.stock_type_etf)
            "MUTUALFUND" -> getString(R.string.stock_type_fund)
            "INDEX" -> getString(R.string.stock_type_index)
            "FUTURE" -> getString(R.string.stock_type_future)
            "CURRENCY" -> getString(R.string.stock_type_currency)
            "CRYPTOCURRENCY" -> getString(R.string.stock_type_crypto)
            else -> value.instrumentType.orEmpty()
        }
        binding.stockDetailMetadata.text = listOf(symbol, type, value.exchange.orEmpty(), value.currency)
            .filter(String::isNotBlank).joinToString(" / ")
        binding.stockDetailPrice.text = "${value.currency} ${StockWidgetDisplay.number(value.price)}"
        val change = value.change
        val percent = value.changePercent
        binding.stockDetailChange.text = if (change != null && percent != null) {
            getString(R.string.stock_detail_change, StockWidgetDisplay.signed(change), StockWidgetDisplay.densePercent(percent))
        } else getString(R.string.stock_change_unavailable)
        binding.stockDetailChange.setTextColor(StockWidgetDisplay.changeColor(change, palette.dark, palette.text))
        binding.stockDetailQuoteTime.text = getString(R.string.stock_as_of, dateTime(value.marketTimeMillis))
        renderNews()
    }

    private fun loadHistory() {
        historyJob?.cancel()
        history[range]?.let {
            showHistory(it)
            return
        }
        points = emptyList()
        binding.stockDetailChart.visibility = View.GONE
        binding.stockDetailChartDates.text = ""
        binding.stockDetailChartStatus.setText(R.string.stock_detail_loading_chart)
        val requestedRange = range
        historyJob = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { client.fetchHistory(symbol, requestedRange) }
                ensureActive()
                history[requestedRange] = loaded
                if (range == requestedRange) showHistory(loaded)
            } catch (_: IOException) {
                ensureActive()
                Timber.w("Stock detail history unavailable")
                if (range == requestedRange) binding.stockDetailChartStatus.setText(R.string.stock_detail_chart_failed)
            }
        }
    }

    private fun showHistory(loaded: List<StockChartPoint>) {
        points = loaded
        binding.stockDetailChart.visibility = View.VISIBLE
        binding.stockDetailChart.doOnLayout { renderChart() }
    }

    private fun renderChart() {
        if (points.isEmpty()) {
            binding.stockDetailChart.visibility = View.GONE
            binding.stockDetailChartStatus.setText(R.string.stock_detail_chart_empty)
            binding.stockDetailChartDates.text = ""
            return
        }
        val prices = points.mapNotNull { it.price }
        val change = if (range == StockHistoryRange.DAY) quote?.change else
            prices.lastOrNull()?.let { last -> prices.firstOrNull()?.let { last - it } }
        val bitmap = StockChartRenderer.render(
            points, StockWidgetDisplay.changeColor(change, palette.dark, palette.text),
            binding.stockDetailChart.width, binding.stockDetailChart.height
        )
        binding.stockDetailChart.setImageBitmap(bitmap)
        binding.stockDetailChart.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        if (bitmap == null) {
            binding.stockDetailChartStatus.setText(R.string.stock_detail_chart_empty)
            binding.stockDetailChartDates.text = ""
        } else {
            binding.stockDetailChartStatus.text = getString(R.string.stock_detail_chart_bounds,
                StockWidgetDisplay.number(prices.minOrNull()!!), StockWidgetDisplay.number(prices.maxOrNull()!!))
            val format = if (range == StockHistoryRange.DAY) DateFormat.getTimeInstance(DateFormat.SHORT)
                else DateFormat.getDateInstance(DateFormat.SHORT)
            binding.stockDetailChartDates.text = getString(R.string.stock_detail_chart_dates,
                format.format(Date(points.first().timeMillis)), format.format(Date(points.last().timeMillis)))
        }
    }

    private fun loadNews() {
        newsJob?.cancel()
        newsArticles = null
        binding.stockDetailNews.removeAllViews()
        binding.stockDetailNewsStatus.setText(R.string.stock_detail_loading_news)
        newsJob = lifecycleScope.launch {
            try {
                val articles = withContext(Dispatchers.IO) { YahooStockNews().fetch(symbol) }
                ensureActive()
                newsArticles = articles
                renderNews()
            } catch (_: IOException) {
                ensureActive()
                Timber.w("Stock detail news unavailable")
                binding.stockDetailNewsStatus.setText(R.string.stock_detail_news_failed)
            }
        }
    }

    private fun renderNews() {
        val articles = newsArticles ?: return
        binding.stockDetailNews.removeAllViews()
        binding.stockDetailNewsStatus.setText(
            if (articles.isEmpty()) R.string.stock_detail_news_empty else R.string.stock_detail_news_source
        )
        articles.forEach { article ->
            val item = StockNewsItemBinding.inflate(layoutInflater, binding.stockDetailNews, false)
            item.stockNewsTitle.text = article.title
            item.stockNewsTitle.setTextColor(palette.text)
            item.stockNewsByline.text = listOfNotNull(
                article.publisher.takeIf(String::isNotBlank), article.publishedAtMillis?.let(::dateTime)
            ).joinToString(" / ")
            item.stockNewsByline.setTextColor(palette.secondaryText)
            val mention = !StockNewsRelevance.headlineNamesInstrument(
                article.title, symbol, quote?.name, quote?.instrumentType
            )
            item.stockNewsMention.visibility = if (mention) View.VISIBLE else View.GONE
            item.stockNewsMention.setColorFilter(palette.secondaryText)
            item.stockNewsMention.setOnClickListener {
                newsExplanation?.dismiss()
                val tags = article.relatedTickers.joinToString(", ").ifBlank { symbol }
                newsExplanation = AlertDialog.Builder(this)
                    .setTitle(R.string.stock_news_mention_title)
                    .setMessage(getString(R.string.stock_news_mention_explanation, symbol, tags))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            item.root.setOnClickListener { openArticle(article) }
            binding.stockDetailNews.addView(item.root)
        }
    }

    override fun onDestroy() {
        newsExplanation?.dismiss()
        super.onDestroy()
    }

    private fun openArticle(article: StockNewsArticle) {
        if (!StockNewsLinks.isArticle(article.url)) {
            Timber.w("Invalid stock article link")
            Toast.makeText(this, R.string.stock_detail_link_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Timber.w("No browser available for stock article")
            Toast.makeText(this, R.string.stock_detail_link_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

    private fun invalidSelection() {
        Timber.w("Cannot open details for a missing widget or unselected stock")
        Toast.makeText(this, R.string.stock_detail_invalid, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_SYMBOL = "stock_detail_symbol"
        private const val STATE_RANGE = "history_range"
    }
}
