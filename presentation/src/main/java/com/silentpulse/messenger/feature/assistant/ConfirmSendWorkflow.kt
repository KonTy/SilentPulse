package com.silentpulse.messenger.feature.assistant

import android.util.Log

/**
 * Reusable voice workflow for composing and confirming a message before sending.
 *
 * Used for:
 *  - Replying to a notification ("reply" command in notification reader)
 *  - Sending a new SMS ("send text to John")
 *
 * ## Flow
 * ```
 * start()                      → asks for dictation → STT
 *   user says <message text>   → "I'll send: <text>. Say yes, no, or read back." → STT
 *     "yes" / "send"           → onSend(text)
 *     "no" / "cancel"          → onCancel()
 *     "read back"              → reads text back → "Say yes, no, or dictate again." → STT
 *         "yes"                → onSend(text)
 *         "no"                 → onCancel()
 *         "dictate again"      → back to dictation step
 *     <unrecognized>           → repeats current prompt
 * ```
 *
 * The caller supplies [speak] and [startStt] lambdas that delegate to
 * VoiceAssistantService — the workflow never talks to TTS or STT directly.
 *
 * @param speak       Delegate to `VoiceAssistantService.speak(text, onDone)`
 * @param startStt    Delegate to `VoiceAssistantService.startSttOneShot()`
 * @param onSend      Called with the confirmed message text — caller does the actual send
 * @param onCancel    Called when the user says "no" or "cancel"
 */
class ConfirmSendWorkflow(
    private val speak: (text: String, onDone: (() -> Unit)?) -> Unit,
    private val startStt: () -> Unit,
    private val onSend: (text: String) -> Unit,
    private val onCancel: () -> Unit
) {
    companion object {
        private const val TAG = "ConfirmSend"
    }

    private enum class State { IDLE, AWAITING_DICTATION, AWAITING_CONFIRM, AWAITING_POST_READBACK }

    private var state = State.IDLE
    private var pendingText = ""
    private var recipientLabel = ""

    /** True when the workflow is waiting for user input (not idle). */
    val isActive: Boolean get() = state != State.IDLE

    // ── Entry points ──────────────────────────────────────────────────────────

    /**
     * Start the workflow from scratch: prompt for dictation, then confirm.
     * [recipientLabel] is used only for the dictation prompt (e.g. "John").
     * [dictationPrompt] is spoken verbatim before STT starts.
     */
    fun start(recipientLabel: String, dictationPrompt: String) {
        Log.d(TAG, "start recipient=$recipientLabel")
        this.recipientLabel = recipientLabel
        state = State.AWAITING_DICTATION
        speak(dictationPrompt) { startStt() }
    }

    /**
     * Start with a pre-supplied [text] — skips the dictation step and jumps
     * directly into the confirmation prompt.  Used when the caller has already
     * captured the message body (e.g. notification reply text just dictated).
     */
    fun startWithText(recipientLabel: String, text: String) {
        Log.d(TAG, "startWithText recipient=$recipientLabel len=${text.length}")
        this.recipientLabel = recipientLabel
        pendingText = text.trim()
        state = State.AWAITING_CONFIRM
        speakConfirmPrompt()
    }

    /**
     * Feed the next STT result into the workflow.
     * @return true if the input was consumed by the workflow, false if idle.
     */
    fun handleInput(command: String): Boolean {
        if (state == State.IDLE) return false
        val c = command.lowercase().trim()
        Log.d(TAG, "handleInput state=$state input=\"$c\"")

        return when (state) {
            State.AWAITING_DICTATION -> {
                pendingText = command.trim()
                state = State.AWAITING_CONFIRM
                speakConfirmPrompt()
                true
            }

            State.AWAITING_CONFIRM -> {
                when {
                    isYes(c)         -> doSend()
                    isNo(c)          -> doCancel()
                    isReadBack(c)    -> doReadBack()
                    else -> speak("Say yes to send, no to cancel, or read back.") { startStt() }
                }
                true
            }

            State.AWAITING_POST_READBACK -> {
                when {
                    isYes(c)         -> doSend()
                    isNo(c)          -> doCancel()
                    isDictateAgain(c) -> doDictateAgain()
                    else -> speak("Say yes to send, no to cancel, or dictate again.") { startStt() }
                }
                true
            }

            State.IDLE -> false
        }
    }

    /** Hard-reset — call this when the assistant returns to wake-word mode unexpectedly. */
    fun reset() {
        Log.d(TAG, "reset()")
        state = State.IDLE
        pendingText = ""
        recipientLabel = ""
    }

    // ── Internal actions ──────────────────────────────────────────────────────

    private fun speakConfirmPrompt() {
        val preview = if (pendingText.length > 120) pendingText.take(120) + "…" else pendingText
        speak("I'll send: $preview. Say yes to send, no to cancel, or read back.") { startStt() }
    }

    private fun doSend() {
        Log.d(TAG, "confirmed → onSend")
        val text = pendingText
        state = State.IDLE
        onSend(text)
    }

    private fun doCancel() {
        Log.d(TAG, "confirmed → onCancel")
        state = State.IDLE
        onCancel()
    }

    private fun doReadBack() {
        Log.d(TAG, "read back requested")
        state = State.AWAITING_POST_READBACK
        speak("$pendingText. Say yes to send, no to cancel, or dictate again.") { startStt() }
    }

    private fun doDictateAgain() {
        Log.d(TAG, "dictate again")
        state = State.AWAITING_DICTATION
        speak("Okay, go ahead.") { startStt() }
    }

    // ── Keyword matching ──────────────────────────────────────────────────────

    private fun isYes(c: String)          = c == "yes" || c.startsWith("yes ") || c == "send" || c == "send it" || c.contains("send it")
    private fun isNo(c: String)           = c == "no"  || c.startsWith("no ")  || c == "cancel" || c.contains("cancel")
    private fun isReadBack(c: String)     = c.contains("read back") || c.contains("read it back") || c == "read"
    private fun isDictateAgain(c: String) = c.contains("dictate again") || c.contains("again") || c.contains("redo") || c.contains("dictate")
}
