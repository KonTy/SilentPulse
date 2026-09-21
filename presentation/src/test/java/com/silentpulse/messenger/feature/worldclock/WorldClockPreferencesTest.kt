package com.silentpulse.messenger.feature.worldclock

import android.content.SharedPreferences
import com.silentpulse.messenger.common.util.CityTimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class WorldClockPreferencesTest {

    private val values = mutableMapOf<String, Any>()
    private val sharedPreferences = mock(SharedPreferences::class.java)
    private val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
    private val preferences = WorldClockPreferences(sharedPreferences)
    private val london = WorldClockSettings(CityTimeZone("London", "Europe/London"))
    private val tokyo = WorldClockSettings(CityTimeZone("Tokyo", "Asia/Tokyo"), darkText = true)

    @Before
    fun setUp() {
        `when`(sharedPreferences.edit()).thenReturn(editor)
        doAnswer { values[it.getArgument<String>(0)] as String? }
            .`when`(sharedPreferences).getString(anyString(), isNull())
        doAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
            .`when`(sharedPreferences).getBoolean(anyString(), anyBoolean())
        doAnswer {
            values[it.getArgument(0)] = it.getArgument<String>(1)
            editor
        }.`when`(editor).putString(anyString(), anyString())
        doAnswer {
            values[it.getArgument(0)] = it.getArgument<Boolean>(1)
            editor
        }.`when`(editor).putBoolean(anyString(), anyBoolean())
        doAnswer {
            values.remove(it.getArgument<String>(0))
            editor
        }.`when`(editor).remove(anyString())
    }

    @Test
    fun `new widget requires city selection`() {
        assertNull(preferences.load(1))
    }

    @Test
    fun `two clocks keep independent cities and text colors across reloads`() {
        preferences.save(1, london)
        preferences.save(2, tokyo)

        val reloaded = WorldClockPreferences(sharedPreferences)
        assertEquals(london, reloaded.load(1))
        assertEquals(tokyo, reloaded.load(2))
    }

    @Test
    fun `reconfiguring one clock does not change another`() {
        preferences.save(1, london)
        preferences.save(2, tokyo)
        val seattle = WorldClockSettings(CityTimeZone("Seattle", "America/Los_Angeles"))
        preferences.save(1, seattle)

        assertEquals(seattle, preferences.load(1))
        assertEquals(tokyo, preferences.load(2))
    }

    @Test
    fun `deleting one clock preserves the remaining clock`() {
        preferences.save(1, london)
        preferences.save(2, tokyo)
        preferences.remove(1)

        assertNull(preferences.load(1))
        assertEquals(tokyo, preferences.load(2))
    }

    @Test
    fun `restoring clocks remaps IDs without losing overlapping configurations`() {
        preferences.save(1, london)
        preferences.save(2, tokyo)
        preferences.restore(intArrayOf(1, 2), intArrayOf(2, 3))

        assertNull(preferences.load(1))
        assertEquals(london, preferences.load(2))
        assertEquals(tokyo, preferences.load(3))
    }

    @Test
    fun `restoring an unconfigured clock does not invent a city`() {
        preferences.save(1, london)
        preferences.restore(intArrayOf(1, 2), intArrayOf(3, 4))

        assertEquals(london, preferences.load(3))
        assertNull(preferences.load(4))
    }

    @Test
    fun `corrupt time zones require selection instead of silently using GMT`() {
        values["clock_1.city"] = "Invalid city"
        values["clock_1.zone"] = "Not/A_Real_Zone"

        assertNull(preferences.load(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid time zone cannot be saved`() {
        preferences.save(1, WorldClockSettings(CityTimeZone("Invalid", "Not/A_Real_Zone")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid widget ID cannot overwrite settings`() {
        preferences.save(0, london)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `restore requires paired IDs`() {
        preferences.restore(intArrayOf(1), intArrayOf(2, 3))
    }
}
