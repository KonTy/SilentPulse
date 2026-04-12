package com.silentpulse.messenger

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static regression tests for Android 14+ background navigation launch handling.
 *
 * These tests intentionally inspect source so BAL / overlay / TTS guardrails do not
 * silently regress during future refactors.
 */
class NavigationBackgroundLaunchRegressionTest {

    private val projectRoot = findProjectRoot()
    private val navigationHandlerFile = File(
        projectRoot,
        "presentation/src/main/java/com/silentpulse/messenger/feature/assistant/NavigationCommandHandler.kt"
    )
    private val voiceAssistantServiceFile = File(
        projectRoot,
        "presentation/src/main/java/com/silentpulse/messenger/feature/assistant/VoiceAssistantService.kt"
    )

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (true) {
            if (File(dir, "settings.gradle").exists()) return dir
            dir = dir.parentFile ?: break
        }
        return File(".")
    }

    @Test
    fun `navigation handler uses creator and sender BAL APIs separately`() {
        val content = navigationHandlerFile.readText()

        assertTrue(
            "NavigationCommandHandler should opt in as PendingIntent creator on Android 14+",
            content.contains("setPendingIntentCreatorBackgroundActivityStartMode")
        )
        assertTrue(
            "NavigationCommandHandler should opt in as PendingIntent sender on Android 14+",
            content.contains("setPendingIntentBackgroundActivityStartMode")
        )
    }

    @Test
    fun `navigation handler checks overlay permission and refreshes assistant notification`() {
        val content = navigationHandlerFile.readText()

        assertTrue(
            "NavigationCommandHandler should check overlay permission before Android 14+ background launch",
            content.contains("Settings.canDrawOverlays(context)")
        )
        assertTrue(
            "NavigationCommandHandler should refresh the assistant notification when overlay permission is missing",
            content.contains("refreshAssistantNotification()")
        )
        assertTrue(
            "NavigationCommandHandler should guide the user to the notification overlay action",
            content.contains("Tap Enable overlay in the SilentPulse notification")
        )
    }

    @Test
    fun `navigation launches happen before spoken confirmation`() {
        val content = navigationHandlerFile.readText()

        val googleSendIndex = content.indexOf("pending.send(context, 0, null, null, null, null, createSenderBalBundle")
        val googleSpeakIndex = content.indexOf("onSpeak(\"Starting navigation to \$destination with Google Maps.\", null)")
        assertTrue(
            "Google Maps launch should happen before spoken confirmation",
            googleSendIndex >= 0 && googleSpeakIndex > googleSendIndex
        )

        val openSourceSendIndex = content.indexOf("sendViaPI(intent, 1)")
        val openSourceSpeakIndex = content.indexOf("onSpeak(\"Navigating to \$destination with \$appName.\", null)")
        assertTrue(
            "Open-source maps launch should happen before spoken confirmation",
            openSourceSendIndex >= 0 && openSourceSpeakIndex > openSourceSendIndex
        )
    }

    @Test
    fun `voice assistant keeps TTS readiness and overlay notification affordance`() {
        val content = voiceAssistantServiceFile.readText()

        assertTrue(
            "VoiceAssistantService should initialize TextToSpeech in onCreate",
            content.contains("tts = TextToSpeech(this, this)")
        )
        assertTrue(
            "VoiceAssistantService should wait for both TTS and Vosk before starting listening",
            content.contains("if (ttsReady && voskModelReady)")
        )
        assertTrue(
            "VoiceAssistantService notification should expose the overlay permission action",
            content.contains("Enable overlay")
        )
        assertTrue(
            "VoiceAssistantService should open overlay settings from the assistant notification",
            content.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION")
        )
        assertTrue(
            "VoiceAssistantService should support assistant notification refresh requests",
            content.contains("ACTION_REFRESH_ASSISTANT_NOTIFICATION")
        )
    }
}