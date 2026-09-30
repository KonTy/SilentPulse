package com.silentpulse.messenger.feature.stocks

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.silentpulse.messenger.R
import com.silentpulse.messenger.manager.WidgetManager
import com.silentpulse.messenger.manager.WidgetManagerImpl
import com.silentpulse.messenger.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.withSettings
import org.mockito.Mockito.`when`

class StockWidgetThemeTest {
    private val light = StockThemeColors(
        0xffffffff.toInt(), 0xff49555f.toInt(), 0xff70808d.toInt(), 0x0c000000, false
    )
    private val dark = StockThemeColors(
        0xff192025.toInt(), 0xffffffff.toInt(), 0xccffffff.toInt(), 0x1affffff, true
    )
    private val oled = dark.copy(background = 0xff000000.toInt())
    private val manualLight = StockThemeColors(
        0xfff4f2fa.toInt(), 0xff202124.toInt(), 0xff202124.toInt(), 0xffe0dde7.toInt(), false
    )
    private val manualDark = StockThemeColors(
        0xff252830.toInt(), 0xfff2f3f5.toInt(), 0xfff2f3f5.toInt(), 0xff464b55.toInt(), true
    )
    private val modes = listOf(
        Preferences.NIGHT_MODE_SYSTEM, Preferences.NIGHT_MODE_OFF,
        Preferences.NIGHT_MODE_ON, Preferences.NIGHT_MODE_OLED
    )

    @Test
    fun `explicit modes match app colors regardless of the system theme or manual selection`() {
        for ((mode, expected) in listOf(
            Preferences.NIGHT_MODE_OFF to light,
            Preferences.NIGHT_MODE_ON to dark,
            Preferences.NIGHT_MODE_OLED to oled
        )) {
            for (systemDark in listOf(false, true)) {
                for (manualDark in listOf(false, true)) {
                    assertEquals(expected, StockWidgetTheme.resolve(
                        mode, systemDark, manualDark = manualDark, color = ::appColor
                    ))
                }
            }
        }
    }

    @Test
    fun `OLED always uses opaque pure black rather than the dark or legacy stock background`() {
        for (systemDark in listOf(false, true)) {
            val colors = StockWidgetTheme.resolve(
                Preferences.NIGHT_MODE_OLED, systemDark, color = ::appColor
            )
            assertEquals(0xff000000.toInt(), colors.background)
            assertEquals(oled, colors)
        }
    }

    @Test
    fun `follow system resolves the current UI night mode rather than a cached night preference`() {
        for (manualDark in listOf(false, true)) {
            assertEquals(light, StockWidgetTheme.resolve(
                Preferences.NIGHT_MODE_SYSTEM, false, manualDark = manualDark, color = ::appColor
            ))
            assertEquals(dark, StockWidgetTheme.resolve(
                Preferences.NIGHT_MODE_SYSTEM, true, manualDark = manualDark, color = ::appColor
            ))
        }
    }

    @Test
    fun `manual selection preserves both legacy palettes independently of every app mode`() {
        for (mode in modes) {
            for (systemDark in listOf(false, true)) {
                for ((manualDark, expected) in listOf(false to manualLight, true to this.manualDark)) {
                    assertEquals(expected, StockWidgetTheme.resolve(
                        mode, systemDark, followAppTheme = false, manualDark = manualDark
                    ) { error("Manual colors must not depend on app resources") })
                }
            }
        }
    }

    @Test
    fun `manual loading does not require an initialized app component`() {
        val context = mock(Context::class.java)
        assertEquals(manualLight, StockWidgetTheme.load(context, followAppTheme = false))
        assertEquals(manualDark, StockWidgetTheme.load(context, followAppTheme = false, manualDark = true))
        verifyNoInteractions(context)
    }

    @Test
    fun `theme updates send SMS IDs only to the SMS provider and notify stocks without IDs`() {
        checkThemeBroadcasts(intArrayOf(12, 34))
    }

    @Test
    fun `theme updates still notify stocks when there are no SMS widgets`() {
        checkThemeBroadcasts(intArrayOf())
    }

    private fun checkThemeBroadcasts(smsIds: IntArray) {
        val context = mock(Context::class.java)
        val manager = mock(AppWidgetManager::class.java)
        `when`(context.packageName).thenReturn("com.silentpulse.messenger")
        `when`(manager.getAppWidgetIds(any(ComponentName::class.java))).thenReturn(smsIds)
        val intentActions = mutableListOf<String>()
        mockStatic(AppWidgetManager::class.java).use { managers ->
            managers.`when`<AppWidgetManager> { AppWidgetManager.getInstance(context) }.thenReturn(manager)
            mockConstruction(ComponentName::class.java) { _, construction ->
                assertEquals(listOf(
                    "com.silentpulse.messenger", "com.silentpulse.messenger.feature.widget.WidgetProvider"
                ), construction.arguments())
            }.use { components ->
                mockConstruction(Intent::class.java, withSettings().defaultAnswer(RETURNS_SELF)) { _, construction ->
                    intentActions.add(construction.arguments().single() as String)
                }.use { intents ->
                    WidgetManagerImpl(context).updateTheme()
                    val broadcasts = intents.constructed()
                    if (smsIds.isNotEmpty()) {
                        assertEquals(listOf(
                            AppWidgetManager.ACTION_APPWIDGET_UPDATE, WidgetManager.ACTION_STOCK_THEME_CHANGED
                        ), intentActions)
                        verify(broadcasts[0]).setComponent(components.constructed().single())
                        verify(broadcasts[0]).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, smsIds)
                        // Mockito verification returns null, not the fluent Intent promised by Android annotations.
                        Unit
                    } else {
                        assertEquals(listOf(WidgetManager.ACTION_STOCK_THEME_CHANGED), intentActions)
                    }
                    val stockBroadcast = broadcasts.last()
                    verify(stockBroadcast).setPackage(context.packageName)
                    verify(stockBroadcast, never()).setComponent(any(ComponentName::class.java))
                    verify(stockBroadcast, never()).putExtra(anyString(), any(IntArray::class.java))
                    verify(stockBroadcast, never()).putExtra(anyString(), anyInt())
                    broadcasts.forEach { verify(context).sendBroadcast(it) }
                    verify(manager).getAppWidgetIds(components.constructed().single())
                }
            }
        }
    }

    private fun appColor(id: Int): Int = when (id) {
        R.color.backgroundLight -> light.background
        R.color.backgroundDark -> dark.background
        R.color.black -> oled.background
        R.color.textPrimary -> light.text
        R.color.textPrimaryDark -> dark.text
        R.color.textSecondary -> light.secondaryText
        R.color.textSecondaryDark -> dark.secondaryText
        R.color.separatorLight -> light.separator
        R.color.separatorDark -> dark.separator
        else -> error("Unexpected app theme color")
    }
}
