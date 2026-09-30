package com.silentpulse.messenger.feature.stocks

data class StockWidgetAppearance(
    val multiline: Boolean,
    val charts: Boolean,
    val twoColumns: Boolean,
    val dark: Boolean,
    val refreshMinutes: Int,
    val dense: Boolean,
    val transparent: Boolean,
    val showCurrency: Boolean,
    val groupStyle: StockGroupStyle,
    val followAppTheme: Boolean,
    val fontScalePercent: Int = 100
) {
    fun applyTo(settings: StockWidgetSettings): StockWidgetSettings = settings.copy(
        multiline = multiline, charts = charts, twoColumns = twoColumns, dark = dark,
        refreshMinutes = refreshMinutes, dense = dense, transparent = transparent,
        showCurrency = showCurrency, groupStyle = groupStyle, followAppTheme = followAppTheme,
        fontScalePercent = fontScalePercent
    )

    companion object {
        fun from(settings: StockWidgetSettings) = StockWidgetAppearance(
            settings.multiline, settings.charts, settings.twoColumns, settings.dark,
            settings.refreshMinutes, settings.dense, settings.transparent, settings.showCurrency,
            settings.groupStyle, settings.followAppTheme, settings.fontScalePercent
        )
    }
}
