package com.moez.QKSMS.feature.drivemode

interface SttEngine {
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit)
    fun stopListening()
    fun shutdown()
}
