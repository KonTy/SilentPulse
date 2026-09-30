package com.silentpulse.messenger.feature.stocks

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

object StockWidgetDisplay {
    const val LIGHT_TEXT = 0xff202124.toInt()
    const val DARK_TEXT = 0xfff2f3f5.toInt()
    const val COLUMN_GAP_DP = 12

    fun columns(settings: StockWidgetSettings, widthDp: Int, fontScale: Float): Int {
        if (!settings.twoColumns || (!settings.multiline && !settings.dense)) return 1
        val minimumCellWidth = if (settings.dense && !settings.showCurrency) 132 else 152
        // The two text cells scale with fonts; the gap, widget padding and outer margins do not.
        val minimumWidth = 2 * minimumCellWidth * fontScale.coerceAtLeast(1f) *
            settings.fontScalePercent / 100f + COLUMN_GAP_DP + 12
        return if (widthDp >= minimumWidth) 2 else 1
    }

    fun fontSize(settings: StockWidgetSettings, baseSp: Float): Float = baseSp * settings.fontScalePercent / 100f

    fun background(settings: StockWidgetSettings?, theme: StockThemeColors): Int =
        if (settings?.transparent == true) 0 else theme.background

    fun changeColor(change: Double?, dark: Boolean, neutral: Int = if (dark) DARK_TEXT else LIGHT_TEXT): Int = when {
        change == null || change == 0.0 -> neutral
        change > 0 -> if (dark) 0xff00e676.toInt() else 0xff008035.toInt()
        else -> if (dark) 0xffff5252.toInt() else 0xffd50032.toInt()
    }

    fun number(value: Double, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = if (abs(value) < 1 && value != 0.0) 4 else 2
        }.format(value)

    fun signed(value: Double, locale: Locale = Locale.getDefault()): String =
        (if (value > 0) "+" else if (value < 0) "-" else "") + number(abs(value), locale)

    fun densePercent(value: Double, locale: Locale = Locale.getDefault()): String =
        (if (value > 0) "+" else if (value < 0) "-" else "") +
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }.format(abs(value)) + "%"
}
