package com.silentpulse.messenger.feature.stocks

import android.content.SharedPreferences
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

internal class StockTestStore {
    val preferences = mock(SharedPreferences::class.java)
    val values = mutableMapOf<String, Any>()
    val cache = StockQuoteCache(preferences)
    val widgets = StockWidgetPreferences(preferences)
    private val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)

    init {
        `when`(preferences.edit()).thenReturn(editor)
        doAnswer { values.toMap() }.`when`(preferences).all
        doAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<String?>(1) }
            .`when`(preferences).getString(anyString(), nullable(String::class.java))
        doAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
            .`when`(preferences).getBoolean(anyString(), anyBoolean())
        doAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Int>(1) }
            .`when`(preferences).getInt(anyString(), anyInt())
        doAnswer { values[it.getArgument(0)] = it.getArgument<String>(1); editor }
            .`when`(editor).putString(anyString(), anyString())
        doAnswer { values[it.getArgument(0)] = it.getArgument<Boolean>(1); editor }
            .`when`(editor).putBoolean(anyString(), anyBoolean())
        doAnswer { values[it.getArgument(0)] = it.getArgument<Int>(1); editor }
            .`when`(editor).putInt(anyString(), anyInt())
        doAnswer { values.remove(it.getArgument<String>(0)); editor }
            .`when`(editor).remove(anyString())
    }
}
