package com.silentpulse.messenger.feature.drivemode

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.util.concurrent.Executor

class AndroidSttEngineTest {
    private class MainQueue {
        val handler = mock(Handler::class.java)
        val delayed = mutableListOf<Runnable>()
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
        }
    }

    @Test fun `creation unavailable invokes error only`() {
        val queue = MainQueue()
        val engine = AndroidSttEngine(queue.handler, 31) {
            OnDeviceRecognition.Creation.Unavailable("on_device_unavailable")
        }
        val failures = mutableListOf<String>()
        engine.startListening({ error("must not succeed") }, failures::add)
        assertEquals(listOf("on_device_unavailable"), failures)
        assertTrue(queue.delayed.isEmpty())
    }

    @Test fun `Android 33 waits for an available installed language check`() {
        mockConstruction(Intent::class.java).use {
            val queue = MainQueue()
            val sr = mock(SpeechRecognizer::class.java)
            val engine = AndroidSttEngine(queue.handler, 33) { OnDeviceRecognition.Creation.Ready(sr) }
            val failures = mutableListOf<String>()
            engine.startListening({ error("no transcript yet") }, failures::add)
            verify(sr, never()).startListening(any(Intent::class.java))
            val callback = ArgumentCaptor.forClass(RecognitionSupportCallback::class.java)
            verify(sr).checkRecognitionSupport(any(Intent::class.java), any(Executor::class.java), callback.capture())
            val support = mock(RecognitionSupport::class.java)
            `when`(support.installedOnDeviceLanguages).thenReturn(listOf("en-US"))
            callback.value.onSupportResult(support)
            verify(sr).startListening(any(Intent::class.java))
            assertTrue(failures.isEmpty())
            assertTrue(queue.delayed.isEmpty())
        }
    }

    @Test fun `available for download or pending models never start recognition`() {
        mockConstruction(Intent::class.java).use {
            val queue = MainQueue()
            val sr = mock(SpeechRecognizer::class.java)
            val engine = AndroidSttEngine(queue.handler, 33) { OnDeviceRecognition.Creation.Ready(sr) }
            val failures = mutableListOf<String>()
            engine.startListening({ error("must not succeed") }, failures::add)
            val callback = ArgumentCaptor.forClass(RecognitionSupportCallback::class.java)
            verify(sr).checkRecognitionSupport(any(Intent::class.java), any(Executor::class.java), callback.capture())
            val support = mock(RecognitionSupport::class.java)
            `when`(support.installedOnDeviceLanguages).thenReturn(emptyList())
            `when`(support.pendingOnDeviceLanguages).thenReturn(listOf("en-US"))
            `when`(support.supportedOnDeviceLanguages).thenReturn(listOf("en-US"))
            callback.value.onSupportResult(support)
            assertEquals(listOf("on_device_language_unavailable"), failures)
            verify(sr, never()).startListening(any(Intent::class.java))
            verify(sr, never()).triggerModelDownload(any(Intent::class.java))
        }
    }

    @Test fun `unsupported model check and timeout use the same on-device recognizer`() {
        mockConstruction(Intent::class.java).use {
            for (timeout in listOf(false, true)) {
                val queue = MainQueue()
                val sr = mock(SpeechRecognizer::class.java)
                var creations = 0
                val engine = AndroidSttEngine(queue.handler, 36) {
                    creations++
                    OnDeviceRecognition.Creation.Ready(sr)
                }
                val failures = mutableListOf<String>()
                engine.startListening({ error("no transcript yet") }, failures::add)
                val callback = ArgumentCaptor.forClass(RecognitionSupportCallback::class.java)
                verify(sr).checkRecognitionSupport(any(Intent::class.java), any(Executor::class.java), callback.capture())
                if (timeout) queue.delayed.single().run() else {
                    callback.value.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
                }
                verify(sr).startListening(any(Intent::class.java))
                verify(sr, never()).triggerModelDownload(any(Intent::class.java))
                assertEquals(1, creations)
                assertTrue(failures.isEmpty())
                assertTrue(queue.delayed.isEmpty())

                // Late support callbacks must not restart or cancel actual recognition.
                callback.value.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                callback.value.onSupportResult(mock(RecognitionSupport::class.java))
                verify(sr, times(1)).startListening(any(Intent::class.java))
                assertTrue(failures.isEmpty())
            }
        }
    }

    @Test fun `unimplemented model check still starts on-device recognition`() {
        mockConstruction(Intent::class.java).use {
            val queue = MainQueue()
            val sr = mock(SpeechRecognizer::class.java)
            doThrow(UnsupportedOperationException()).`when`(sr).checkRecognitionSupport(
                any(Intent::class.java), any(Executor::class.java), any(RecognitionSupportCallback::class.java)
            )
            val engine = AndroidSttEngine(queue.handler, 33) { OnDeviceRecognition.Creation.Ready(sr) }
            engine.startListening({ error("no transcript yet") }, { error("unexpected failure: $it") })
            verify(sr).startListening(any(Intent::class.java))
            verify(sr, never()).triggerModelDownload(any(Intent::class.java))
            assertTrue(queue.delayed.isEmpty())
        }
    }

    @Test fun `real model check failures never start recognition`() {
        mockConstruction(Intent::class.java).use {
            for (failureCode in listOf(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_SERVER)) {
                val queue = MainQueue()
                val sr = mock(SpeechRecognizer::class.java)
                val engine = AndroidSttEngine(queue.handler, 33) { OnDeviceRecognition.Creation.Ready(sr) }
                val failures = mutableListOf<String>()
                engine.startListening({ error("must not succeed") }, failures::add)
                val callback = ArgumentCaptor.forClass(RecognitionSupportCallback::class.java)
                verify(sr).checkRecognitionSupport(any(Intent::class.java), any(Executor::class.java), callback.capture())
                callback.value.onError(failureCode)
                assertEquals(listOf(OnDeviceRecognition.errorCode(failureCode)), failures)
                verify(sr, never()).startListening(any(Intent::class.java))
                assertTrue(queue.delayed.isEmpty())
            }
        }
    }

    @Test fun `recognition still reports missing language after an unsupported preflight`() {
        mockConstruction(Intent::class.java).use {
            val queue = MainQueue()
            val sr = mock(SpeechRecognizer::class.java)
            val engine = AndroidSttEngine(queue.handler, 36) { OnDeviceRecognition.Creation.Ready(sr) }
            val failures = mutableListOf<String>()
            engine.startListening({ error("must not succeed") }, failures::add)
            val support = ArgumentCaptor.forClass(RecognitionSupportCallback::class.java)
            verify(sr).checkRecognitionSupport(any(Intent::class.java), any(Executor::class.java), support.capture())
            support.value.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            val listener = ArgumentCaptor.forClass(RecognitionListener::class.java)
            verify(sr).setRecognitionListener(listener.capture())
            listener.value.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            assertEquals(listOf("on_device_language_unavailable"), failures)
            verify(sr, times(1)).startListening(any(Intent::class.java))
            verify(sr, never()).triggerModelDownload(any(Intent::class.java))
            assertTrue(queue.delayed.isEmpty())
        }
    }

    @Test fun `Android 31 model error discards buffered transcript`() {
        mockConstruction(Intent::class.java).use {
            val queue = MainQueue()
            val sr = mock(SpeechRecognizer::class.java)
            val engine = AndroidSttEngine(queue.handler, 31) { OnDeviceRecognition.Creation.Ready(sr) }
            val failures = mutableListOf<String>()
            engine.startListening({ error("must not submit partial command") }, failures::add)
            val listener = ArgumentCaptor.forClass(RecognitionListener::class.java)
            verify(sr).setRecognitionListener(listener.capture())
            val result = mock(Bundle::class.java)
            `when`(result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)).thenReturn(arrayListOf("synthetic test input"))
            listener.value.onResults(result)
            listener.value.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            queue.delayed.toList().forEach(Runnable::run)
            assertEquals(listOf("on_device_language_unavailable"), failures)
            verify(sr, times(1)).startListening(any(Intent::class.java))
        }
    }

    @Test fun `stopping during model check ignores a late callback`() {
        mockConstruction(Intent::class.java).use {
            val queue = MainQueue()
            val sr = mock(SpeechRecognizer::class.java)
            val engine = AndroidSttEngine(queue.handler, 33) { OnDeviceRecognition.Creation.Ready(sr) }
            engine.startListening({ error("stopped") }, { error("stopped") })
            val callback = ArgumentCaptor.forClass(RecognitionSupportCallback::class.java)
            verify(sr).checkRecognitionSupport(any(Intent::class.java), any(Executor::class.java), callback.capture())
            val timeout = queue.delayed.single()
            engine.stopListening()
            timeout.run()
            callback.value.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            val support = mock(RecognitionSupport::class.java)
            `when`(support.installedOnDeviceLanguages).thenReturn(listOf("en-US"))
            callback.value.onSupportResult(support)
            verify(sr, never()).startListening(any(Intent::class.java))
            assertTrue(queue.delayed.isEmpty())
        }
    }
}
