package com.silentpulse.messenger

import com.silentpulse.messenger.data.BuildConfig
import com.silentpulse.messenger.manager.AnalyticsManagerImpl
import com.silentpulse.messenger.manager.ReferralManagerImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import timber.log.Timber

class TelemetryDisabledTest {
    @Test
    fun `analytics does not inspect or log event properties`() {
        val privateValue = object {
            override fun toString(): String = error("Telemetry must not inspect private values")
        }
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                fail("Telemetry must not produce diagnostics")
            }
        }
        Timber.plant(tree)
        try {
            AnalyticsManagerImpl().apply {
                track("synthetic-event", "private" to privateValue)
                setUserProperty("synthetic-property", privateValue)
            }
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `referral tracking completes without a context or preferences`() = runBlocking {
        ReferralManagerImpl().trackReferrer()
        assertEquals(0, ReferralManagerImpl::class.java.declaredConstructors.single().parameterCount)
    }

    @Test
    fun `analytics credentials and SDKs are absent in every flavor`() {
        assertFalse(BuildConfig::class.java.declaredFields.any { it.name == "AMPLITUDE_API_KEY" })
        for (name in listOf("com.amplitude.api.Amplitude", "com.android.installreferrer.api.InstallReferrerClient")) {
            assertThrows(ClassNotFoundException::class.java) { Class.forName(name) }
        }
    }
}
