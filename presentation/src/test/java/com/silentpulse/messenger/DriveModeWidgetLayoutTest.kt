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
                icon.dp("height") + 2 * button.dp("padding") <= availableHeight
            )
        }
    }
}
