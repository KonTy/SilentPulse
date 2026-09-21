package com.silentpulse.messenger.feature.worldclock

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkThemedActivity
import com.silentpulse.messenger.common.util.CityTimeZone
import com.silentpulse.messenger.common.util.CityTimeZones
import com.silentpulse.messenger.databinding.WorldClockConfigureActivityBinding
import com.silentpulse.messenger.feature.worldclock.weather.OpenMeteoClockWeather
import com.silentpulse.messenger.feature.worldclock.weather.WorldClockWeatherWorker
import dagger.android.AndroidInjection
import timber.log.Timber

class WorldClockConfigureActivity : QkThemedActivity() {

    private lateinit var binding: WorldClockConfigureActivityBinding
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedCity: CityTimeZone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (!isOwnWidget()) {
            reportInvalidWidget()
            return
        }

        setContentView(R.layout.world_clock_configure_activity)
        binding = WorldClockConfigureActivityBinding.bind(findViewById(R.id.world_clock_configure_root))
        title = getString(R.string.world_clock_configure_title)
        showBackButton(true)
        binding.worldClockWeatherCredit.movementMethod = LinkMovementMethod.getInstance()

        val stored = WorldClockPreferences(this).load(widgetId)
        selectedCity = stored?.city?.let(WorldClockCityOptions::canonical)
        val savedZone = savedInstanceState?.getString(STATE_ZONE)
        val savedCity = savedInstanceState?.getString(STATE_CITY)
        if (savedZone != null && !savedCity.isNullOrBlank() && CityTimeZones.isValidZoneId(savedZone)) {
            selectedCity = WorldClockCityOptions.canonical(CityTimeZone(savedCity, savedZone))
        }
        val darkText = savedInstanceState?.getBoolean(STATE_DARK) ?: (stored?.darkText == true)
        binding.worldClockTextColor.check(
            if (darkText) R.id.world_clock_dark_text else R.id.world_clock_light_text
        )

        val adapter = CityAdapter(this)
        binding.worldClockCities.adapter = adapter
        binding.worldClockCities.emptyView = binding.worldClockEmpty
        binding.worldClockSearch.doAfterTextChanged {
            adapter.cities = WorldClockCityOptions.search(it?.toString().orEmpty())
        }
        binding.worldClockCities.setOnItemClickListener { _, _, position, _ ->
            selectedCity = adapter.getItem(position)
            updateSelection()
        }
        binding.worldClockSave.setOnClickListener {
            if (!isOwnWidget()) {
                reportInvalidWidget()
                return@setOnClickListener
            }
            val city = selectedCity
            if (city == null) {
                Toast.makeText(this, R.string.world_clock_choose_city, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            WorldClockPreferences(this).save(
                widgetId,
                WorldClockSettings(
                    city,
                    binding.worldClockTextColor.checkedRadioButtonId == R.id.world_clock_dark_text
                )
            )
            WorldClockWidgetProvider.updateWidget(this, widgetId)
            WorldClockWeatherWorker.requestRefresh(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
        updateSelection()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedCity?.let {
            outState.putString(STATE_CITY, it.city)
            outState.putString(STATE_ZONE, it.zoneId)
        }
        if (::binding.isInitialized) {
            outState.putBoolean(
                STATE_DARK, binding.worldClockTextColor.checkedRadioButtonId == R.id.world_clock_dark_text
            )
        }
    }

    private fun updateSelection() {
        binding.worldClockSelection.text = selectedCity?.let {
            val selection = getString(R.string.world_clock_selected_city, it.city, it.zoneId)
            val weatherCity = OpenMeteoClockWeather.weatherLocationLabel(it)
            if (weatherCity != it.city) {
                getString(R.string.world_clock_selected_weather_city, selection, weatherCity)
            } else selection
        } ?: getString(R.string.world_clock_choose_city)
        binding.worldClockSave.isEnabled = selectedCity != null
    }

    private fun isOwnWidget(): Boolean = widgetId > 0 &&
        AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider ==
        ComponentName(this, WorldClockWidgetProvider::class.java)

    private fun reportInvalidWidget() {
        Timber.w("Cannot configure missing or unrelated world clock widget %d", widgetId)
        Toast.makeText(this, R.string.world_clock_invalid_widget, Toast.LENGTH_LONG).show()
        finish()
    }

    private class CityAdapter(context: Context) : BaseAdapter() {
        private val inflater = LayoutInflater.from(context)
        var cities = WorldClockCityOptions.search("")
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = cities.size
        override fun getItem(position: Int) = cities[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: inflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val city = getItem(position)
            row.findViewById<TextView>(android.R.id.text1).text = city.city
            row.findViewById<TextView>(android.R.id.text2).text = city.zoneId
            return row
        }
    }

    companion object {
        private const val STATE_CITY = "selected_city"
        private const val STATE_ZONE = "selected_zone"
        private const val STATE_DARK = "dark_text"
    }
}
