package com.silentpulse.messenger.feature.assistant

import java.util.UUID
import java.util.concurrent.TimeUnit

/** One reply capability per dispatched request; never persisted or logged. */
internal class AssistantReplySessions(
    private val now: () -> Long,
    private val timeoutMillis: Long = TimeUnit.MINUTES.toMillis(2)
) {
    enum class Kind { COMMAND, SCHEMA }
    data class Reply(
        val sessionId: String,
        val packageName: String,
        val uid: Int,
        val wireSessionId: String = sessionId,
        val requiresReplyNonce: Boolean = false,
        val kind: Kind = Kind.COMMAND
    )
    private data class Pending(val reply: Reply, val createdAt: Long)
    private val pending = mutableMapOf<String, Pending>()

    @Synchronized
    fun expect(
        sessionId: String,
        packageName: String,
        uid: Int,
        conversationId: String = sessionId,
        wireSessionId: String = sessionId,
        requiresReplyNonce: Boolean = false,
        kind: Kind = Kind.COMMAND
    ): Boolean {
        val parsed = try {
            UUID.fromString(sessionId)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (parsed.version() != 4 || parsed.variant() != 2 || parsed.toString() != sessionId) return false
        prune()
        val existing = pending[sessionId]
        if (uid < 0 || conversationId.isBlank() ||
            existing != null && (existing.reply.packageName != packageName || existing.reply.uid != uid)) return false
        pending[sessionId] = Pending(
            Reply(conversationId, packageName, uid, wireSessionId, requiresReplyNonce, kind), now()
        )
        return true
    }

    @Synchronized
    fun consume(sessionId: String?): Reply? {
        prune()
        if (sessionId == null) return null
        val request = pending.remove(sessionId) ?: return null
        return request.reply
    }

    @Synchronized
    fun cancel(sessionId: String) {
        pending.remove(sessionId)
    }

    @Synchronized
    fun clear() {
        pending.clear()
    }

    private fun prune() {
        val current = now()
        pending.entries.removeAll { current - it.value.createdAt !in 0 until timeoutMillis }
    }
}
