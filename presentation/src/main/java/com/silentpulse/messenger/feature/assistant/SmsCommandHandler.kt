package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

/**
 * Handles reading and sending SMS messages via on-device APIs.
 *
 * ## Supported phrases — "read SMS" entry point
 *   - "read my SMS" / "read my texts" / "read my messages"
 *   - "check my SMS" / "check texts" / "any texts" / "new messages"
 *
 * ## Supported phrases — "send SMS" entry point
 *   - "send text to John" / "send SMS to John"
 *   - "text John" / "message John"
 *   - "send a message to John"
 *
 * All of these feed into [ConfirmSendWorkflow] in VoiceAssistantService —
 * the actual dictate/confirm/send loop lives there.
 *
 * ## Permissions required (already declared in AndroidManifest.xml)
 *   - READ_SMS, SEND_SMS, READ_CONTACTS, RECEIVE_SMS
 */
class SmsCommandHandler(private val context: Context) {

    companion object {
        private const val TAG = "SmsHandler"
    }

    data class SmsMessage(
        val id: Long,             // _id in content://sms — needed for deletion
        val address: String,      // raw phone number
        val senderName: String,   // display name from Contacts, or raw number
        val body: String,
        val timestamp: Long
    )

    data class ResolvedContact(val name: String, val number: String)

    // ── Command detection ─────────────────────────────────────────────────────

    fun isReadSmsCommand(c: String): Boolean =
        c.contains("read my sms") ||
        c.contains("read my texts") ||
        c.contains("read my messages") ||
        c.contains("read sms") ||
        c.contains("check my sms") ||
        c.contains("check sms") ||
        c.contains("check my texts") ||
        c.contains("unread sms") ||
        c.contains("unread texts") ||
        c.contains("unread messages") ||
        c.contains("new texts") ||
        c.contains("new messages") ||
        c.contains("any texts") ||
        c.contains("any messages") ||
        c == "sms" || c == "texts"

    fun isSendSmsCommand(c: String): Boolean =
        (c.startsWith("send") &&
            (c.contains(" sms ") || c.contains(" sms to") || c.contains(" text ") ||
             c.contains(" text to") || c.contains(" message ") || c.contains(" message to"))) ||
        (c.startsWith("text ") && c.length > 5) ||
        (c.startsWith("message ") && c.length > 8) ||
        c.contains("send a text") ||
        c.contains("send a message") ||
        c.contains("send text to") ||
        c.contains("send message to")

    /**
     * Extracts the recipient name from a send-sms command.
     * e.g. "send text to John doe" → "John doe"
     *      "text John"             → "John"
     *      "message to Sarah"      → "Sarah"
     */
    fun extractRecipientName(c: String): String {
        // Strip the verb+noun prefix and any trailing filler
        val cleaned = c
            .replace(Regex("^send\\s+(a\\s+)?(sms|text|message)(\\s+to)?\\s*"), "")
            .replace(Regex("^(text|message)(\\s+to)?\\s*"), "")
            .trim()
        return cleaned
    }

    // ── SMS reading ───────────────────────────────────────────────────────────

