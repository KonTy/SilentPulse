package com.silentpulse.messenger.feature.worldclock

import android.content.Context
import android.content.SharedPreferences
import com.silentpulse.messenger.common.util.CityTimeZone
import com.silentpulse.messenger.common.util.CityTimeZones
import timber.log.Timber

data class WorldClockSettings(val city: CityTimeZone, val darkText: Boolean = false)

class WorldClockPreferences(private val preferences: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences("world_clock_widgets", Context.MODE_PRIVATE)
    )

    fun load(widgetId: Int): WorldClockSettings? {
        val city = preferences.getString("clock_$widgetId.city", null)
        val zone = preferences.getString("clock_$widgetId.zone", null)
        if (city == null && zone == null) return null
        if (city.isNullOrBlank() || zone == null || !CityTimeZones.isValidZoneId(zone)) {
            Timber.w("World clock %d has invalid settings; city selection is required", widgetId)
            return null
        }
        return WorldClockSettings(
            CityTimeZone(city, zone),
            preferences.getBoolean("clock_$widgetId.dark", false)
        )
    }

    fun save(widgetId: Int, settings: WorldClockSettings) {
        require(widgetId > 0) { "Invalid world clock widget ID" }
        require(settings.city.city.isNotBlank()) { "A world clock needs a city label" }
        require(CityTimeZones.isValidZoneId(settings.city.zoneId)) { "Invalid world clock time zone" }
        preferences.edit().write(widgetId, settings).apply()
    }

    fun remove(widgetId: Int) {
        preferences.edit().removeClock(widgetId).apply()
    }

    fun restore(oldIds: IntArray, newIds: IntArray) {
        require(oldIds.size == newIds.size) { "Widget restore IDs must be paired" }
        require(newIds.all { it > 0 }) { "Invalid restored world clock widget ID" }
        // Snapshot before editing: restored IDs can overlap the old IDs.
        val settings = oldIds.map(::load)
        val editor = preferences.edit()
        oldIds.forEach { editor.removeClock(it) }
        newIds.forEachIndexed { index, id ->
            val restored = settings[index]
            if (restored != null) editor.write(id, restored) else editor.removeClock(id)
        }
        editor.apply()
    }

    private fun SharedPreferences.Editor.write(id: Int, settings: WorldClockSettings) = apply {
        putString("clock_$id.city", settings.city.city)
        putString("clock_$id.zone", settings.city.zoneId)
        putBoolean("clock_$id.dark", settings.darkText)
    }

    private fun SharedPreferences.Editor.removeClock(id: Int) = apply {
        remove("clock_$id.city")
        remove("clock_$id.zone")
        remove("clock_$id.dark")
    }
}
