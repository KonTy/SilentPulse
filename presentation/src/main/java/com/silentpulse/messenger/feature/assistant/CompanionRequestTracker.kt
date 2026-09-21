package com.silentpulse.messenger.feature.assistant

import timber.log.Timber
import android.os.SystemClock
import java.util.UUID

/**
 * Tracks one outstanding companion command, never a multi-turn conversation.
 * Every new command requires an explicit wake-word invocation and fresh request.
 */
class CompanionRequestTracker(private val now: () -> Long = SystemClock::elapsedRealtime) {

    data class PendingRequest(
        val sessionId: String,
        val targetPackage: String,
        val appLabel: String,
        val createdAt: Long
    )

    @Volatile
    private var activeRequest: PendingRequest? = null

    companion object {
        /** A companion has at most 60 seconds to answer one request. */
        const val TIMEOUT_MS = 60_000L
    }

    /**
     * Starts a new one-shot request. The wire protocol retains the session-ID field name.
     */
    @Synchronized
    fun begin(targetPackage: String, appLabel: String): String {
        val sessionId = UUID.randomUUID().toString()
        activeRequest = PendingRequest(
            sessionId = sessionId,
            targetPackage = targetPackage,
            appLabel = appLabel,
            createdAt = now()
        )
        Timber.d("companion_request_started")
        return sessionId
    }

    /**
     * Returns the pending request only for validating its reply, not for routing speech.
     */
    @Synchronized
    fun current(): PendingRequest? {
        val session = activeRequest ?: return null
        if (now() - session.createdAt !in 0 until TIMEOUT_MS) {
            Timber.d("companion_request_expired")
            activeRequest = null
            return null
        }
        return session
    }

    /**
     * Cancels the pending request.
     */
    @Synchronized
    fun clear() {
        val session = activeRequest
        if (session != null) {
            Timber.d("companion_request_closed")
        }
        activeRequest = null
    }

    /**
     * Completes only the matching request.
     */
    @Synchronized
    fun complete(sessionId: String) {
        if (activeRequest?.sessionId == sessionId) clear()
    }
}
