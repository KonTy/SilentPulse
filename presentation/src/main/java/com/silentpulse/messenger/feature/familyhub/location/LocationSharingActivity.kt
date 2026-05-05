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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkThemedActivity
import com.silentpulse.messenger.common.util.extensions.setBackgroundTint
import com.silentpulse.messenger.common.widget.QkTextView
import dagger.android.AndroidInjection
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import timber.log.Timber
import java.io.File

/**
 * Family Sharing Hub — Slice 1: Location Sharing screen.
 *
 * Full-screen OpenStreetMap. Shows the user's current GPS location and (in
 * Slice 2) any peers the user has subscribed to. All toggles + setup checklist
 * live in [LocationSharingSettingsActivity], reachable via the toolbar gear or
 * via Settings → Location sharing.
 *
 * Privacy:
 *  - Tiles fetched from tile.openstreetmap.org (whitelisted in
 *    network_security_config.xml). No Google, no Mapbox, no API keys.
 *  - GPS fixes never leave the device in this slice.
 *  - osmdroid user-agent set to `SilentPulse/<version>` per OSM tile policy.
 */
class LocationSharingActivity : QkThemedActivity() {

    companion object {
        const val PREFS_NAME = "family_hub"
        const val PREF_TILE_CACHE = "tile_cache_to_disk"
        const val PREF_SHARE_LOCATION = "share_my_location"
    }

    private val locationToolbar: Toolbar by lazy { findViewById(R.id.toolbar) }
    private val mapView: MapView by lazy { findViewById(R.id.mapView) }
    private val centerOnMeFab: FloatingActionButton by lazy { findViewById(R.id.centerOnMeFab) }
    private val zoomInFab: FloatingActionButton by lazy { findViewById(R.id.zoomInFab) }
    private val zoomOutFab: FloatingActionButton by lazy { findViewById(R.id.zoomOutFab) }
    private val peerSheetEmpty: QkTextView by lazy { findViewById(R.id.peerSheetEmpty) }

    private val familyPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var userMarker: Marker? = null
    private var locationManager: LocationManager? = null
    private var pendingPostPermissionAction: (() -> Unit)? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { updateUserMarker(location) }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startLocationUpdates()
            pendingPostPermissionAction?.invoke()
        }
        pendingPostPermissionAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)

        // osmdroid configuration MUST be set before inflating the MapView.
        configureOsmdroid()

        setContentView(R.layout.location_sharing_activity)
        setSupportActionBar(locationToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        setupMap()

        // Tint FABs from the active app theme so they match OLED/light/etc.
        colors.theme().let { theme ->
            zoomInFab.setBackgroundTint(theme.theme)
            zoomOutFab.setBackgroundTint(theme.theme)
            centerOnMeFab.setBackgroundTint(theme.theme)
        }

        // Per decision (B): show user's GPS location immediately on first open.
        ensureLocationPermissionThen { startLocationUpdates() }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply tile-cache policy in case the user toggled it in Settings.
        applyTileCachePolicy(familyPrefs.getBoolean(PREF_TILE_CACHE, true))
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        stopLocationUpdates()
        super.onPause()
    }

    override fun onDestroy() {
        try { mapView.onDetach() } catch (_: Exception) { }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- toolbar

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.location_sharing, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_location_settings -> {
            startActivity(Intent(this, LocationSharingSettingsActivity::class.java))
            true
        }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------- osmdroid

    private fun configureOsmdroid() {
        val config = Configuration.getInstance()
        config.load(applicationContext, familyPrefs)
        try {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            val ver = pkgInfo.versionName ?: "dev"
            config.userAgentValue = "SilentPulse/$ver"
        } catch (_: Exception) {
            config.userAgentValue = "SilentPulse"
        }
        val cacheRoot = File(cacheDir, "osmdroid").apply { mkdirs() }
        config.osmdroidBasePath = cacheRoot
        config.osmdroidTileCache = File(cacheRoot, "tiles").apply { mkdirs() }

        applyTileCachePolicy(familyPrefs.getBoolean(PREF_TILE_CACHE, true))
    }

    /** Caps the on-disk cache. When [enabled]=false, osmdroid effectively
     *  only uses the in-memory LRU. */
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

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.controller.setZoom(13.0)
        mapView.controller.setCenter(GeoPoint(48.8566, 2.3522))

        centerOnMeFab.setOnClickListener {
            ensureLocationPermissionThen { centerOnLastKnownOrRequest() }
        }
        zoomInFab.setOnClickListener {
            mapView.controller.zoomIn()
        }
        zoomOutFab.setOnClickListener {
            mapView.controller.zoomOut()
        }

        // Slice 1: peer list is empty. Slice 2 will add a RecyclerView adapter
        // bound to a Realm-backed `FamilyPeer` collection and click handlers
        // that pan the map to the selected peer.
        peerSheetEmpty.setOnClickListener {
            startActivity(Intent(this, LocationSharingSettingsActivity::class.java))
        }
    }

    // -------------------------------------------------------- location wiring

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureLocationPermissionThen(action: () -> Unit) {
        if (hasLocationPermission()) {
            action()
        } else {
            pendingPostPermissionAction = action
            locationPermissionRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val lm = locationManager ?: return
        if (!hasLocationPermission()) return
        try {
            val lastFix = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            ).mapNotNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }
            if (lastFix != null) updateUserMarker(lastFix)

            val providers = lm.getProviders(true)
            for (provider in providers) {
                if (provider == LocationManager.PASSIVE_PROVIDER) continue
                lm.requestLocationUpdates(provider, 5_000L, 5f, locationListener)
            }
        } catch (e: SecurityException) {
            Timber.w(e, "No location permission despite earlier check")
        } catch (e: Exception) {
            Timber.w(e, "Failed to start location updates")
        }
    }

    private fun stopLocationUpdates() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun centerOnLastKnownOrRequest() {
        val lm = locationManager ?: return
        if (!hasLocationPermission()) return
        try {
            val lastFix = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            ).mapNotNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }
            if (lastFix != null) {
                updateUserMarker(lastFix)
                mapView.controller.animateTo(GeoPoint(lastFix.latitude, lastFix.longitude))
                mapView.controller.setZoom(15.0)
            }
        } catch (_: SecurityException) { }
    }

    private fun updateUserMarker(location: Location) {
        val point = GeoPoint(location.latitude, location.longitude)
        val marker = userMarker ?: Marker(mapView).also {
            it.title = getString(R.string.family_you_are_here)
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(it)
            userMarker = it
        }
        marker.position = point
        if (mapView.zoomLevelDouble < 14.0) {
            mapView.controller.setZoom(15.0)
            mapView.controller.animateTo(point)
        }
        mapView.invalidate()
    }
}
