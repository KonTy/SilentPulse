package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Voice command handler for Outlook email management.
 *
 * Trigger: "outlook read my inbox", "outlook check email", "outlook read my email"
 *
 * Reading flow:
 *   1. Fetch top 5 emails via [OutlookWebScraper]
 *   2. Speak each: "Email 1 of 5. From Alice. Subject: Budget Review. Say next, reply, delete, forward, read, or stop."
 *   3. On "reply" → dictate → confirm → send via WebView
 *   4. On "delete" → delete via WebView → advance
 *   5. On "forward" → ask "who?" → dictate recipient → forward via WebView
 *   6. On "read" → read full body → re-prompt
 *   7. On "next" → advance to next email
 *   8. On "repeat" → re-read current summary
 *   9. On "stop" → exit reading mode
 */
class OutlookCommandHandler(private val context: Context) {

    companion object {
        private const val TAG = "OutlookHandler"
    }

    private val scraper = OutlookWebScraper(context)

    val isReady: Boolean get() = scraper.isReady

    // ── Command detection ─────────────────────────────────────────────────────

    /**
     * Returns true if the command is an Outlook voice command.
     * Trigger: "outlook" + (read/check/open/show) + (inbox/email/mail)
     */
    fun isOutlookCommand(c: String): Boolean {
        if (!c.contains("outlook")) return false
        val outlookKeywords = listOf(
            "read", "check", "open", "show", "inbox", "email", "mail"
        )
        return outlookKeywords.any { c.contains(it) }
    }

    /**
     * "outlook login", "outlook sign in", "outlook re-login", "outlook authenticate"
     */
    fun isOutlookLoginCommand(c: String): Boolean {
        if (!c.contains("outlook")) return false
        return c.contains("login") || c.contains("log in") || c.contains("sign in") ||
               c.contains("authenticate") || c.contains("re-login") || c.contains("relogin")
    }

    // ── Per-item command detection ────────────────────────────────────────────

    fun isNextCommand(c: String): Boolean =
        c.contains("next") || c.contains("skip")

    fun isDeleteCommand(c: String): Boolean =
        c.contains("delete") || c.contains("remove") || c.contains("trash")

    fun isReplyCommand(c: String): Boolean =
        c.contains("reply") || c.contains("respond") || c.contains("answer")

    fun isForwardCommand(c: String): Boolean =
        c.contains("forward") || c.contains("send to")

    fun isReadBodyCommand(c: String): Boolean =
        c == "read" || c.contains("read full") || c.contains("read body") ||
        c.contains("read email") || c.contains("read it") || c.contains("open it")

    fun isRepeatCommand(c: String): Boolean =
        c.contains("repeat") || c.contains("again") || c.contains("replay")

    fun isStopCommand(c: String): Boolean =
        c.contains("stop") || c.contains("done") || c.contains("cancel") ||
        c.contains("exit") || c.contains("quit")

    // ── Data fetching ─────────────────────────────────────────────────────────

    fun fetchInbox(count: Int = 5, onResult: (List<OutlookWebScraper.EmailPreview>) -> Unit) {
        scraper.fetchInbox(count, onResult)
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    fun formatForSpeech(email: OutlookWebScraper.EmailPreview, index: Int, total: Int): String {
        val subject = email.subject.ifBlank { "no subject" }
        return "Email ${index} of $total. From ${email.sender}. Subject: $subject."
    }

    fun promptForCommands(): String {
        return "Say next, reply, delete, forward, read, repeat, or stop."
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /** Click the email at [emailIndex] and read the full body aloud. */
    fun readFullBody(emailIndex: Int, onResult: (String?) -> Unit) {
        scraper.readEmailBody(emailIndex, onResult)
    }

    fun deleteCurrentEmail(onResult: (Boolean) -> Unit) {
        scraper.deleteCurrentEmail(onResult)
    }

    fun replyToCurrentEmail(text: String, onResult: (Boolean) -> Unit) {
        scraper.replyToCurrentEmail(text, onResult)
    }

    fun forwardCurrentEmail(recipient: String, onResult: (Boolean) -> Unit) {
        scraper.forwardCurrentEmail(recipient, onResult)
    }

    fun goBackToInbox(onDone: () -> Unit) {
        scraper.goBackToInbox(onDone)
    }

    fun shutdown() {
        scraper.shutdown()
    }
}
