/*
 * Copyright (C) 2026 SilentPulse contributors
 *
 * This file is part of SilentPulse.
 *
 * SilentPulse is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.silentpulse.messenger.feature.familyhub.location

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkThemedActivity
import com.silentpulse.messenger.common.widget.PreferenceView
import com.silentpulse.messenger.feature.familyhub.common.BatteryOptimizationHelper
import com.silentpulse.messenger.feature.familyhub.common.OemAutostartHelper
import dagger.android.AndroidInjection
import org.osmdroid.config.Configuration
import timber.log.Timber

/**
 * Settings screen for the Location Sharing feature.
 *
 * Uses the app's existing PreferenceView widgets so OLED/black/light themes
 * apply automatically. Reachable from:
 *   - the gear icon in the [LocationSharingActivity] toolbar
 *   - Settings → Location sharing
 */
class LocationSharingSettingsActivity : QkThemedActivity() {

    private val locationToolbar: Toolbar by lazy { findViewById(R.id.toolbar) }
    private val shareToggle: PreferenceView by lazy { findViewById(R.id.shareToggle) }
    private val tileCacheToggle: PreferenceView by lazy { findViewById(R.id.tileCacheToggle) }
    private val clearTileCache: PreferenceView by lazy { findViewById(R.id.clearTileCache) }
    private val setupLocationRow: PreferenceView by lazy { findViewById(R.id.setupLocationRow) }
    private val setupBatteryRow: PreferenceView by lazy { findViewById(R.id.setupBatteryRow) }
    private val setupOemRow: PreferenceView by lazy { findViewById(R.id.setupOemRow) }

    private val familyPrefs: SharedPreferences by lazy {
        getSharedPreferences(LocationSharingActivity.PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.location_sharing_settings_activity)
        setSupportActionBar(locationToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        wireRows()
        refreshChecklist()
    }

    override fun onResume() {
        super.onResume()
        refreshChecklist()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ----------------------------------------------------------------- rows

    private fun wireRows() {
        // Tile cache row: tapping the row flips its switch.
        val cacheOn = familyPrefs.getBoolean(LocationSharingActivity.PREF_TILE_CACHE, true)
        tileCacheToggle.checkbox.isChecked = cacheOn
        tileCacheToggle.summary = getString(
            if (cacheOn) R.string.family_tile_cache_hint_on
            else R.string.family_tile_cache_hint_off
        )
        tileCacheToggle.setOnClickListener {
            val next = !tileCacheToggle.checkbox.isChecked
            tileCacheToggle.checkbox.isChecked = next
            familyPrefs.edit()
                .putBoolean(LocationSharingActivity.PREF_TILE_CACHE, next).apply()
            applyTileCachePolicy(next)
            tileCacheToggle.summary = getString(
                if (next) R.string.family_tile_cache_hint_on
                else R.string.family_tile_cache_hint_off
            )
            if (!next) clearTileCacheOnDisk()
        }

        clearTileCache.setOnClickListener { clearTileCacheOnDisk() }

        // Master share toggle row
        shareToggle.checkbox.isChecked =
            familyPrefs.getBoolean(LocationSharingActivity.PREF_SHARE_LOCATION, false)
        shareToggle.setOnClickListener {
            val next = !shareToggle.checkbox.isChecked
            shareToggle.checkbox.isChecked = next
            familyPrefs.edit()
                .putBoolean(LocationSharingActivity.PREF_SHARE_LOCATION, next).apply()
            if (next) runSetupChecklistFlow()
            refreshChecklist()
        }

        // Setup checklist rows
        setupLocationRow.setOnClickListener { finish() }
        setupBatteryRow.setOnClickListener {
            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
        }
        setupOemRow.setOnClickListener {
            OemAutostartHelper.openAutostartSettings(this, OemAutostartHelper.detectVendor())
        }
    }

    private fun applyTileCachePolicy(enabled: Boolean) {
        val config = Configuration.getInstance()
        if (enabled) {
            config.tileFileSystemCacheMaxBytes = 600L * 1024 * 1024
            config.tileFileSystemCacheTrimBytes = 500L * 1024 * 1024
        } else {
            config.tileFileSystemCacheMaxBytes = 0
            config.tileFileSystemCacheTrimBytes = 0
        }
    }

    private fun clearTileCacheOnDisk() {
        try {
            val cacheRoot = Configuration.getInstance().osmdroidTileCache
            if (cacheRoot != null && cacheRoot.exists()) {
                cacheRoot.deleteRecursively()
                cacheRoot.mkdirs()
            }
            android.widget.Toast.makeText(
                this, R.string.family_tile_cache_cleared,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Timber.w(e, "Failed to clear tile cache")
        }
    }

    private fun runSetupChecklistFlow() {
        if (!BatteryOptimizationHelper.isPreDoze
            && !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
        }
    }

    private fun refreshChecklist() {
        val vendor = OemAutostartHelper.detectVendor()
        val needOem = vendor.needsAutostartWhitelist
        val batteryOk = BatteryOptimizationHelper.isPreDoze
            || BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)

        setupBatteryRow.summary = getString(
            if (batteryOk) R.string.family_setup_status_done
            else R.string.family_setup_status_needed
        )
        setupOemRow.visibility =
            if (needOem) android.view.View.VISIBLE else android.view.View.GONE
        setupOemRow.title = getString(R.string.family_setup_oem_autostart_fmt, vendor.displayName)
        setupOemRow.summary = getString(R.string.family_setup_status_check)
        setupLocationRow.summary = getString(R.string.family_setup_open_map)
    }
}
