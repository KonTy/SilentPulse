package com.silentpulse.messenger.feature.drivemode

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.util.Locale

class OfflineTtsTest {
    private fun voice(name: String, locale: Locale = Locale.US, network: Boolean = false, missing: Boolean = false): Voice =
        mock(Voice::class.java).also {
            `when`(it.name).thenReturn(name)
            `when`(it.locale).thenReturn(locale)
            `when`(it.isNetworkConnectionRequired).thenReturn(network)
            `when`(it.features).thenReturn(if (missing) setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) else emptySet())
        }

    @Test fun `only installed offline voices qualify`() {
        val offline = voice("offline")
        val cloud = voice("cloud", network = true)
        val notInstalled = voice("missing", missing = true)
        assertSame(offline, OfflineTts.chooseVoice(setOf(cloud, notInstalled, offline), Locale.US))
        assertNull(OfflineTts.chooseVoice(setOf(cloud, notInstalled), Locale.US))
    }

    @Test fun `missing feature metadata is an error not proof of installation`() {
        val unknown = voice("unknown")
        `when`(unknown.features).thenReturn(null)
        assertNull(OfflineTts.chooseVoice(setOf(unknown), Locale.US))
    }

    @Test fun `unsafe or uninstalled voice is never selected to trigger a download`() {
        val engine = mock(TextToSpeech::class.java)
        val missing = voice("missing", missing = true)
        val network = voice("network", network = true)
        `when`(engine.voices).thenReturn(setOf(missing, network))
        assertEquals("tts_offline_voice_unavailable", OfflineTts.prepare(engine, Locale.US))
        verify(engine, never()).setVoice(any())
        verify(engine, never()).setLanguage(any())
    }

    @Test fun `exact locale precedes same language and never crosses language`() {
        val uk = voice("uk", Locale.UK)
        val us = voice("us")
        val french = voice("fr", Locale.FRANCE)
        assertSame(us, OfflineTts.chooseVoice(setOf(uk, us, french), Locale.US))
        assertSame(uk, OfflineTts.chooseVoice(setOf(uk, french), Locale.US))
        assertNull(OfflineTts.chooseVoice(setOf(us, french), Locale("ru")))
    }

    @Test fun `script-specific locale cannot choose incompatible script`() {
        val traditional = Locale.forLanguageTag("zh-Hant")
        val simplified = voice("simplified", Locale.forLanguageTag("zh-Hans"))
        assertNull(OfflineTts.chooseVoice(setOf(simplified), traditional))
    }

    @Test fun `failed selection never speaks using previous network voice`() {
        val engine = mock(TextToSpeech::class.java)
        val safe = voice("safe")
        val previous = voice("previous", network = true)
        `when`(engine.voices).thenReturn(setOf(safe))
        `when`(engine.voice).thenReturn(previous)
        `when`(engine.setVoice(safe)).thenReturn(TextToSpeech.ERROR)
        assertEquals("tts_voice_selection_failed", OfflineTts.speak(engine, Locale.US, "private", TextToSpeech.QUEUE_ADD, null, "id"))
        verify(engine, never()).speak(anyString(), anyInt(), isNull(), anyString())
    }

    @Test fun `successful selection must agree with actual offline voice`() {
        val engine = mock(TextToSpeech::class.java)
        val safe = voice("safe")
        val unsafe = voice("safe", network = true)
        `when`(engine.voices).thenReturn(setOf(safe))
        `when`(engine.setVoice(safe)).thenReturn(TextToSpeech.SUCCESS)
        `when`(engine.voice).thenReturn(unsafe)
        assertEquals("tts_voice_selection_failed", OfflineTts.speak(engine, Locale.US, "private", TextToSpeech.QUEUE_ADD, null, "id"))
        verify(engine, never()).speak(anyString(), anyInt(), isNull(), anyString())
    }

    @Test fun `every submission revalidates voice and locale`() {
        val engine = mock(TextToSpeech::class.java)
        val safe = voice("safe")
        `when`(engine.voices).thenReturn(setOf(safe))
        `when`(engine.setVoice(safe)).thenReturn(TextToSpeech.SUCCESS)
        `when`(engine.voice).thenReturn(safe)
        `when`(engine.speak(anyString(), anyInt(), isNull(), anyString())).thenReturn(TextToSpeech.SUCCESS)
        assertNull(OfflineTts.speak(engine, Locale.US, "first", TextToSpeech.QUEUE_ADD, null, "first"))
        assertEquals("tts_offline_voice_unavailable",
            OfflineTts.speak(engine, Locale("ru"), "second", TextToSpeech.QUEUE_ADD, null, "second"))
        `when`(engine.voices).thenReturn(emptySet())
        assertEquals("tts_offline_voice_unavailable",
            OfflineTts.speak(engine, Locale.US, "third", TextToSpeech.QUEUE_ADD, null, "third"))
        verify(engine, times(1)).speak(anyString(), anyInt(), isNull(), anyString())
        verify(engine, never()).setLanguage(any())
    }

    @Test fun `provider selection exceptions fail closed`() {
        val engine = mock(TextToSpeech::class.java)
        `when`(engine.voices).thenThrow(IllegalStateException("private provider text"))
        assertEquals("tts_voice_selection_failed",
            OfflineTts.speak(engine, Locale.US, "private", TextToSpeech.QUEUE_ADD, null, "id"))
        verify(engine, never()).speak(anyString(), anyInt(), isNull(), anyString())
    }
}
