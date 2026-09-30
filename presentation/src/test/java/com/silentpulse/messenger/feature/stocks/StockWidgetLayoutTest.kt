package com.silentpulse.messenger.feature.stocks

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StockWidgetLayoutTest {
    private val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .first { File(it, "settings.gradle").exists() }
    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(root, "presentation/src/main/$path")).documentElement
    private fun Element.android(name: String) = getAttributeNS("http://schemas.android.com/apk/res/android", name)
    private fun Element.elements(tag: String) = getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `stock widget is resizable scrollable and has no periodic broadcast wakeups`() {
        val provider = xml("res/xml/stock_widget_info.xml")
        assertEquals("horizontal|vertical", provider.android("resizeMode"))
        assertEquals("0", provider.android("updatePeriodMillis"))
        assertEquals("reconfigurable", provider.android("widgetFeatures"))
        assertEquals("4", provider.android("targetCellWidth"))
        assertEquals("2", provider.android("targetCellHeight"))
        val layout = xml("res/layout/stock_widget.xml")
        assertEquals(1, layout.elements("ListView").size)
        assertEquals(2, layout.elements("ImageButton").size)
    }

    @Test
    fun `launcher configuration exported but data service and actions are private`() {
        val manifest = xml("AndroidManifest.xml")
        val activity = manifest.elements("activity").single {
            it.android("name") == ".feature.stocks.StockWidgetConfigureActivity"
        }
        assertEquals("true", activity.android("exported"))
        assertEquals("android.appwidget.action.APPWIDGET_CONFIGURE", activity.elements("action").single().android("name"))
        val receiver = manifest.elements("receiver").single { it.android("name") == ".feature.stocks.StockWidgetProvider" }
        assertEquals("false", receiver.android("exported"))
        val service = manifest.elements("service").single { it.android("name") == ".feature.stocks.StockWidgetService" }
        assertEquals("false", service.android("exported"))
        assertEquals("android.permission.BIND_REMOTEVIEWS", service.android("permission"))
    }

    @Test
    fun `stocks has a real drawer entry and private themed settings hub with empty state and backup access`() {
        val drawer = xml("res/layout/drawer_view.xml").elements("LinearLayout").single {
            it.android("id") == "@+id/stocks"
        }
        assertEquals("@style/DrawerRow", drawer.getAttribute("style"))
        assertEquals("@string/drawer_stocks",
            drawer.elements("com.silentpulse.messenger.common.widget.QkTextView").single().android("text"))
        val activity = xml("AndroidManifest.xml").elements("activity").single {
            it.android("name") == ".feature.stocks.StockSettingsActivity"
        }
        assertEquals("false", activity.android("exported"))
        assertEquals("@string/drawer_stocks", activity.android("label"))
        val layout = xml("res/layout/stock_settings_activity.xml")
        assertEquals("?android:attr/windowBackground", layout.android("background"))
        assertEquals(1, layout.elements("androidx.appcompat.widget.Toolbar").size)
        assertTrue(layout.elements("LinearLayout").any { it.android("id") == "@+id/stock_settings_widgets" })
        val empty = layout.elements("com.silentpulse.messenger.common.widget.QkTextView").single {
            it.android("id") == "@+id/stock_settings_empty"
        }
        assertEquals("@string/stock_settings_empty", empty.android("text"))
        val backup = layout.elements("Button").single()
        assertEquals("@+id/stock_settings_backup", backup.android("id"))
        assertEquals("@string/settings_backup_title", backup.android("text"))
        val row = xml("res/layout/stock_settings_widget_item.xml")
        assertEquals("true", row.android("clickable"))
        assertEquals("true", row.android("focusable"))
        assertEquals("wrap_content", row.android("layout_height"))
    }

    @Test
    fun `compact mode is one line and cards put chart below all quote text`() {
        val compact = xml("res/layout/stock_widget_compact.xml")
        assertEquals("horizontal", compact.android("orientation"))
        compact.elements("TextView").filter { it.android("id") != "@+id/stock_cached" }
            .forEach { assertEquals("1", it.android("maxLines")) }
        val card = xml("res/layout/stock_widget_card.xml")
        val children = (0 until card.childNodes.length).map { card.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals("vertical", card.android("orientation"))
        val chartIndex = children.indexOfFirst { it.android("id") == "@+id/stock_chart" }
        val timeIndex = children.indexOfFirst { it.android("id") == "@+id/stock_quote_time" }
        assertTrue(chartIndex > timeIndex)
        assertEquals("ImageView", children[chartIndex].tagName)
        assertEquals("48dp", children[chartIndex].android("layout_height"))
    }

    @Test
    fun `dense mode uses bigger fonts in shorter borderless rows without vertical cell margins`() {
        val dense = xml("res/layout/stock_widget_dense.xml")
        val compact = xml("res/layout/stock_widget_compact.xml")
        assertEquals("18dp", dense.android("minHeight"))
        assertEquals("16dp", compact.android("minHeight"))
        assertEquals("@android:color/transparent", dense.android("background"))
        val labels = dense.elements("TextView").filter { it.android("id") != "@+id/stock_cached" }
        assertEquals(3, labels.size)
        labels.forEach {
            assertEquals("14sp", it.android("textSize"))
            assertEquals("", it.android("autoSizeTextType"))
            assertEquals("1", it.android("maxLines"))
            assertEquals("false", it.android("includeFontPadding"))
        }
        val row = xml("res/layout/stock_widget_dense_row.xml")
        row.elements("FrameLayout").forEach {
            assertEquals("", it.android("layout_margin"))
            assertEquals("", it.android("layout_marginTop"))
            assertEquals("", it.android("layout_marginBottom"))
            assertEquals("1", it.android("layout_weight"))
        }
        val list = xml("res/layout/stock_widget.xml").elements("ListView").single()
        assertEquals("@android:color/transparent", list.android("background"))
        assertEquals("@android:color/transparent", list.android("cacheColorHint"))
    }

    @Test
    fun `configuration offers independent density currency and transparency controls`() {
        val configure = xml("res/layout/stock_widget_configure_activity.xml")
        assertEquals(3, configure.elements("RadioButton").size)
        val ids = configure.elements("CheckBox").map { it.android("id") }
        assertTrue(ids.containsAll(listOf("@+id/stock_currency", "@+id/stock_transparent", "@+id/stock_columns")))
    }

    @Test
    fun `asset search is a private full-screen themed page with a top filter`() {
        val activity = xml("AndroidManifest.xml").elements("activity").single {
            it.android("name") == ".feature.stocks.StockAssetPickerActivity"
        }
        assertEquals("false", activity.android("exported"))
        val layout = xml("res/layout/stock_asset_picker_activity.xml")
        assertEquals("match_parent", layout.android("layout_height"))
        assertEquals("?android:attr/windowBackground", layout.android("background"))
        val search = layout.elements("EditText").single()
        assertEquals("@+id/stock_asset_search", search.android("id"))
        assertEquals("no", search.android("importantForAutofill"))
        val children = (0 until layout.childNodes.length).map { layout.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals("EditText", children[1].tagName)
        assertEquals(1, layout.elements("ListView").size)
        assertEquals("actionSearch", search.android("imeOptions"))
        assertEquals("@+id/stock_search_online", layout.elements("Button").single().android("id"))
    }

    @Test
    fun `both row layouts reserve a twelve dp gutter between equal-width columns`() {
        for (path in listOf("stock_widget_row.xml", "stock_widget_dense_row.xml")) {
            val row = xml("res/layout/$path")
            val children = (0 until row.childNodes.length).map { row.childNodes.item(it) }.filterIsInstance<Element>()
            assertEquals(listOf("FrameLayout", "TextView", "FrameLayout"), children.map { it.tagName })
            assertEquals("@+id/stock_column_gap", children[1].android("id"))
            assertEquals("${StockWidgetDisplay.COLUMN_GAP_DP}dp", children[1].android("layout_width"))
            assertEquals("no", children[1].android("importantForAccessibility"))
            assertEquals("1", children[0].android("layout_weight"))
            assertEquals("1", children[2].android("layout_weight"))
        }
    }

    @Test
    fun `details are private and use native chart and news views rather than an embedded browser`() {
        val activity = xml("AndroidManifest.xml").elements("activity").single {
            it.android("name") == ".feature.stocks.StockDetailActivity"
        }
        assertEquals("false", activity.android("exported"))
        val layout = xml("res/layout/stock_detail_activity.xml")
        assertEquals("?android:attr/windowBackground", layout.android("background"))
        assertEquals(1, layout.elements("ImageView").size)
        assertEquals(1, layout.elements("Spinner").size)
        assertTrue(layout.elements("WebView").isEmpty())
        assertTrue(layout.elements("LinearLayout").any { it.android("id") == "@+id/stock_detail_news" })
    }

    @Test
    fun `each headline has a separate accessible relevance-info action`() {
        val item = xml("res/layout/stock_news_item.xml")
        val indicator = item.elements("ImageButton").single()
        assertEquals("@+id/stock_news_mention", indicator.android("id"))
        assertEquals("48dp", indicator.android("layout_width"))
        assertEquals("48dp", indicator.android("layout_height"))
        assertEquals("@string/stock_news_mention_hint", indicator.android("contentDescription"))
        assertEquals("gone", indicator.android("visibility"))
    }

    @Test
    fun `single-line rows use no vertical padding and separators are two dp thick`() {
        for (name in listOf("stock_widget_compact.xml", "stock_widget_dense.xml")) {
            val row = xml("res/layout/$name")
            assertEquals("0dp", row.android("paddingTop"))
            assertEquals("0dp", row.android("paddingBottom"))
            assertEquals("wrap_content", row.android("layout_height"))
            row.elements("TextView").forEach { assertEquals("false", it.android("includeFontPadding")) }
        }
        val compact = xml("res/layout/stock_widget_compact.xml")
        assertTrue(compact.android("minHeight").removeSuffix("dp").toInt() <= 16)
        val border = xml("res/layout/stock_widget_group_row.xml")
        border.elements("TextView").forEach {
            val axis = if (it.android("id") in listOf("@+id/stock_group_top", "@+id/stock_group_bottom")) {
                "layout_height"
            } else "layout_width"
            assertEquals("2dp", it.android(axis))
        }
    }

    @Test
    fun `heading and separator share one horizontal row`() {
        val header = xml("res/layout/stock_widget_group_header.xml")
        assertEquals("horizontal", header.android("orientation"))
        assertEquals("center_vertical", header.android("gravity"))
        val children = (0 until header.childNodes.length).map { header.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(listOf("@+id/stock_group_title", "@+id/stock_group_rule"), children.map { it.android("id") })
        assertEquals("2dp", children[1].android("layout_height"))
        assertEquals("1", children[1].android("layout_weight"))
        assertEquals("1", children[0].android("maxLines"))
    }

    @Test
    fun `font slider is available and automatic fitting cannot override its chosen size`() {
        val configure = xml("res/layout/stock_widget_configure_activity.xml")
        assertEquals("@+id/stock_font_size", configure.elements("SeekBar").single().android("id"))
        for (name in listOf("stock_widget_compact.xml", "stock_widget_dense.xml", "stock_widget_card.xml")) {
            xml("res/layout/$name").elements("TextView").forEach {
                assertEquals("", it.android("autoSizeTextType"))
                assertEquals("wrap_content", it.android("layout_height"))
            }
        }
    }
}
