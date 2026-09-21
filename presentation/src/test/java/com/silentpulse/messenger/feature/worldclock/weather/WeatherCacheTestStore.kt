package com.silentpulse.messenger.feature.worldclock.weather

import android.content.SharedPreferences
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

internal class WeatherCacheTestStore {
    val preferences = mock(SharedPreferences::class.java)
    val values = mutableMapOf<String, Any>()
    val cache = ClockWeatherCache(preferences)
    private val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)

    init {
        `when`(preferences.edit()).thenReturn(editor)
        doAnswer { values[it.getArgument<String>(0)] }
            .`when`(preferences).getString(anyString(), isNull())
        doAnswer { values.containsKey(it.getArgument<String>(0)) }
            .`when`(preferences).contains(anyString())
        doAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
            .`when`(preferences).getBoolean(anyString(), anyBoolean())
        doAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Int>(1) }
            .`when`(preferences).getInt(anyString(), anyInt())
        doAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Long>(1) }
            .`when`(preferences).getLong(anyString(), anyLong())
        doAnswer { values[it.getArgument(0)] = it.getArgument<String>(1); editor }
            .`when`(editor).putString(anyString(), anyString())
        doAnswer { values[it.getArgument(0)] = it.getArgument<Boolean>(1); editor }
            .`when`(editor).putBoolean(anyString(), anyBoolean())
        doAnswer { values[it.getArgument(0)] = it.getArgument<Int>(1); editor }
            .`when`(editor).putInt(anyString(), anyInt())
        doAnswer { values[it.getArgument(0)] = it.getArgument<Long>(1); editor }
            .`when`(editor).putLong(anyString(), anyLong())
        doAnswer { values.clear(); editor }.`when`(editor).clear()
    }
}
