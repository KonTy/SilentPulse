package com.silentpulse.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DriveModeWidgetLayoutTest {

    private val projectRoot = generateSequence(File(System.getProperty("user.dir") ?: ".")) {
        it.parentFile
    }.first { File(it, "settings.gradle").exists() }

    private fun resource(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(projectRoot, "presentation/src/main/res/$path"))
            .documentElement

    private fun Element.android(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun Element.dp(name: String): Int {
        val value = android(name)
        assertTrue("$name must be specified in dp", value.endsWith("dp"))
        return value.removeSuffix("dp").toInt()
    }

    @Test
    fun `widget requests one row and can be resized back to one row`() {
        val provider = resource("xml/widget_drive_mode_info.xml")

        assertEquals("@layout/widget_drive_mode", provider.android("initialLayout"))
        assertEquals("1", provider.android("targetCellHeight"))
        assertTrue(
            "Leave room for launcher padding on a single row",
            provider.dp("minHeight") in 1..40
        )
        assertTrue(
            "Existing widgets must be allowed to shrink vertically",
            "vertical" in provider.android("resizeMode").split("|")
        )
        assertTrue(provider.dp("minResizeHeight") in 1..40)
    }

    @Test
    fun `all four icons fit in the minimum widget height without shrinking`() {
        val provider = resource("xml/widget_drive_mode_info.xml")
        val layout = resource("layout/widget_drive_mode.xml")
        val availableHeight = minOf(provider.dp("minHeight"), provider.dp("minResizeHeight")) -
            layout.dp("paddingTop") - layout.dp("paddingBottom")
        val buttons = layout.getElementsByTagName("ImageButton")
        val expectedIds = listOf(
            "@+id/btn_notif_reader",
            "@+id/btn_next_notif",
            "@+id/btn_voice_ast",
            "@+id/btn_stop_speaking"
        )

        assertEquals("LinearLayout", layout.tagName)
        assertEquals("horizontal", layout.android("orientation"))
        assertEquals(expectedIds.size, buttons.length)
        expectedIds.forEachIndexed { index, id ->
            val button = buttons.item(index) as Element
            assertEquals(id, button.android("id"))
            assertEquals("0dp", button.android("layout_width"))
            assertEquals("1", button.android("layout_weight"))
            assertEquals("match_parent", button.android("layout_height"))
            assertTrue(button.dp("minHeight") <= availableHeight)

            val icon = resource("drawable/${button.android("src").removePrefix("@drawable/")}.xml")
            assertTrue(
                "$id must fit at full icon size in a single row",
                icon.dp("height") + button.dp("paddingTop") + button.dp("paddingBottom") <= availableHeight
            )
        }
    }

    @Test
    fun `all four icons fit at two three and four columns without shrinking`() {
        val provider = resource("xml/widget_drive_mode_info.xml")
        val layout = resource("layout/widget_drive_mode.xml")
        val buttons = layout.getElementsByTagName("ImageButton")

        assertEquals("2", provider.android("targetCellWidth"))
        assertTrue(provider.dp("minWidth") in 1..110)
        assertTrue(provider.dp("minResizeWidth") in 1..110)
        assertTrue("horizontal" in provider.android("resizeMode").split("|"))
        assertEquals(4, buttons.length)

        // Legacy launcher size hints are 70 * columns - 30 dp.
        for (columns in 2..4) {
            val width = 70 * columns - 30
            val slotWidth = (width - layout.dp("paddingStart") - layout.dp("paddingEnd")) / 4
            for (index in 0 until buttons.length) {
                val button = buttons.item(index) as Element
                val icon = resource("drawable/${button.android("src").removePrefix("@drawable/")}.xml")
                assertEquals("0dp", button.android("layout_width"))
                assertEquals("1", button.android("layout_weight"))
                assertTrue(button.dp("minWidth") <= slotWidth)
                assertTrue(
                    "${button.android("id")} must fit at $columns columns without shrinking its icon",
                    icon.dp("width") + button.dp("paddingStart") + button.dp("paddingEnd") <= slotWidth
                )
            }
        }
    }
}
