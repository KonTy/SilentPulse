package com.silentpulse.messenger.feature.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DEBUG-ONLY mock receiver that pretends to be "Microcore" (or any assistant-capable app).
 *
 * Lives in src/debug/ so it is compiled into debug APKs only — never included in release.
 *
 * ## What it does
 * 1. Responds to ASSISTANT_CAPABLE queries so CommandRouter discovers an app called "Microcore"
 * 2. Receives EXECUTE_COMMAND broadcasts and replies with fake TTS_REPLY responses
 * 3. Receives REQUEST_SCHEMA broadcasts and replies with a mock command list
 *
 * ## How to test via adb
 *
 * ### Simulate an EXECUTE_COMMAND (as if VoiceAssistantService dispatched it):
 * ```
 * adb shell am broadcast \
 *   -a com.silentpulse.action.EXECUTE_COMMAND \
 *   --es EXTRA_TRANSCRIPT "log my weight at 220" \
 *   --es EXTRA_SESSION_ID "test-1" \
 *   -n com.silentpulse.messenger/.feature.assistant.DebugMockAssistantReceiver
 * ```
 *
 * ### Simulate a TTS_REPLY back to the assistant (as if Microcore replied):
 * ```
 * adb shell am broadcast \
 *   -a com.silentpulse.action.TTS_REPLY \
 *   --es EXTRA_SPOKEN_TEXT "Weight logged at 220 pounds" \
 *   --es EXTRA_SESSION_ID "test-1" \
 *   com.silentpulse.messenger
 * ```
 *
 * ### Full voice test (just talk):
 * 1. Enable Drive Mode + Wake Word in settings
 * 2. Say: "Computer, tell Microcore to log my weight at 220"
 * 3. You should hear the mock response via TTS
 */
class DebugMockAssistantReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DebugMockAssistant"

        /** Mock responses keyed by keyword in the command */
        private val MOCK_RESPONSES = listOf(
            MockRule("weight", "Got it. Weight logged at %s pounds.", extractNumber = true),
            MockRule("calorie", "You have 1,250 calories remaining today."),
            MockRule("calories", "You have 1,250 calories remaining today."),
            MockRule("water", "Water intake logged. You've had %s glasses today.", extractNumber = true),
            MockRule("sleep", "Sleep logged at %s hours.", extractNumber = true),
            MockRule("steps", "You've walked 8,432 steps today."),
            MockRule("blood pressure", "Blood pressure logged."),
            MockRule("medication", "Medication reminder set."),
            MockRule("how much", "Based on your logs, your current weight is 218 pounds and you've consumed 1,800 calories today."),
            MockRule("summary", "Today's summary: weight 218, calories 1,800 of 2,200, water 6 glasses, sleep 7.5 hours."),
        )

        /** Fallback when no keyword matches */
        private const val FALLBACK_RESPONSE = "Mock Microcore received your command: \"%s\". In production, the real app would handle this."
    }

    private data class MockRule(
        val keyword: String,
        val responseTemplate: String,
        val extractNumber: Boolean = false,
        val requireFollowup: Boolean = false
    )

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CommandRouter.ACTION_EXECUTE_COMMAND -> handleCommand(context, intent)
            CommandRouter.ACTION_REQUEST_SCHEMA  -> handleSchemaRequest(context, intent)
            CommandRouter.ACTION_ASSISTANT_CAPABLE -> {
                Log.d(TAG, "ASSISTANT_CAPABLE query received — Microcore mock is alive")
            }
            else -> Log.d(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun handleCommand(context: Context, intent: Intent) {
        val transcript = intent.getStringExtra(CommandRouter.EXTRA_TRANSCRIPT) ?: return
        val sessionId = intent.getStringExtra(CommandRouter.EXTRA_SESSION_ID) ?: "unknown"

        Log.d(TAG, "EXECUTE_COMMAND received: \"$transcript\" (session=$sessionId)")

        val lower = transcript.lowercase()

        // Find matching mock rule
        val rule = MOCK_RESPONSES.firstOrNull { lower.contains(it.keyword) }
        val spokenText = if (rule != null) {
            if (rule.extractNumber) {
                val number = extractNumber(transcript) ?: "unknown"
                String.format(rule.responseTemplate, number)
            } else {
                rule.responseTemplate
            }
        } else {
            String.format(FALLBACK_RESPONSE, transcript)
        }

        Log.d(TAG, "Replying with TTS: \"$spokenText\" (followup=${rule?.requireFollowup ?: false})")

        // Send TTS_REPLY back to VoiceAssistantService
        context.sendBroadcast(Intent(CommandRouter.ACTION_TTS_REPLY).apply {
            putExtra(CommandRouter.EXTRA_SPOKEN_TEXT, spokenText)
            putExtra(CommandRouter.EXTRA_SESSION_ID, sessionId)
            putExtra(CommandRouter.EXTRA_REQUIRE_FOLLOWUP, rule?.requireFollowup ?: false)
        })
    }

    private fun handleSchemaRequest(context: Context, intent: Intent) {
        Log.d(TAG, "REQUEST_SCHEMA received — sending mock schema")

        val schemaJson = """
            [
                {"command": "log weight <number>", "description": "Log your weight"},
                {"command": "log water <number>", "description": "Log water intake in glasses"},
                {"command": "log sleep <number>", "description": "Log sleep in hours"},
                {"command": "how many calories", "description": "Check remaining calories"},
                {"command": "summary", "description": "Get today's health summary"}
            ]
        """.trimIndent()

        context.sendBroadcast(Intent(CommandRouter.ACTION_REPORT_SCHEMA).apply {
            putExtra(CommandRouter.EXTRA_SCHEMA_JSON, schemaJson)
        })
    }

    private fun extractNumber(text: String): String? {
        val regex = Regex("\\d+\\.?\\d*")
        return regex.find(text)?.value
    }
}
