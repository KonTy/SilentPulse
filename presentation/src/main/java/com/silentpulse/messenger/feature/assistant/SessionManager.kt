package com.silentpulse.messenger.feature.assistant

import timber.log.Timber
import java.util.UUID

/**
 * Tracks active multi-turn voice conversations with target apps.
 *
 * When a target app replies with EXTRA_REQUIRE_FOLLOWUP=true, the session stays
 * open so the next user utterance is routed back to the same app with the same
 * SESSION_ID. This enables the 3-tier confidence flow from the spec:
 *
 * - High confidence: app replies with final answer, session ends.
 * - Medium confidence: app asks "Did you mean X?", session stays open for Yes/No.
 * - Low confidence: app says "I didn't understand", session ends.
 *
 * Sessions auto-expire after [TIMEOUT_MS] to prevent stale state.
 */
class SessionManager {

    data class ActiveSession(
        val sessionId: String,
        val targetPackage: String,
        val appLabel: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    @Volatile
    private var activeSession: ActiveSession? = null

    companion object {
        /** Sessions expire after 60 seconds of inactivity */
        const val TIMEOUT_MS = 60_000L
    }

    /**
     * Opens a new session for a command dispatched to [targetPackage].
     * Returns the session ID.
     */
    fun open(targetPackage: String, appLabel: String): String {
        val sessionId = UUID.randomUUID().toString()
        activeSession = ActiveSession(
            sessionId = sessionId,
            targetPackage = targetPackage,
            appLabel = appLabel
        )
        Timber.d("SessionManager: opened session $sessionId → $appLabel ($targetPackage)")
        return sessionId
    }

    /**
     * Returns the active session if one exists and hasn't expired.
     * Used when the user speaks a follow-up (e.g. "yes" / "no") —
     * if there's an active session, the follow-up goes to the same app.
     */
    fun getActive(): ActiveSession? {
        val session = activeSession ?: return null
        if (System.currentTimeMillis() - session.createdAt > TIMEOUT_MS) {
            Timber.d("SessionManager: session ${session.sessionId} expired")
            activeSession = null
            return null
        }
        return session
    }

    /**
     * Extends the session timeout (call when follow-up is in progress).
     */
    fun touch() {
        activeSession = activeSession?.copy(createdAt = System.currentTimeMillis())
    }

    /**
     * Closes the active session (call when EXTRA_REQUIRE_FOLLOWUP=false
     * or the conversation is done).
     */
    fun close() {
        val session = activeSession
        if (session != null) {
            Timber.d("SessionManager: closed session ${session.sessionId}")
        }
        activeSession = null
    }

    /**
     * Closes only if the given session ID matches the active one.
     */
    fun close(sessionId: String) {
        if (activeSession?.sessionId == sessionId) close()
    }
}
