package com.silentpulse.messenger.feature.drivemode

import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.util.Locale

class AndroidTtsEngineTest {
    private class Fixture {
        val platform = mock(TextToSpeech::class.java)
        val handler = mock(Handler::class.java)
        val delayed = mutableListOf<Runnable>()
        val messages = mutableListOf<String>()
        val initialized = mutableListOf<Boolean>()
        lateinit var init: TextToSpeech.OnInitListener
        val engine: AndroidTtsEngine

        init {
            `when`(handler.post(any(Runnable::class.java))).thenAnswer {
                it.getArgument<Runnable>(0).run()
                true
            }
            `when`(handler.postDelayed(any(Runnable::class.java), anyLong())).thenAnswer {
                delayed.add(it.getArgument(0))
                true
            }
            doAnswer {
                delayed.remove(it.getArgument<Runnable>(0))
                null
            }.`when`(handler).removeCallbacks(any(Runnable::class.java))
            engine = AndroidTtsEngine(handler, initialized::add, messages::add) {
                init = it
                platform
            }
        }

        fun ready() {
            val voice = mock(Voice::class.java)
            `when`(voice.name).thenReturn("offline")
            `when`(voice.locale).thenReturn(Locale.getDefault())
            `when`(voice.features).thenReturn(emptySet())
            `when`(platform.voices).thenReturn(setOf(voice))
            `when`(platform.voice).thenReturn(voice)
            init.onInit(TextToSpeech.SUCCESS)
            assertTrue(engine.isReady)
        }
    }

    @Test fun `unavailable offline voice signals initialization and speech failure only`() {
        val f = Fixture()
        `when`(f.platform.voices).thenReturn(emptySet())
        f.init.onInit(TextToSpeech.SUCCESS)
        assertEquals(listOf(false), f.initialized)
        assertFalse(f.engine.isReady)
        val errors = mutableListOf<String>()
        f.engine.speak("synthetic", errors::add) { error("must not continue workflow") }
        assertEquals(listOf("tts_offline_voice_unavailable"), errors)
        verify(f.platform, never()).speak(anyString(), anyInt(), isNull(), anyString())
    }

    @Test fun `locale failure clears readiness and cannot reuse previous voice`() {
        val f = Fixture()
        f.ready()
        assertFalse(f.engine.setLocale(Locale.forLanguageTag("zz")))
        assertFalse(f.engine.isReady)
        val errors = mutableListOf<String>()
        f.engine.speak("synthetic", errors::add) { error("must not continue workflow") }
        assertEquals(listOf("tts_offline_voice_unavailable"), errors)
        verify(f.platform, never()).speak(anyString(), anyInt(), isNull(), anyString())
    }

    @Test fun `platform synchronous failure never calls success callback`() {
        val f = Fixture()
        f.ready()
        `when`(f.platform.speak(anyString(), anyInt(), isNull(), anyString())).thenReturn(TextToSpeech.ERROR)
        val errors = mutableListOf<String>()
        f.engine.speak("synthetic", errors::add) { error("must not continue workflow") }
        assertEquals(listOf("tts_synthesis_failed"), errors)
    }

    @Test fun `platform asynchronous failure consumes callback exactly once`() {
        val f = Fixture()
        f.ready()
        val errors = mutableListOf<String>()
        f.engine.speak("synthetic", errors::add) { error("must not continue workflow") }
        val listener = ArgumentCaptor.forClass(UtteranceProgressListener::class.java)
        val id = ArgumentCaptor.forClass(String::class.java)
        verify(f.platform).setOnUtteranceProgressListener(listener.capture())
        verify(f.platform).speak(anyString(), anyInt(), isNull(), id.capture())
        listener.value.onError(id.value, TextToSpeech.ERROR_NETWORK)
        listener.value.onDone(id.value)
        assertEquals(listOf("tts_synthesis_failed"), errors)
        assertFalse(f.engine.isReady)
    }

    @Test fun `successful speech completes exactly once`() {
        val f = Fixture()
        f.ready()
        var completed = 0
        f.engine.speak("synthetic", { error("unexpected failure") }) { completed++ }
        val listener = ArgumentCaptor.forClass(UtteranceProgressListener::class.java)
        val id = ArgumentCaptor.forClass(String::class.java)
        verify(f.platform).setOnUtteranceProgressListener(listener.capture())
        verify(f.platform).speak(anyString(), anyInt(), isNull(), id.capture())
        listener.value.onDone(id.value)
        listener.value.onDone(id.value)
        assertEquals(1, completed)
    }

    @Test fun `init timeout fails closed and ignores late success`() {
        val f = Fixture()
        f.delayed.single().run()
        assertEquals(listOf(false), f.initialized)
        f.init.onInit(TextToSpeech.SUCCESS)
        assertFalse(f.engine.isReady)
        assertEquals(listOf(false), f.initialized)
        verify(f.platform).shutdown()
    }
}
