package com.silentpulse.messenger.feature.worldclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WorldClockWidgetLayoutTest {

    private val projectRoot = generateSequence(File(System.getProperty("user.dir") ?: ".")) {
        it.parentFile
    }.first { File(it, "settings.gradle").exists() }

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(projectRoot, "presentation/src/main/$path"))
            .documentElement

    private fun Element.android(name: String) =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun Element.dp(name: String) = android(name).removeSuffix("dp").toInt()

    @Test
    fun `two clocks fit side by side on a four column single row grid`() {
        val provider = xml("res/xml/widget_world_clock_info.xml")

        assertEquals("2", provider.android("targetCellWidth"))
        assertEquals("1", provider.android("targetCellHeight"))
        assertTrue(provider.dp("minWidth") in 1..110)
        assertTrue(provider.dp("minHeight") in 1..40)
        assertTrue(provider.dp("minResizeWidth") in 1..110)
        assertTrue(provider.dp("minResizeHeight") in 1..40)
        assertEquals(setOf("horizontal", "vertical"), provider.android("resizeMode").split("|").toSet())
    }

    @Test
    fun `clock has a transparent background and uses a host driven TextClock`() {
        val layout = xml("res/layout/widget_world_clock.xml")
        val clocks = layout.getElementsByTagName("TextClock")
        val icons = layout.getElementsByTagName("ImageView")
        val labels = layout.getElementsByTagName("TextView")

        assertEquals("@android:color/transparent", layout.android("background"))
        assertEquals("vertical", layout.android("orientation"))
        assertEquals(0, layout.dp("paddingTop") + layout.dp("paddingBottom"))
        assertEquals(1, clocks.length)
        assertEquals(1, icons.length)
        assertEquals(1, labels.length)
        val clock = clocks.item(0) as Element
        val city = labels.item(0) as Element
        val weather = icons.item(0) as Element
        assertEquals("0dp", clock.android("layout_height"))
        assertEquals("0dp", city.android("layout_height"))
        assertEquals("2", clock.android("layout_weight"))
        assertEquals("1", city.android("layout_weight"))
        assertEquals("0dp", weather.android("layout_height"))
        assertEquals("1", weather.android("layout_weight"))
        assertEquals("fitCenter", weather.android("scaleType"))
        assertEquals("@android:color/transparent", weather.android("background"))
        assertEquals("uniform", clock.android("autoSizeTextType"))
        assertEquals("uniform", city.android("autoSizeTextType"))
        assertEquals("1", clock.android("maxLines"))
        assertEquals("1", city.android("maxLines"))
        assertEquals("false", clock.android("includeFontPadding"))
        assertEquals("false", city.android("includeFontPadding"))
        assertEquals("", clock.android("format12Hour"))
        assertEquals("", clock.android("format24Hour"))
        assertEquals("0", xml("res/xml/widget_world_clock_info.xml").android("updatePeriodMillis"))
        val children = (0 until layout.childNodes.length).map { layout.childNodes.item(it) }
            .filterIsInstance<Element>()
        assertEquals(listOf("TextClock", "ImageView", "TextView"), children.map { it.tagName })
        assertFalse(children.any { it.android("id").contains("temperature") })
    }

    @Test
    fun `launcher can configure and later reconfigure each clock`() {
        val provider = xml("res/xml/widget_world_clock_info.xml")
        val manifest = xml("AndroidManifest.xml")
        val activities = manifest.getElementsByTagName("activity")
        val receivers = manifest.getElementsByTagName("receiver")
        val configureName = ".feature.worldclock.WorldClockConfigureActivity"
        val receiverName = ".feature.worldclock.WorldClockWidgetProvider"
        val configure = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.android("name") == configureName }
        val receiver = (0 until receivers.length).map { receivers.item(it) as Element }
            .single { it.android("name") == receiverName }

        assertEquals("com.silentpulse.messenger$configureName", provider.android("configure"))
        assertEquals("reconfigurable", provider.android("widgetFeatures"))
        assertFalse(provider.android("widgetFeatures").contains("configuration_optional"))
        assertEquals("true", configure.android("exported"))
        assertEquals("false", receiver.android("exported"))
        assertEquals(
            "android.appwidget.action.APPWIDGET_CONFIGURE",
            (configure.getElementsByTagName("action").item(0) as Element).android("name")
        )
        assertEquals(
            "@xml/widget_world_clock_info",
            (receiver.getElementsByTagName("meta-data").item(0) as Element).android("resource")
        )
    }
}
