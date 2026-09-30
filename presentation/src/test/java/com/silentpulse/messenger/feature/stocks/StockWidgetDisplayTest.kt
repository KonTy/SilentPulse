package com.silentpulse.messenger.feature.stocks

import com.silentpulse.messenger.feature.stocks.data.StockChartPoint
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class StockWidgetDisplayTest {
    @Test
    fun `cards adapt to width and accessibility font scale but compact remains one column`() {
        val cards = StockWidgetSettings(listOf("AAPL"), multiline = true)
        assertEquals(1, StockWidgetDisplay.columns(cards, 327, 1f))
        assertEquals(2, StockWidgetDisplay.columns(cards, 328, 1f))
        assertEquals(1, StockWidgetDisplay.columns(cards, 320, 1.5f))
        assertEquals(2, StockWidgetDisplay.columns(cards, 480, 1.5f))
        assertEquals(1, StockWidgetDisplay.columns(cards.copy(twoColumns = false), 500, 1f))
        assertEquals(1, StockWidgetDisplay.columns(cards.copy(multiline = false), 500, 1f))
    }

    @Test
    fun `dense rows reserve a full gap and adapt to larger fonts`() {
        val dense = StockWidgetSettings(listOf("AAPL"), dense = true, showCurrency = false)
        assertEquals(1, StockWidgetDisplay.columns(dense, 287, 1f))
        assertEquals(2, StockWidgetDisplay.columns(dense, 288, 1f))
        assertEquals(1, StockWidgetDisplay.columns(dense, 320, 1.5f))
        assertEquals(2, StockWidgetDisplay.columns(dense, 420, 1.5f))
        assertEquals(1, StockWidgetDisplay.columns(dense.copy(showCurrency = true), 327, 1f))
        assertEquals(2, StockWidgetDisplay.columns(dense.copy(showCurrency = true), 328, 1f))
        assertEquals(1, StockWidgetDisplay.columns(dense.copy(twoColumns = false), 500, 1f))
        val enlarged = dense.copy(fontScalePercent = 150)
        assertEquals(1, StockWidgetDisplay.columns(enlarged, 320, 1f))
        assertEquals(2, StockWidgetDisplay.columns(enlarged, 420, 1f))
        assertEquals(21f, StockWidgetDisplay.fontSize(enlarged, 14f), 0f)
    }

    @Test
    fun `fully transparent background works independently of text color and mode`() {
        for (dark in listOf(false, true)) {
            for (mode in listOf("compact", "dense", "cards")) {
                val settings = StockWidgetSettings(listOf("AAPL"), dark = dark,
                    multiline = mode == "cards", dense = mode == "dense", transparent = true)
                assertEquals(0, StockWidgetDisplay.background(settings,
                    StockThemeColors(0xff000000.toInt(), -1, -1, -1, dark)))
            }
        }
        val oled = StockThemeColors(0xff000000.toInt(), -1, -1, -1, true)
        assertEquals(0xff000000.toInt(), StockWidgetDisplay.background(StockWidgetSettings(listOf("AAPL")), oled))
    }

    @Test
    fun `dense percentage keeps direction and two decimals without absolute change`() {
        assertEquals("+0.23%", StockWidgetDisplay.densePercent(0.225, Locale.US))
        assertEquals("-1.25%", StockWidgetDisplay.densePercent(-1.25, Locale.US))
        assertEquals("0.00%", StockWidgetDisplay.densePercent(0.0, Locale.US))
        assertEquals("+1,25%", StockWidgetDisplay.densePercent(1.25, Locale.GERMANY))
    }

    @Test
    fun `changes include direction independent of color and currency formatting is locale aware`() {
        assertEquals("+1.25", StockWidgetDisplay.signed(1.25, Locale.US))
        assertEquals("-1.25", StockWidgetDisplay.signed(-1.25, Locale.US))
        assertEquals("0.00", StockWidgetDisplay.signed(0.0, Locale.US))
        assertEquals("1.234,50", StockWidgetDisplay.number(1234.5, Locale.GERMANY))
        assertEquals("0.0012", StockWidgetDisplay.number(0.0012, Locale.US))
        assertNotEquals(StockWidgetDisplay.changeColor(-1.0, false), StockWidgetDisplay.changeColor(1.0, false))
        assertEquals(StockWidgetDisplay.LIGHT_TEXT, StockWidgetDisplay.changeColor(null, false))
    }

    @Test
    fun `vivid gain and loss colors remain legible on app and OLED backgrounds`() {
        assertEquals(0xff00e676.toInt(), StockWidgetDisplay.changeColor(1.0, true))
        assertEquals(0xffff5252.toInt(), StockWidgetDisplay.changeColor(-1.0, true))
        for ((dark, background) in listOf(
            true to 0xff000000.toInt(), true to 0xff192025.toInt(),
            true to 0xff252830.toInt(), false to 0xffffffff.toInt(), false to 0xfff4f2fa.toInt()
        )) {
            for (change in listOf(-1.0, 1.0)) {
                assertTrue(contrast(StockWidgetDisplay.changeColor(change, dark), background) >= 4.5)
            }
        }
    }

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color shr shift) and 255) / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
        }
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test
    fun `chart uses timestamp spacing and keeps up prices visually above lower ones`() {
        val positions = StockChartGeometry.segments(listOf(
            StockChartPoint(1_000, 10.0), StockChartPoint(2_000, 20.0), StockChartPoint(5_000, 15.0)
        ), 100f, 40f).single()
        assertEquals(listOf(ChartPosition(0f, 40f), ChartPosition(25f, 0f), ChartPosition(100f, 20f)), positions)
    }

    @Test
    fun `missing chart samples produce gaps not fabricated connecting lines`() {
        val points = listOf(10.0, 11.0, null, 15.0, 16.0).mapIndexed { i, price ->
            StockChartPoint(i * 1_000L, price)
        }
        val segments = StockChartGeometry.segments(points, 100f, 40f)
        assertEquals(2, segments.size)
        assertEquals(25f, segments[0].last().x)
        assertEquals(75f, segments[1].first().x)
    }

    @Test
    fun `flat charts are centered and missing series are unavailable`() {
        val flat = StockChartGeometry.segments(listOf(
            StockChartPoint(1_000, 10.0), StockChartPoint(2_000, 10.0)
        ), 100f, 40f)
        assertTrue(flat.single().all { it.y == 20f })
        assertTrue(StockChartGeometry.segments(emptyList(), 100f, 40f).isEmpty())
        assertTrue(StockChartGeometry.segments(listOf(StockChartPoint(1_000, 10.0)), 100f, 40f).isEmpty())
        assertTrue(StockChartGeometry.segments(listOf(
            StockChartPoint(1_000, 10.0), StockChartPoint(2_000, null), StockChartPoint(3_000, 10.0)
        ), 100f, 40f).isEmpty())
        assertTrue(StockChartGeometry.segments(listOf(
            StockChartPoint(1_000, -Double.MAX_VALUE), StockChartPoint(2_000, Double.MAX_VALUE)
        ), 100f, 40f).isEmpty())
    }
}
