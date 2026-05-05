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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helper for checking + requesting the
 * [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] exemption.
 *
 * Without this exemption Android will aggressively doze SilentPulse and our
 * location publisher / calendar sync worker / SMS background tasks may be
 * killed within minutes of the screen going off. The Family Sharing Hub setup
 * checklist asks the user to grant this once.
 *
 * Per Google Play policy [REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] is restricted
 * to apps that genuinely need to run continuously in the background. SilentPulse
 * (always-on SMS + location publisher + voice assistant) qualifies. We are
 * self-hosted and do not ship via Play, so this is policy-clean either way.
 */
object BatteryOptimizationHelper {

    /** True when the user has already exempted SilentPulse from battery optimization. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Open the system dialog asking the user to exempt SilentPulse. The dialog
     * itself is the only legitimate path; users that decline can still exempt
     * us manually via [openBatterySettings].
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openBatterySettings(context)
        }
    }

    /** Fallback: dump the user into the system Battery Optimization list. */
    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Last-resort: settings root
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    /** True on Android < M which had no doze restrictions. */
    val isPreDoze: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
}
