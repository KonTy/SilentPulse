package com.silentpulse.messenger.feature.stocks

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.silentpulse.messenger.R
import com.silentpulse.messenger.feature.stocks.data.StockQuote
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import java.util.Locale

class StockRowsTest {
    private val context = mock(Context::class.java)
    private lateinit var views: MockedConstruction<RemoteViews>
    private lateinit var intents: MockedConstruction<Intent>
    private lateinit var originalLocale: Locale
    private val layouts = mutableListOf<Int>()
    private val now = System.currentTimeMillis()
    private val quote = StockQuote("AAPL", "Apple Inc.", "USD", 101.0, 100.0, now - 60_000, emptyList())
    private var settings: StockWidgetSettings? = StockWidgetSettings(listOf("AAPL", "MSFT", "GC=F"))
    private var entry = CachedStockQuote(quote, now, now)

    @Before
    fun setup() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        `when`(context.packageName).thenReturn("com.silentpulse.messenger")
        `when`(context.getString(anyInt())).thenAnswer { invocation ->
            when (invocation.getArgument<Int>(0)) {
                R.string.stock_unavailable -> "Unavailable"
                R.string.stock_change_unavailable -> "Change unavailable"
                R.string.stock_dense_unknown -> "N/A"
                else -> "Waiting"
            }
        }
        `when`(context.getString(eq(R.string.stock_as_of), anyString())).thenReturn("Quote time")
        `when`(context.getString(eq(R.string.stock_cached_as_of), anyString())).thenReturn("Cached / quote time")
        views = mockConstruction(RemoteViews::class.java) { _, construction ->
            layouts.add(construction.arguments()[1] as Int)
        }
        intents = mockConstruction(Intent::class.java, withSettings().defaultAnswer(RETURNS_SELF))
    }

    @After
    fun tearDown() {
        intents.close()
        views.close()
        Locale.setDefault(originalLocale)
    }

    private fun factory(columns: Int = 1) = StockRows(context, 1, columns, { settings }, { entry },
        loadTheme = { StockWidgetTheme.load(context, followAppTheme = false, manualDark = it.dark) })
        .also { it.onCreate() }

    @Test
    fun `compact rows display symbol currency price and both signed changes`() {
        val factory = factory()
        assertEquals(3, factory.count)
        factory.getViewAt(0)
        val row = views.constructed()[0]
        val stock = views.constructed()[1]
        assertEquals(listOf(R.layout.stock_widget_dense_row, R.layout.stock_widget_compact), layouts)
        verify(stock).setTextViewText(R.id.stock_symbol, "AAPL")
        verify(stock).setTextViewText(R.id.stock_price, "USD 101.00")
        verify(stock).setTextViewText(R.id.stock_change, "+1.00 (+1.00%)")
        verify(stock).setViewVisibility(R.id.stock_cached, View.GONE)
        verify(row).setViewVisibility(R.id.stock_cell_right, View.GONE)
        verify(row).setViewVisibility(R.id.stock_column_gap, View.GONE)
    }

    @Test
    fun `multiline cards show company and stack changes into two lines`() {
        settings = settings!!.copy(multiline = true, charts = false, dark = true)
        val factory = factory(2)
        assertEquals(2, factory.count)
        factory.getViewAt(0)
        assertEquals(listOf(R.layout.stock_widget_row, R.layout.stock_widget_card, R.layout.stock_widget_card), layouts)
        val stock = views.constructed()[1]
        verify(stock).setTextViewText(R.id.stock_company, "Apple Inc.")
        verify(stock).setTextViewText(R.id.stock_change, "+1.00%\n+1.00")
        verify(stock).setTextColor(R.id.stock_price, StockWidgetDisplay.DARK_TEXT)
        verify(stock).setViewVisibility(R.id.stock_chart, View.GONE)
        verify(stock).setViewVisibility(R.id.stock_chart_empty, View.GONE)
    }

    @Test
    fun `enabled chart without samples is explicitly unavailable`() {
        settings = settings!!.copy(multiline = true)
        factory().getViewAt(0)
        verify(views.constructed()[1]).setViewVisibility(R.id.stock_chart_empty, View.VISIBLE)
        verify(views.constructed()[1]).setViewVisibility(R.id.stock_chart, View.GONE)
    }

    @Test
    fun `cached prices are labeled and absent prices never become zero`() {
        entry = entry.copy(failed = true)
        val factory = factory()
        factory.getViewAt(0)
        verify(views.constructed()[1]).setViewVisibility(R.id.stock_cached, View.VISIBLE)
        entry = CachedStockQuote(failed = true)
        factory.onDataSetChanged()
        factory.getViewAt(0)
        verify(views.constructed()[3]).setTextViewText(R.id.stock_price, "Unavailable")
        verify(views.constructed()[3]).setTextViewText(R.id.stock_change, "Change unavailable")
    }

    @Test
    fun `configuration removal empties rows and a short final grid row does not repeat symbols`() {
        val factory = factory(2)
        factory.getViewAt(1)
        assertEquals(listOf(R.layout.stock_widget_dense_row, R.layout.stock_widget_compact), layouts)
        verify(views.constructed()[1]).setTextViewText(R.id.stock_symbol, "GC=F")
        settings = null
        factory.onDataSetChanged()
        assertEquals(0, factory.count)
        assertNull(factory.getViewAt(0))
    }

    @Test
    fun `dense mode packs two stock lines per row without card borders or absolute changes`() {
        settings = settings!!.copy(dense = true, showCurrency = false)
        val factory = factory(2)
        assertEquals(2, factory.count)
        factory.getViewAt(0)
        assertEquals(listOf(R.layout.stock_widget_dense_row, R.layout.stock_widget_dense, R.layout.stock_widget_dense), layouts)
        val stock = views.constructed()[1]
        verify(stock).setTextViewText(R.id.stock_symbol, "AAPL")
        verify(stock).setTextViewText(R.id.stock_price, "101.00")
        verify(stock).setTextViewText(R.id.stock_change, "+1.00%")
        verify(stock).setInt(R.id.stock_item, "setBackgroundColor", 0)
        verify(stock).setTextColor(R.id.stock_change, StockWidgetDisplay.changeColor(1.0, false))
        verify(views.constructed()[0]).setViewVisibility(R.id.stock_column_gap, View.VISIBLE)
    }

    @Test
    fun `currency hiding and transparency apply to compact rows cards and dense mode`() {
        for (mode in listOf("compact", "cards", "dense")) {
            settings = settings!!.copy(multiline = mode == "cards", dense = mode == "dense",
                charts = false, showCurrency = false, transparent = true, dark = true)
            val index = views.constructed().size
            factory().getViewAt(0)
            val stock = views.constructed()[index + 1]
            verify(stock).setInt(R.id.stock_item, "setBackgroundColor", 0)
            verify(stock).setTextViewText(R.id.stock_price, "101.00")
            verify(stock).setTextColor(R.id.stock_symbol, StockWidgetDisplay.DARK_TEXT)
            verify(stock).setContentDescription(eq(R.id.stock_item), contains("USD"))
        }
    }

    @Test
    fun `dense rows use previous close for red green and neutral daily changes`() {
        settings = settings!!.copy(dense = true)
        for (previous in listOf(100.0, 102.0, 101.0)) {
            entry = entry.copy(quote = quote.copy(previousClose = previous))
            val index = views.constructed().size
            factory().getViewAt(0)
            val stock = views.constructed()[index + 1]
            verify(stock).setTextColor(R.id.stock_change, StockWidgetDisplay.changeColor(101.0 - previous, false))
            val percent = (101.0 - previous) / previous * 100.0
            verify(stock).setTextViewText(R.id.stock_change, StockWidgetDisplay.densePercent(percent, Locale.US))
        }
    }

    @Test
    fun `dense rows retain cached marker and show unknown rather than a fabricated flat change`() {
        settings = settings!!.copy(dense = true)
        entry = entry.copy(quote = quote.copy(previousClose = null), failed = true)
        factory().getViewAt(0)
        val stock = views.constructed()[1]
        verify(stock).setViewVisibility(R.id.stock_cached, View.VISIBLE)
        verify(stock).setTextViewText(R.id.stock_change, "N/A")
        verify(stock).setTextColor(R.id.stock_change, StockWidgetDisplay.LIGHT_TEXT)
    }

    @Test
    fun `group borders enclose complete groups including short final rows`() {
        val groups = listOf(StockGroup("Tech", listOf("AAPL", "MSFT", "GOOGL")), StockGroup("Gold", listOf("GC=F")))
        settings = settings!!.copy(symbols = StockWatchlist.symbols(groups), groups = groups,
            groupStyle = StockGroupStyle.BORDERS, dense = true, transparent = true)
        val factory = factory(2)
        assertEquals(5, factory.count)
        val header = factory.getViewAt(0)!!
        verify(header).setViewVisibility(R.id.stock_group_top, View.VISIBLE)
        verify(header).setViewVisibility(R.id.stock_group_bottom, View.GONE)
        val middle = factory.getViewAt(1)!!
        verify(middle).setViewVisibility(R.id.stock_group_top, View.GONE)
        verify(middle).setViewVisibility(R.id.stock_group_left, View.VISIBLE)
        verify(middle).setViewVisibility(R.id.stock_group_right, View.VISIBLE)
        val last = factory.getViewAt(2)!!
        verify(last).setViewVisibility(R.id.stock_group_bottom, View.VISIBLE)
        verify(last).setViewVisibility(R.id.stock_group_top, View.GONE)
    }

    @Test
    fun `separator mode draws the rule in the heading instead of above it`() {
        val groups = listOf(StockGroup("Tech", listOf("AAPL")), StockGroup("Gold", listOf("GC=F")))
        settings = settings!!.copy(symbols = StockWatchlist.symbols(groups), groups = groups)
        val factory = factory()
        val first = factory.getViewAt(0)!!
        verify(first).setViewVisibility(R.id.stock_group_top, View.GONE)
        verify(views.constructed()[0]).setViewVisibility(R.id.stock_group_rule, View.GONE)
        val nextHeader = views.constructed().size
        val second = factory.getViewAt(2)!!
        verify(second).setViewVisibility(R.id.stock_group_top, View.GONE)
        verify(views.constructed()[nextHeader]).setViewVisibility(R.id.stock_group_rule, View.VISIBLE)
        verify(second).setViewVisibility(R.id.stock_group_left, View.GONE)
        verify(second).setViewVisibility(R.id.stock_group_right, View.GONE)
        verify(second).setViewVisibility(R.id.stock_group_bottom, View.GONE)
    }

    @Test
    fun `app OLED theme overrides old manual colors without overriding transparency`() {
        val oled = StockThemeColors(0xff000000.toInt(), -1, -1, 0xff555555.toInt(), true)
        val factory = StockRows(context, 1, 1, { settings }, { entry }, { oled })
        factory.onCreate()
        factory.getViewAt(0)
        val stock = views.constructed()[1]
        verify(stock).setInt(R.id.stock_item, "setBackgroundColor", 0xff000000.toInt())
        verify(stock).setTextColor(R.id.stock_price, -1)
        settings = settings!!.copy(transparent = true)
        factory.onDataSetChanged()
        factory.getViewAt(0)
        verify(views.constructed()[3]).setInt(R.id.stock_item, "setBackgroundColor", 0)
    }

    @Test
    fun `saved currency toggle renders without units on the next widget update`() {
        val store = StockTestStore()
        val initial = StockWidgetSettings(listOf("AAPL"))
        store.widgets.save(1, initial)
        val factory = StockRows(context, 1, 1, { store.widgets.load(1) }, { entry },
            { StockWidgetTheme.load(context, followAppTheme = false) })
        factory.onCreate()
        factory.getViewAt(0)
        verify(views.constructed()[1]).setTextViewText(R.id.stock_price, "USD 101.00")

        store.widgets.updateAppearance(1, StockWidgetAppearance.from(initial).copy(showCurrency = false))
        factory.onDataSetChanged()
        factory.getViewAt(0)
        verify(views.constructed()[3]).setTextViewText(R.id.stock_price, "101.00")
        assertFalse(store.widgets.load(1)!!.showCurrency)
    }

    @Test
    fun `OLED separators use the visible secondary text color rather than the faint divider`() {
        val groups = listOf(StockGroup("Commodities-", listOf("GC=F")))
        settings = settings!!.copy(symbols = listOf("GC=F"), groups = groups, groupStyle = StockGroupStyle.NONE)
        val oled = StockThemeColors(0xff000000.toInt(), -1, 0xccffffff.toInt(), 0x1affffff, true)
        val factory = StockRows(context, 1, 1, { settings }, { entry }, { oled })
        factory.onCreate()
        val header = factory.getViewAt(0)!!
        verify(header).setViewVisibility(R.id.stock_group_top, View.GONE)
        verify(views.constructed()[0]).setViewVisibility(R.id.stock_group_rule, View.VISIBLE)
        verify(views.constructed()[0]).setInt(R.id.stock_group_rule, "setBackgroundColor", 0xccffffff.toInt())
        verify(views.constructed()[0], never()).setInt(R.id.stock_group_rule, "setBackgroundColor", 0x1affffff)
    }

    @Test
    fun `each column opens its own ticker and an empty recycled cell cannot be tapped`() {
        settings = settings!!.copy(dense = true)
        val factory = factory(2)
        val row = factory.getViewAt(0)!!
        val left = intents.constructed()[0]
        val right = intents.constructed()[1]
        verify(left).putExtra(StockDetailActivity.EXTRA_SYMBOL, "AAPL")
        verify(right).putExtra(StockDetailActivity.EXTRA_SYMBOL, "MSFT")
        verify(row).setOnClickFillInIntent(R.id.stock_cell_left, left)
        verify(row).setOnClickFillInIntent(R.id.stock_cell_right, right)
        val last = factory.getViewAt(1)!!
        verify(last).setViewVisibility(R.id.stock_cell_right, View.INVISIBLE)
        verify(last, never()).setOnClickFillInIntent(eq(R.id.stock_cell_right), any(Intent::class.java))
    }

    @Test
    fun `grouped cells bind clicks on the returned collection row and headings do not launch details`() {
        val groups = listOf(StockGroup("Tech", listOf("AAPL", "MSFT")))
        settings = settings!!.copy(symbols = StockWatchlist.symbols(groups), groups = groups, dense = true)
        val factory = factory(2)
        val header = factory.getViewAt(0)!!
        verify(header, never()).setOnClickFillInIntent(anyInt(), any(Intent::class.java))
        val row = factory.getViewAt(1)!!
        verify(row).setOnClickFillInIntent(R.id.stock_cell_left, intents.constructed()[0])
        verify(row).setOnClickFillInIntent(R.id.stock_cell_right, intents.constructed()[1])
    }

    @Test
    fun `marked group forces a separator even in headings-only mode`() {
        val groups = listOf(StockGroup("Stocks", listOf("AAPL")), StockGroup("Commodities-", listOf("GC=F")))
        settings = settings!!.copy(symbols = StockWatchlist.symbols(groups), groups = groups,
            groupStyle = StockGroupStyle.NONE)
        val factory = factory()
        verify(factory.getViewAt(0)!!).setViewVisibility(R.id.stock_group_top, View.GONE)
        val start = views.constructed().size
        val marked = factory.getViewAt(2)!!
        verify(marked).setViewVisibility(R.id.stock_group_top, View.GONE)
        verify(views.constructed()[start]).setViewVisibility(R.id.stock_group_rule, View.VISIBLE)
        verify(views.constructed()[start]).setInt(R.id.stock_group_rule, "setBackgroundColor", StockWidgetDisplay.LIGHT_TEXT)
        verify(views.constructed()[start]).setTextViewText(R.id.stock_group_title, "Commodities")
        verify(factory.getViewAt(3)!!).setViewVisibility(R.id.stock_group_top, View.GONE)
    }

    @Test
    fun `font slider scales the actual ticker price and change text`() {
        settings = settings!!.copy(dense = true, fontScalePercent = 150)
        factory().getViewAt(0)
        val stock = views.constructed()[1]
        for (id in listOf(R.id.stock_symbol, R.id.stock_price, R.id.stock_change)) {
            verify(stock).setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, 21f)
        }
    }
}
