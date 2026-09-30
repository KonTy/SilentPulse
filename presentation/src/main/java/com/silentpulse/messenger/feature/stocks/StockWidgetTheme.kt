package com.silentpulse.messenger.feature.stocks

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import com.silentpulse.messenger.R
import com.silentpulse.messenger.injection.appComponent
import com.silentpulse.messenger.util.Preferences

data class StockThemeColors(
    val background: Int,
    val text: Int,
    val secondaryText: Int,
    val separator: Int,
    val dark: Boolean
)

object StockWidgetTheme {
    fun load(
        context: Context,
        followAppTheme: Boolean = true,
        manualDark: Boolean = false
    ): StockThemeColors {
        if (!followAppTheme) return manualColors(manualDark)

        val applicationContext = context.applicationContext
        val systemDark = applicationContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return resolve(
            nightMode = appComponent.preferences().nightMode.get(),
            systemDark = systemDark
        ) { ContextCompat.getColor(applicationContext, it) }
    }

    internal fun resolve(
        nightMode: Int,
        systemDark: Boolean,
        followAppTheme: Boolean = true,
        manualDark: Boolean = false,
        color: (Int) -> Int
    ): StockThemeColors {
        if (!followAppTheme) return manualColors(manualDark)

        // The selected mode is authoritative even before an activity updates prefs.night/black.
        val dark = when (nightMode) {
            Preferences.NIGHT_MODE_SYSTEM -> systemDark
            Preferences.NIGHT_MODE_ON, Preferences.NIGHT_MODE_OLED -> true
            else -> false
        }
        return StockThemeColors(
            background = color(when {
                nightMode == Preferences.NIGHT_MODE_OLED -> R.color.black
                dark -> R.color.backgroundDark
                else -> R.color.backgroundLight
            }),
            text = color(if (dark) R.color.textPrimaryDark else R.color.textPrimary),
            secondaryText = color(if (dark) R.color.textSecondaryDark else R.color.textSecondary),
            separator = color(if (dark) R.color.separatorDark else R.color.separatorLight),
            dark = dark
        )
    }

    private fun manualColors(dark: Boolean): StockThemeColors = if (dark) {
        StockThemeColors(
            background = 0xff252830.toInt(),
            text = 0xfff2f3f5.toInt(),
            secondaryText = 0xfff2f3f5.toInt(),
            separator = 0xff464b55.toInt(),
            dark = true
        )
    } else {
        StockThemeColors(
            background = 0xfff4f2fa.toInt(),
            text = 0xff202124.toInt(),
            secondaryText = 0xff202124.toInt(),
            separator = 0xffe0dde7.toInt(),
            dark = false
        )
    }
}
