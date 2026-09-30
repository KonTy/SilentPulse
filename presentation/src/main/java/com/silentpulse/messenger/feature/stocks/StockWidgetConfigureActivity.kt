package com.silentpulse.messenger.feature.stocks

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkThemedActivity
import com.silentpulse.messenger.databinding.StockWidgetConfigureActivityBinding
import com.silentpulse.messenger.feature.settingsbackup.ImportedWidgetPresets
import com.silentpulse.messenger.feature.stocks.data.StockQuote
import com.silentpulse.messenger.feature.stocks.data.StockSymbols
import com.silentpulse.messenger.feature.stocks.data.YahooStockClient
import dagger.android.AndroidInjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class StockWidgetConfigureActivity : QkThemedActivity() {
    private lateinit var binding: StockWidgetConfigureActivityBinding
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var verificationDialog: AlertDialog? = null
    private var presetPending = false
    private val assetPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val symbol = result.data?.getStringExtra(StockAssetPickerActivity.EXTRA_SYMBOL)
            if (symbol != null && StockSymbols.normalize(symbol) == symbol) {
                insertLine(StockAssets.editorSymbol(symbol))
            } else {
                Timber.w("Invalid stock asset picker result")
                Toast.makeText(this, R.string.stock_asset_pick_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        presetPending = savedInstanceState?.getBoolean("imported_preset_pending") ?: false
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (!StockWidgetProvider.isOwnWidget(this, widgetId)) {
            reportInvalidWidget()
            return
        }
        setContentView(R.layout.stock_widget_configure_activity)
        binding = StockWidgetConfigureActivityBinding.bind(findViewById(R.id.stock_configure_root))
        title = getString(R.string.stock_configure_title)
        showBackButton(true)
        binding.stockInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            StockWidgetPreferences.refreshIntervals.map { getString(R.string.stock_refresh_interval, it) }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.stockGroupStyle.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(R.string.stock_group_lines, R.string.stock_group_borders, R.string.stock_group_none).map(::getString)
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.stockFontSize.max = (StockWidgetPreferences.MAX_FONT_SCALE - StockWidgetPreferences.MIN_FONT_SCALE) /
            StockWidgetPreferences.FONT_SCALE_STEP
        if (savedInstanceState == null) {
            val preferences = StockWidgetPreferences(this)
            val stored = preferences.load(widgetId)
            binding.stockSymbols.setText(stored?.let {
                StockWatchlist.format(StockWatchlist.effectiveGroups(it))
            } ?: preferences.rememberedWatchlist())
            binding.stockMode.check(when {
                stored?.multiline == true -> R.id.stock_mode_cards
                stored?.dense == true -> R.id.stock_mode_dense
                else -> R.id.stock_mode_compact
            })
            binding.stockCharts.isChecked = stored?.charts ?: true
            binding.stockColumns.isChecked = stored?.twoColumns ?: true
            binding.stockDark.isChecked = stored?.dark ?: false
            binding.stockTransparent.isChecked = stored?.transparent ?: false
            binding.stockCurrency.isChecked = stored?.showCurrency ?: true
            binding.stockFollowTheme.isChecked = stored?.followAppTheme ?: true
            binding.stockFontSize.progress = ((stored?.fontScalePercent ?: 100) - StockWidgetPreferences.MIN_FONT_SCALE) /
                StockWidgetPreferences.FONT_SCALE_STEP
            binding.stockGroupStyle.setSelection((stored?.groupStyle ?: StockGroupStyle.LINES).ordinal)
            binding.stockInterval.setSelection(StockWidgetPreferences.refreshIntervals.indexOf(stored?.refreshMinutes ?: 15))
        }
        binding.stockAddGroup.setOnClickListener { addGroup() }
        binding.stockAddAsset.setOnClickListener {
            assetPicker.launch(Intent(this, StockAssetPickerActivity::class.java))
        }
        binding.stockSave.setOnClickListener {
            if (!StockWidgetProvider.isOwnWidget(this, widgetId)) {
                reportInvalidWidget()
                return@setOnClickListener
            }
            val groups = StockWatchlist.parse(binding.stockSymbols.text.toString())
            if (groups == null) {
                binding.stockSymbols.error = getString(R.string.stock_symbols_invalid)
                return@setOnClickListener
            }
            verifyAndSave(groups)
        }
        updateOptions()
        updateFontLabel()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!::binding.isInitialized) return
        // Attach after Android restores the full view state, never persist a partially restored form.
        binding.stockMode.setOnCheckedChangeListener { _, _ ->
            updateOptions()
            persistAppearance()
        }
        listOf(binding.stockCharts, binding.stockColumns, binding.stockCurrency, binding.stockTransparent,
            binding.stockFollowTheme, binding.stockDark).forEach { checkbox ->
            checkbox.setOnCheckedChangeListener { _, _ ->
                updateOptions()
                persistAppearance()
            }
        }
        val selectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = persistAppearance()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.stockInterval.onItemSelectedListener = selectionListener
        binding.stockGroupStyle.onItemSelectedListener = selectionListener
        binding.stockFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateFontLabel()
                if (fromUser) persistAppearance()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        updateOptions()
        updateFontLabel()
    }

    private fun updateOptions() {
        val multiline = binding.stockMode.checkedRadioButtonId == R.id.stock_mode_cards
        binding.stockCharts.isEnabled = multiline
        binding.stockColumns.isEnabled = multiline || binding.stockMode.checkedRadioButtonId == R.id.stock_mode_dense
        binding.stockDark.setText(if (binding.stockTransparent.isChecked) R.string.stock_light_text else R.string.stock_dark)
        binding.stockDark.isEnabled = !binding.stockFollowTheme.isChecked
    }

    private fun selectedFontScale() = StockWidgetPreferences.MIN_FONT_SCALE +
        binding.stockFontSize.progress * StockWidgetPreferences.FONT_SCALE_STEP

    private fun updateFontLabel() {
        binding.stockFontSizeLabel.text = getString(R.string.stock_font_size_value, selectedFontScale())
    }

    private fun appearance() = StockWidgetAppearance(
        multiline = binding.stockMode.checkedRadioButtonId == R.id.stock_mode_cards,
        charts = binding.stockCharts.isChecked,
        twoColumns = binding.stockColumns.isChecked,
        dark = binding.stockDark.isChecked,
        refreshMinutes = StockWidgetPreferences.refreshIntervals[binding.stockInterval.selectedItemPosition],
        dense = binding.stockMode.checkedRadioButtonId == R.id.stock_mode_dense,
        transparent = binding.stockTransparent.isChecked,
        showCurrency = binding.stockCurrency.isChecked,
        groupStyle = StockGroupStyle.values()[binding.stockGroupStyle.selectedItemPosition],
        followAppTheme = binding.stockFollowTheme.isChecked,
        fontScalePercent = selectedFontScale()
    )

    private fun persistAppearance() {
        // A selected preset is a draft until the user explicitly verifies and saves the whole form.
        if (presetPending) return
        if (!StockWidgetProvider.isOwnWidget(this, widgetId)) {
            reportInvalidWidget()
            return
        }

        if (StockWidgetPreferences(this).updateAppearance(widgetId, appearance())) {
            StockWidgetProvider.updateWidget(this, widgetId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        super.onCreateOptionsMenu(menu)
        menu?.add(Menu.NONE, ImportedWidgetPresets.MENU_ID, Menu.NONE, R.string.settings_backup_presets)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != ImportedWidgetPresets.MENU_ID) return super.onOptionsItemSelected(item)
        if (!::binding.isInitialized || !binding.stockSave.isEnabled || verificationDialog?.isShowing == true) return true
        ImportedWidgetPresets.chooseStock(this) { stored ->
            presetPending = true
            binding.stockSymbols.setText(StockWatchlist.format(StockWatchlist.effectiveGroups(stored)))
            binding.stockMode.check(when {
                stored.multiline -> R.id.stock_mode_cards
                stored.dense -> R.id.stock_mode_dense
                else -> R.id.stock_mode_compact
            })
            binding.stockCharts.isChecked = stored.charts
            binding.stockColumns.isChecked = stored.twoColumns
            binding.stockDark.isChecked = stored.dark
            binding.stockTransparent.isChecked = stored.transparent
            binding.stockCurrency.isChecked = stored.showCurrency
            binding.stockFollowTheme.isChecked = stored.followAppTheme
            binding.stockFontSize.progress = (stored.fontScalePercent - StockWidgetPreferences.MIN_FONT_SCALE) /
                StockWidgetPreferences.FONT_SCALE_STEP
            binding.stockGroupStyle.setSelection(stored.groupStyle.ordinal)
            binding.stockInterval.setSelection(StockWidgetPreferences.refreshIntervals.indexOf(stored.refreshMinutes))
            updateOptions()
            updateFontLabel()
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("imported_preset_pending", presetPending)
        super.onSaveInstanceState(outState)
    }

    private fun addGroup() {
        val input = EditText(this).apply { hint = getString(R.string.stock_group_name) }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.stock_add_group).setView(input)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (!StockWatchlist.validGroupName(name)) {
                    input.error = getString(R.string.stock_group_invalid)
                } else {
                    insertLine("[$name]\n")
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun insertLine(value: String) {
        val text = binding.stockSymbols.text
        val selection = binding.stockSymbols.selectionStart.coerceIn(0, text.length)
        val endOfLine = text.indexOf('\n', selection).takeIf { it >= 0 } ?: text.length
        val prefix = if (endOfLine == 0) "" else "\n"
        text.insert(endOfLine, prefix + value)
        binding.stockSymbols.requestFocus()
        binding.stockSymbols.setSelection(endOfLine + prefix.length + value.length)
    }

    private fun verifyAndSave(groups: List<StockGroup>) {
        binding.stockSymbols.error = null
        setVerifying(true)
        val symbols = StockWatchlist.symbols(groups)
        val cache = StockQuoteCache(this)
        val existing = StockWidgetPreferences(this).load(widgetId)?.symbols.orEmpty()
            .mapNotNull { symbol -> cache.load(symbol).quote?.let { symbol to it } }.toMap()
        lifecycleScope.launch {
            try {
                val verified = withContext(Dispatchers.IO) {
                    StockWatchlistVerifier(YahooStockClient()::fetch).verify(symbols, existing) { symbol, index, total ->
                        ensureActive()
                        lifecycleScope.launch {
                            binding.stockValidation.text = getString(R.string.stock_verifying, symbol, index, total)
                        }
                    }
                }
                if (!StockWidgetProvider.isOwnWidget(this@StockWidgetConfigureActivity, widgetId)) {
                    reportInvalidWidget()
                    return@launch
                }
                val newSymbols = symbols.filterNot(existing::containsKey)
                if (newSymbols.isEmpty()) {
                    save(groups, verified, emptySet())
                } else {
                    binding.stockValidation.text = getString(R.string.stock_verified)
                    val descriptions = newSymbols.joinToString("\n") { symbol ->
                        val asset = StockAssets.entries.find { it.symbol == symbol }
                        val name = asset?.let { getString(it.label) } ?: verified.getValue(symbol).name
                        "$symbol - $name"
                    }
                    verificationDialog = AlertDialog.Builder(this@StockWidgetConfigureActivity)
                        .setTitle(R.string.stock_verify_title)
                        .setMessage(descriptions)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.stock_save_confirmed) { _, _ -> save(groups, verified, newSymbols.toSet()) }
                        .show()
                }
            } catch (failure: StockVerificationException) {
                Timber.w("Stock watchlist could not be verified")
                val message = getString(R.string.stock_verify_failed, failure.symbol)
                binding.stockValidation.text = message
                binding.stockSymbols.error = message
            } finally {
                setVerifying(false)
            }
        }
    }

    private fun setVerifying(verifying: Boolean) {
        binding.stockSave.isEnabled = !verifying
        binding.stockSymbols.isEnabled = !verifying
        binding.stockAddGroup.isEnabled = !verifying
        binding.stockAddAsset.isEnabled = !verifying
        binding.stockValidation.visibility = View.VISIBLE
    }

    private fun save(groups: List<StockGroup>, quotes: Map<String, StockQuote>, fetched: Set<String>) {
        if (!StockWidgetProvider.isOwnWidget(this, widgetId)) {
            reportInvalidWidget()
            return
        }
        StockWidgetPreferences(this).save(widgetId, appearance().applyTo(StockWidgetSettings(
            symbols = StockWatchlist.symbols(groups),
            groups = groups
        )))
        val cache = StockQuoteCache(this)
        val now = System.currentTimeMillis()
        fetched.forEach { symbol -> cache.save(symbol, CachedStockQuote(quotes.getValue(symbol), now, now)) }
        StockWidgetProvider.updateWidget(this, widgetId)
        StockWidgetWorker.requestRefresh(this, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    override fun onDestroy() {
        verificationDialog?.dismiss()
        super.onDestroy()
    }

    private fun reportInvalidWidget() {
        Timber.w("Cannot configure a missing or unrelated stock widget")
        Toast.makeText(this, R.string.stock_invalid_widget, Toast.LENGTH_LONG).show()
        finish()
    }
}
