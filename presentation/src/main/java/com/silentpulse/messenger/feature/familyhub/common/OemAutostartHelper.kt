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
package com.silentpulse.messenger.feature.familyhub.common

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

/**
 * OEM autostart / "protected apps" deep-link helper.
 *
 * Many Chinese OEMs (Xiaomi/MIUI, OPPO/ColorOS, Huawei/EMUI, Vivo/FuntouchOS,
 * OnePlus, Honor, Realme, Asus) ship aggressive background-process killers that
 * sit *above* Android's standard doze/battery-opt system. Even if the user
 * exempts SilentPulse via [BatteryOptimizationHelper], these layers can still
 * kill our location publisher / calendar sync / boot receiver.
 *
 * The fix is to point the user at each OEM's autostart whitelist screen and ask
 * them to enable SilentPulse there. There is no public API for this; we use the
 * documented `ComponentName` deep-links published at https://dontkillmyapp.com/.
 *
 * We never silently bypass any restriction. We open the OEM screen and let the
 * user toggle the switch themselves.
 */
object OemAutostartHelper {

    enum class Vendor(val displayName: String) {
        XIAOMI("Xiaomi / Redmi / POCO"),
        OPPO("OPPO / Realme"),
        VIVO("Vivo / iQOO"),
        HUAWEI("Huawei / Honor"),
        ONEPLUS("OnePlus"),
        SAMSUNG("Samsung"),
        ASUS("Asus"),
        MEIZU("Meizu"),
        OTHER("Generic Android");

        val needsAutostartWhitelist: Boolean
            get() = this in setOf(XIAOMI, OPPO, VIVO, HUAWEI, ONEPLUS, MEIZU, ASUS)
    }

    /** Detect the device vendor via [Build.MANUFACTURER] + [Build.BRAND]. */
    fun detectVendor(): Vendor {
        val mfg = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return when {
            mfg.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> Vendor.XIAOMI
            mfg.contains("oppo") || brand.contains("realme") -> Vendor.OPPO
            mfg.contains("vivo") || brand.contains("iqoo") -> Vendor.VIVO
            mfg.contains("huawei") || brand.contains("honor") -> Vendor.HUAWEI
            mfg.contains("oneplus") -> Vendor.ONEPLUS
            mfg.contains("samsung") -> Vendor.SAMSUNG
            mfg.contains("asus") -> Vendor.ASUS
            mfg.contains("meizu") -> Vendor.MEIZU
            else -> Vendor.OTHER
        }
    }

    /**
     * Open the OEM-specific autostart whitelist screen. Tries each known
     * deep-link in order; falls back to the system app-info page if none are
     * resolvable on this build. Returns true if *some* settings screen was
     * launched.
     */
    fun openAutostartSettings(context: Context, vendor: Vendor = detectVendor()): Boolean {
        val candidates: List<Intent> = when (vendor) {
            Vendor.XIAOMI -> listOf(
                componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                componentIntent("com.miui.securitycenter", "com.miui.permcenter.MainAtivity"),
            )
            Vendor.OPPO -> listOf(
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            Vendor.VIVO -> listOf(
                // Vivo's "Background app refresh" / autostart whitelist
                componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            Vendor.HUAWEI -> listOf(
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            )
            Vendor.ONEPLUS -> listOf(
                componentIntent("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            )
            Vendor.ASUS -> listOf(
                componentIntent("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
            )
            Vendor.MEIZU -> listOf(
                componentIntent("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
            )
            Vendor.SAMSUNG, Vendor.OTHER -> emptyList()
        }

        for (intent in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Timber.w(e, "OEM autostart deep-link failed for $vendor")
            }
        }

        // Fallback: app-detail settings
        return try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun componentIntent(pkg: String, cls: String): Intent =
        Intent().apply { component = ComponentName(pkg, cls) }
}
