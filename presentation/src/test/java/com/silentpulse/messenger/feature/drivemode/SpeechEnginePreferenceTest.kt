package com.silentpulse.messenger.feature.drivemode

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class SpeechEnginePreferenceTest {
    @Test fun `selected STT preference is preserved including Whisper`() {
        for (preferred in listOf("android", "vosk", "whisper")) {
            val engine = mock(SttEngine::class.java)
            fun selected(candidate: String): SttEngine {
                assertEquals(preferred, candidate)
                return engine
            }
            assertSame(engine, SttEngineFactory.select(preferred,
                { selected("android") }, { selected("vosk") }, { selected("whisper") }))
        }
    }

    @Test fun `unavailable selected model does not invoke another engine or success callback`() {
        val unavailable = UnavailableSttEngine("whisper_model_not_found")
        val engine = SttEngineFactory.select("whisper", { error("no fallback") }, { error("no fallback") }, { unavailable })
        var reported: String? = null
        engine.startListening({ error("no false transcript") }, { reported = it })
        assertEquals("whisper_model_not_found", reported)
    }

    @Test fun `unknown preference fails closed`() {
        val engine = SttEngineFactory.select("unexpected", { error("no fallback") }, { error("no fallback") }, { error("no fallback") })
        var reported: String? = null
        engine.startListening({ error("no false transcript") }, { reported = it })
        assertEquals("stt_unknown_engine", reported)
    }

    @Test fun `TTS factory respects Android and Kokoro preferences`() {
        val engine = mock(TtsEngine::class.java)
        assertSame(engine, TtsEngineFactory.select("android", { engine }, { error("must not force Kokoro") }))
        assertSame(engine, TtsEngineFactory.select("kokoro", { error("must not bypass preference") }, { engine }))
    }

    @Test fun `unverifiable Kokoro text logging fails with error instead of false completion`() {
        val engine = KokoroTtsEngine()
        var error: String? = null
        engine.speak("synthetic test input", onError = { error = it }, onDone = { error("must not succeed") })
        assertFalse(engine.isReady)
        assertEquals("kokoro_privacy_unavailable", error)
    }
}