    /**
     * Returns unread SMS messages from the inbox, newest first.
     * Returns empty list if READ_SMS permission is denied or ContentResolver fails.
     */
    fun fetchUnreadSms(): List<SmsMessage> {
        val results = mutableListOf<SmsMessage>()
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date", "read"),
                "read = 0",
                null,
                "date DESC"
            ) ?: run {
                Log.w(TAG, "fetchUnreadSms: ContentResolver returned null cursor")
                return emptyList()
            }
            while (cursor.moveToNext()) {
                val id      = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                val address = cursor.getString(cursor.getColumnIndexOrThrow("address"))
                    ?: continue
                val body = cursor.getString(cursor.getColumnIndexOrThrow("body"))
                    ?: continue
                val date = cursor.getLong(cursor.getColumnIndexOrThrow("date"))
                val name = resolveNameForNumber(address) ?: address
                results += SmsMessage(id, address, name, body, date)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchUnreadSms failed", e)
        } finally {
            cursor?.close()
        }
        Log.d(TAG, "fetchUnreadSms: ${results.size} unread")
        return results
    }

    /** Formats a single [SmsMessage] for TTS output. */
    fun formatSmsForSpeech(msg: SmsMessage, index: Int, total: Int): String {
        val counter = if (total > 1) "Message $index of $total. " else ""
        return "${counter}From ${msg.senderName}: ${msg.body}. Say next, delete, repeat, or stop."
    }

    /**
     * Returns true if this app is currently set as the default SMS handler.
     * Marking messages as read and deleting them both require this.
     */
    fun isDefaultSmsApp(): Boolean =
        context.packageName == Telephony.Sms.getDefaultSmsPackage(context)

    /**
     * Marks a single SMS as read (read=1) in the inbox.
     * Requires default SMS app on Android 4.4+.
     */
    fun markAsRead(msg: SmsMessage) {
        try {
            val values = android.content.ContentValues().apply { put("read", 1) }
            val updated = context.contentResolver.update(
                Uri.parse("content://sms/${msg.id}"), values, null, null
            )
            Log.d(TAG, "markAsRead id=${msg.id}: $updated row(s) updated")
        } catch (e: Exception) {
            Log.e(TAG, "markAsRead failed for id=${msg.id}", e)
        }
    }

    /**
     * Deletes a single SMS from the inbox by its [_id][SmsMessage.id].
     * Requires WRITE_SMS / DELETE_SMS permission (or default SMS app on Android 4.4+).
     * Returns true if a row was deleted.
     */
    fun deleteSms(msg: SmsMessage): Boolean {
        return try {
            val deleted = context.contentResolver.delete(
                Uri.parse("content://sms/${msg.id}"),
                null, null
            )
            Log.d(TAG, "deleteSms id=${msg.id}: $deleted row(s) deleted")
            deleted > 0
        } catch (e: Exception) {
            Log.e(TAG, "deleteSms failed for id=${msg.id}", e)
            false
        }
    }

    // ── Contact resolution ────────────────────────────────────────────────────

    /**
     * Fuzzy-matches [name] against the device's contact book.
     * Returns the best-matching [ResolvedContact], or null if nothing close enough.
     */
    fun resolveContact(name: String): ResolvedContact? {
        val nameLower = name.lowercase().trim()
        if (nameLower.isBlank()) return null

        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            ) ?: return null

            var best: ResolvedContact? = null
            var bestScore = Int.MAX_VALUE

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(0) ?: continue
                val number      = cursor.getString(1)?.filter { it.isDigit() || it == '+' } ?: continue
                val labelLower  = displayName.lowercase()

                // Substring match scores 0; otherwise use Levenshtein
                val dist = when {
                    labelLower.contains(nameLower) || nameLower.contains(labelLower) -> 0
                    else -> levenshtein(nameLower, labelLower)
                }

                // Accept if distance ≤ 40% of the shorter string, at least 3
                val threshold = (nameLower.length.coerceAtMost(labelLower.length) * 0.4).toInt().coerceAtLeast(3)
                if (dist <= threshold && dist < bestScore) {
                    bestScore = dist
                    best = ResolvedContact(displayName, number)
                }
            }
            Log.d(TAG, "resolveContact(\"$name\") → ${best?.name} (score=$bestScore)")
            best
        } catch (e: Exception) {
            Log.e(TAG, "resolveContact failed", e)
            null
        } finally {
            cursor?.close()
        }
    }

    // ── SMS sending ───────────────────────────────────────────────────────────

    /**
     * Sends [text] to [number] via SmsManager.
     * Automatically splits long messages into multipart SMS.
     * @return true on success, false if an exception occurred.
     */
    @Suppress("DEPRECATION")
    fun sendSms(number: String, text: String): Boolean {
        return try {
            val manager: SmsManager =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    context.getSystemService(SmsManager::class.java)
                else
                    SmsManager.getDefault()

            val parts = manager.divideMessage(text)
            if (parts.size == 1) {
                manager.sendTextMessage(number, null, text, null, null)
            } else {
                manager.sendMultipartTextMessage(number, null, parts, null, null)
            }
            Log.d(TAG, "sendSms to=$number len=${text.length} parts=${parts.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendSms failed to=$number", e)
            false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Reverse phone-number look-up: number → display name in Contacts, or null. */
    private fun resolveNameForNumber(number: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                       else minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + 1)
        }
        return dp[a.length][b.length]
    }
}
