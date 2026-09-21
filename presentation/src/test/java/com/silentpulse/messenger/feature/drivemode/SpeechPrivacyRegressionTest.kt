package com.silentpulse.messenger.feature.drivemode

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeechPrivacyRegressionTest {
    private val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .first { File(it, "settings.gradle").isFile }
    private val sources = File(root, "presentation/src/main/java")
    private val feature = File(sources, "com/silentpulse/messenger/feature")
    private fun drive(name: String) = File(feature, "drivemode/$name.kt").readText()
    private fun assistant(name: String) = File(feature, "assistant/$name.kt").readText()

    @Test fun `no generic recognizer creation or automatic model download exists`() {
        sources.walkTopDown().filter { it.extension in setOf("kt", "java") }.forEach {
            val source = it.readText()
            assertFalse(it.path, Regex("""SpeechRecognizer\s*\.\s*createSpeechRecognizer\s*\(""").containsMatchIn(source))
            assertFalse(it.path, Regex("""\.\s*triggerModelDownload\s*\(""").containsMatchIn(source))
        }
    }

    @Test fun `all direct Android speech submissions use shared offline guard`() {
        val directClients = setOf("AndroidTtsEngine", "DriveModeWidgetProvider", "VoiceAssistantService")
        sources.walkTopDown().filter { it.extension in setOf("kt", "java") }.forEach {
            val source = it.readText()
            if (Regex("""TextToSpeech\s*\(""").containsMatchIn(source)) {
                assertTrue("New direct TTS client needs review: ${it.path}", it.nameWithoutExtension in directClients)
                if (it.nameWithoutExtension == "VoiceAssistantService") {
                    assertTrue(it.path, source.contains("createPlatformEngine = { listener ->"))
                    assertTrue(it.path, source.contains("ttsInitializationListener = listener"))
                    assertTrue(it.path, source.contains("listener.onInit(status)"))
                } else {
                    assertTrue(it.path, source.contains("OfflineTts.speak("))
                }
                assertFalse(it.path, Regex("""(?:engine|tts|initialized)\??\s*\.\s*speak\s*\(""").containsMatchIn(source))
            }
            assertFalse("Implicit voice selection: ${it.path}", Regex("""\.\s*setLanguage\s*\(""").containsMatchIn(source))
        }
        assertTrue(drive("OfflineTts").contains("KEY_FEATURE_NOT_INSTALLED"))
        assertTrue(drive("OfflineTts").contains("isNetworkConnectionRequired"))
    }

    @Test fun `service and interactor cannot bypass preferred engine factories`() {
        val service = assistant("VoiceAssistantService")
        assertTrue(service.contains("appComponent.sttEngineFactory().create()"))
        assertFalse(service.contains("AndroidSttEngine("))
        assertTrue(drive("VoiceInteractor").contains("appComponent.ttsEngineFactory().createEngine"))
        assertFalse(drive("VoiceInteractor").contains("AndroidTtsEngine(context"))
        assertFalse(drive("SilentPulseNotificationListener").contains("cachedSttEngine is WhisperSttEngine"))
    }

    @Test fun `speech sources never persist queries or log payloads`() {
        val files = File(feature, "drivemode").listFiles()!!.filter { it.extension == "kt" } +
            listOf(File(feature, "assistant/VoiceAssistantService.kt"), File(feature, "assistant/VoskWakeWordDetector.kt"))
        val privateInterpolation = Regex("""\$(?:\{)?(?:text|transcript|partial|hypothesis|recognizedText|spokenText|schemaJson|replyText|messageBody|sender|recipientName|command|c|answer|bingQuery|leoQuery|modelPath|notificationKey|threadId)(?:\}|["\\ .,:])""")
        files.forEach { file ->
            file.readLines().filter { it.contains("Log.") || it.contains("Timber.") }.forEach { line ->
                assertFalse("${file.name}: $line", privateInterpolation.containsMatchIn(line))
            }
        }
        assertFalse(assistant("VoiceAssistantService").contains("writeText("))
        assertFalse(assistant("VoiceAssistantService").contains("saveDebugAnswer"))
    }

    @Test fun `native speech diagnostics do not print transcripts`() {
        assertTrue(drive("KokoroTtsEngine").contains("kokoro_privacy_unavailable"))
        assertFalse(drive("KokoroTtsEngine").contains("generateWithCallback("))
        assertFalse(drive("KokoroTtsEngine").contains("OfflineTts("))
        assertTrue(drive("VoskPrivacy").contains("vosk_set_log_level(-10)"))
        assertTrue(drive("VoskSttEngine").contains("VoskPrivacy.disableNativeLogging()"))
        assertTrue(assistant("VoskWakeWordDetector").contains("VoskPrivacy.disableNativeLogging()"))
        val native = File(root, "presentation/src/main/cpp/whisper/jni.c").readText()
        assertTrue(native.contains("whisper_log_set(discard_native_log, NULL)"))
        assertTrue(native.contains("ggml_log_set(discard_native_log, NULL)"))
        assertTrue(native.contains("params.print_realtime   = false"))
        assertTrue(native.contains("#ifndef NDEBUG"))
    }
}
