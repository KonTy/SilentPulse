package com.silentpulse.messenger.feature.stocks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkThemedActivity
import com.silentpulse.messenger.databinding.StockAssetPickerActivityBinding
import com.silentpulse.messenger.feature.stocks.data.StockSearchResult
import com.silentpulse.messenger.feature.stocks.data.YahooStockSearch
import dagger.android.AndroidInjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

class StockAssetPickerActivity : QkThemedActivity() {
    private lateinit var binding: StockAssetPickerActivityBinding
    private lateinit var adapter: AssetAdapter
    private var searchJob: Job? = null
    private var searchGeneration = 0
    private var submittedQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.stock_asset_picker_activity)
        binding = StockAssetPickerActivityBinding.bind(findViewById(R.id.stock_asset_picker_root))
        title = getString(R.string.stock_asset_picker_title)
        showBackButton(true)

        val theme = StockWidgetTheme.load(this)
        binding.stockAssetPickerRoot.setBackgroundColor(theme.background)
        binding.toolbar.setBackgroundColor(theme.background)
        binding.toolbar.setTitleTextColor(theme.text)
        binding.stockAssetSearch.setTextColor(theme.text)
        binding.stockAssetSearch.setHintTextColor(theme.secondaryText)
        binding.stockAssetHint.setTextColor(theme.secondaryText)
        binding.stockAssetEmpty.setTextColor(theme.text)
        binding.stockSearchStatus.setTextColor(theme.secondaryText)
        binding.stockAssetList.setBackgroundColor(theme.background)
        binding.stockAssetList.cacheColorHint = theme.background

        adapter = AssetAdapter(this, theme)
        binding.stockAssetList.adapter = adapter
        binding.stockAssetList.emptyView = binding.stockAssetEmpty
        binding.stockAssetSearch.doAfterTextChanged { showLocalMatches() }
        binding.stockAssetSearch.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                searchOnline()
                true
            } else false
        }
        binding.stockSearchOnline.setOnClickListener { searchOnline() }
        binding.stockAssetList.setOnItemClickListener { _, _, position, _ ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SYMBOL, adapter.getItem(position).symbol))
            finish()
        }
        showLocalMatches()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean(STATE_ONLINE)) searchOnline()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_ONLINE, submittedQuery != null)
        super.onSaveInstanceState(outState)
    }

    private fun localMatches(query: String): List<StockSearchResult> =
        StockAssets.search(query, ::getString).map { StockSearchResult(it.symbol, getString(it.label)) }

    private fun showLocalMatches() {
        searchGeneration++
        searchJob?.cancel()
        submittedQuery = null
        binding.stockAssetSearch.error = null
        binding.stockSearchOnline.isEnabled = true
        binding.stockSearchStatus.setText(R.string.stock_local_search_status)
        binding.stockAssetEmpty.setText(R.string.stock_asset_no_results)
        adapter.show(localMatches(binding.stockAssetSearch.text.toString()))
    }

    private fun searchOnline() {
        val query = binding.stockAssetSearch.text.toString().trim()
        if (!YahooStockSearch.validQuery(query)) {
            binding.stockAssetSearch.error = getString(R.string.stock_search_invalid)
            return
        }
        searchJob?.cancel()
        val generation = ++searchGeneration
        submittedQuery = query
        binding.stockAssetSearch.error = null
        binding.stockSearchOnline.isEnabled = false
        binding.stockSearchStatus.setText(R.string.stock_online_search_loading)
        searchJob = lifecycleScope.launch {
            try {
                val online = withContext(Dispatchers.IO) { YahooStockSearch().search(query) }
                ensureActive()
                if (generation != searchGeneration) return@launch
                val results = (localMatches(query) + online).distinctBy { it.symbol }
                adapter.show(results)
                binding.stockAssetEmpty.setText(R.string.stock_online_no_results)
                binding.stockSearchStatus.text = getString(R.string.stock_online_search_results, results.size)
            } catch (_: IOException) {
                ensureActive()
                if (generation != searchGeneration) return@launch
                Timber.w("Online stock lookup unavailable")
                binding.stockSearchStatus.setText(R.string.stock_online_search_failed)
                binding.stockAssetEmpty.setText(R.string.stock_online_search_failed)
            } finally {
                if (generation == searchGeneration) binding.stockSearchOnline.isEnabled = true
            }
        }
    }

    private class AssetAdapter(
        private val context: Context,
        private val colors: StockThemeColors
    ) : BaseAdapter() {
        private val inflater = LayoutInflater.from(context)
        private var assets: List<StockSearchResult> = emptyList()

        fun show(results: List<StockSearchResult>) {
            assets = results
            notifyDataSetChanged()
        }

        override fun getCount() = assets.size
        override fun getItem(position: Int) = assets[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: inflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val asset = getItem(position)
            row.setBackgroundColor(colors.background)
            row.findViewById<TextView>(android.R.id.text1).apply {
                text = asset.name
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(colors.text)
            }
            row.findViewById<TextView>(android.R.id.text2).apply {
                text = listOf(asset.symbol, asset.type, asset.exchange).filter(String::isNotEmpty).joinToString(" / ")
                setTextColor(colors.secondaryText)
            }
            return row
        }
    }

    companion object {
        const val EXTRA_SYMBOL = "stock_asset_symbol"
        private const val STATE_ONLINE = "online_search"
    }
}
