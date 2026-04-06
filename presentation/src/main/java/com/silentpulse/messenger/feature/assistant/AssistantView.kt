package com.silentpulse.messenger.feature.assistant

import com.silentpulse.messenger.common.base.QkViewContract

interface AssistantView : QkViewContract<AssistantState> {
    fun showTimeoutPicker(currentSecs: Int)
    fun showTtsEnginePicker(current: String)
}
