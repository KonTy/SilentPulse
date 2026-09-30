package com.silentpulse.messenger.feature.stocks

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkThemedActivity
import com.silentpulse.messenger.feature.settingsbackup.SettingsBackupActivity
import dagger.android.AndroidInjection

class StockSettingsActivity : QkThemedActivity() {
    private val stockPreferences by lazy { StockWidgetPreferences(this) }
    private val widgets by lazy {
        StockSettingsWidgetList(
            installedIds = {
                AppWidgetManager.getInstance(this)
                    .getAppWidgetIds(ComponentName(this, StockWidgetProvider::class.java))
            },
            isOwnWidget = { StockWidgetProvider.isOwnWidget(this, it) },
            settings = stockPreferences::load
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.stock_settings_activity)
        title = getString(R.string.drawer_stocks)
        showBackButton(true)
        findViewById<Button>(R.id.stock_settings_backup).setOnClickListener {
            startActivity(Intent(this, SettingsBackupActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshWidgets()
    }

    private fun refreshWidgets() {
        val entries = widgets.load()
        val list = findViewById<LinearLayout>(R.id.stock_settings_widgets)
        list.removeAllViews()
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.stock_settings_empty).visibility =
            if (entries.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.stock_settings_remembered).visibility =
            if (stockPreferences.rememberedWatchlist().isNotEmpty()) View.VISIBLE else View.GONE
        entries.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.stock_settings_widget_item, list, false)
            row.findViewById<TextView>(R.id.stock_settings_widget_title).text =
                getString(R.string.stock_settings_widget_title, entry.widgetId)
            row.findViewById<TextView>(R.id.stock_settings_widget_symbols).text =
                entry.settings?.symbols?.joinToString(", ") ?: getString(R.string.stock_settings_unconfigured)
            row.setOnClickListener { configure(entry.widgetId) }
            list.addView(row)
        }
    }

    private fun configure(widgetId: Int) {
        if (!widgets.canConfigure(widgetId)) {
            refreshWidgets()
            Toast.makeText(this, R.string.stock_settings_widget_removed, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, StockWidgetConfigureActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        })
    }
}
