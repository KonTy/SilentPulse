package com.silentpulse.messenger.feature.worldclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldClockWidgetSizingTest {

    @Test
    fun `legacy dimensions fit both host orientations and tolerate missing options`() {
        assertEquals(40, WorldClockWidgetSizing.smallestDimension(40, 110, 110))
        assertEquals(40, WorldClockWidgetSizing.smallestDimension(110, 40, 110))
        assertEquals(220, WorldClockWidgetSizing.smallestDimension(220, 0, 110))
        assertEquals(40, WorldClockWidgetSizing.smallestDimension(0, 40, 110))
        assertEquals(110, WorldClockWidgetSizing.smallestDimension(0, -1, 110))
    }

    @Test
    fun `one cell time fits long day periods at every font scale and density`() {
        for (density in listOf(1f, 2f, 3.5f)) {
            for (fontScale in listOf(1f, 1.5f, 2f)) {
                for (textWidth in listOf(2.5f, 4f, 8f)) {
                    val width = 40f * density
                    val timeHeight = 20f * density
                    val size = WorldClockWidgetSizing.fitText(
                        width, timeHeight, 28f * density * fontScale, textWidth, 1.2f
                    )
                    assertTrue(size > 0f)
                    assertTrue(size * textWidth <= width)
                    assertTrue(size * 1.2f <= timeHeight)
                }
            }
        }
    }

    @Test
    fun `larger tiles retain large accessible text rather than a compact fixed size`() {
        assertEquals(28f, WorldClockWidgetSizing.fitText(220f, 100f, 28f, 4f, 1.2f), 0.001f)
        assertEquals(56f, WorldClockWidgetSizing.fitText(440f, 200f, 56f, 4f, 1.2f), 0.001f)
    }

    @Test
    fun `long city labels keep their minimum width size and ellipsize instead`() {
        assertEquals(
            8f,
            WorldClockWidgetSizing.fitText(40f, 10f, 12f, 20f, 1f, minimumWidthSize = 8f),
            0.001f
        )
    }

    @Test
    fun `height still limits city size and empty labels produce finite sizes`() {
        assertEquals(
            4f,
            WorldClockWidgetSizing.fitText(40f, 5f, 12f, 20f, 1f, minimumWidthSize = 8f),
            0.001f
        )
        assertEquals(12f, WorldClockWidgetSizing.fitText(40f, 20f, 12f, 0f, 1f), 0.001f)
    }
}
