package com.silentpulse.messenger.feature.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class CompanionPrivacyRegressionTest {
    private val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .first { File(it, "settings.gradle").isFile }
    private val service = File(root,
        "presentation/src/main/java/com/silentpulse/messenger/feature/assistant/VoiceAssistantService.kt").readText()

    @Test
    fun `untrusted replies are rejected before reading or speaking their payloads`() {
        assertTrue(service.indexOf("commandRouter.acceptReply(") <
            service.indexOf("intent.getStringExtra(CommandRouter.EXTRA_SPOKEN_TEXT)"))
        assertTrue(service.indexOf("commandRouter.acceptSchemaReply(") <
            service.indexOf("intent.getStringExtra(CommandRouter.EXTRA_SCHEMA_JSON)"))
        assertTrue(service.contains("active?.sessionId != authorized.sessionId"))
        assertTrue(service.contains("active.targetPackage != authorized.packageName"))
    }

    @Test
    fun `app-directed commands are blocked before general online question handling`() {
        val refusal = service.indexOf("commandRouter.isCompanionCommand(command)")
        assertTrue(refusal > 0)
        assertTrue(refusal < service.indexOf("// ── 7. General knowledge queries"))
        val entry = service.indexOf("if (routeCompanionCommand(command, c)) return")
        assertTrue(entry > 0)
        assertTrue(entry < service.indexOf("weatherHandler.isWeatherCommand(c)"))
    }

    @Test
    fun `unrouted health questions return locally before every online backend`() {
        val entry = service.indexOf("if (routeCompanionCommand(command, c)) return")
        for (backend in listOf("weatherHandler.fetchAndSpeak(", "driveTimeHandler.fetchAndSpeak(",
            "stockQueryHandler.fetchPrice(", "webAiSearchScraper.searchStreaming(",
            "braveSearchHandler.search(", "generalQueryHandler.fetchAndSpeak(")) {
            assertTrue(backend, entry in 0 until service.indexOf(backend))
        }
        val refusal = service.substringAfter("if (appDirected) {")
            .substringBefore("// ── Command routing")
        assertTrue(refusal.contains("commandRouter.isPrivateHealthCommand(command)"))
        assertTrue(refusal.contains("return true"))
        assertTrue(refusal.indexOf("return true") < refusal.indexOf("return false"))
        assertFalse(refusal.contains("searchStreaming"))
        assertFalse(refusal.contains("fetchAndSpeak"))
    }

    @Test
    fun `cancellation and expiry clear pending reply capabilities`() {
        assertTrue(service.contains("commandRouter.clearPendingReplies()"))
        assertTrue(service.contains("mainHandler.postDelayed(timeout, CompanionRequestTracker.TIMEOUT_MS)"))
        assertTrue(service.substringAfter("override fun onDestroy()").substringBefore("// ── Vosk")
            .contains("cancelCompanionInteraction()"))
        assertTrue(service.substringAfter("private val stopSpeakingReceiver").substringBefore("// ── Lifecycle")
            .contains("cancelCompanionInteraction()"))
    }

    @Test
    fun `voice command sender permission is declared and requested`() {
        val manifest = File(root, "presentation/src/main/AndroidManifest.xml").readText()
        val permission = "com.silentpulse.messenger.permission.VOICE_COMMAND"
        assertTrue(manifest.contains("android:name=\"$permission\"\n        android:protectionLevel=\"signature\""))
        assertTrue(manifest.contains("<uses-permission android:name=\"$permission\""))
    }

    @Test
    fun `rendering saved Whisper selection cannot silently overwrite it`() {
        val controller = File(root,
            "presentation/src/main/java/com/silentpulse/messenger/feature/assistant/AssistantController.kt").readText()
        assertTrue(controller.contains("!isChecked || renderingEngineSelection"))
        assertTrue(controller.contains("\"whisper\" -> R.id.sttBtnWhisper"))
        assertTrue(controller.contains("renderEngineChoice(sttEngineToggle, sttBtnToCheck)"))
    }

    @Test
    fun `other apps cannot start microphone service or impersonate control widget actions`() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(root, "presentation/src/main/AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        val components = manifest.getElementsByTagName("*")
        val privateNames = setOf(
            "com.silentpulse.messenger.feature.assistant.VoiceAssistantService",
            ".feature.drivemode.DriveModeWidgetProvider",
            ".feature.widget.WidgetProvider",
            ".feature.worldclock.WorldClockWidgetProvider"
        )
        val selected = (0 until components.length).map { components.item(it) as Element }
            .filter { it.getAttributeNS(ns, "name") in privateNames }
        assertEquals(privateNames.size, selected.size)
        selected.forEach { assertEquals("false", it.getAttributeNS(ns, "exported")) }
    }

    @Test
    fun `companion replies never start another listening turn or implicitly route future speech`() {
        val receiver = service.substringAfter("private val ttsReplyReceiver")
            .substringBefore("private val schemaReplyReceiver")
        assertFalse(receiver.contains("EXTRA_REQUIRE_FOLLOWUP"))
        assertFalse(receiver.contains("startSttOneShot"))
        assertTrue(receiver.contains("companionRequest.complete(authorized.sessionId)"))
        assertTrue(receiver.contains("resumeWakeWord()"))
        assertFalse(service.contains("sessionManager"))
        assertFalse(service.contains("commandPrefix"))
        assertFalse(service.contains("commandRouter.findAppByName"))
    }
}
