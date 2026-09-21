package com.silentpulse.messenger.feature.assistant

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AssistantReplySessionsTest {
    private var now = 1_000L
    private val sessions = AssistantReplySessions({ now }, timeoutMillis = 120_000)
    private val token = UUID.randomUUID().toString()

    @Test
    fun `reply needs a live unpredictable capability and is consumed once`() {
        assertNull(sessions.consume(null))
        assertNull(sessions.consume(token))
        assertFalse(sessions.expect("predictable-session", "com.grafium.app", 101))
        assertFalse(sessions.expect("00000000-0000-0000-0000-000000000000", "com.grafium.app", 101))
        assertTrue(sessions.expect(token, "com.grafium.app", 101))
        assertEquals(AssistantReplySessions.Reply(token, "com.grafium.app", 101), sessions.consume(token))
        assertNull(sessions.consume(token))
    }

    @Test
    fun `request cannot switch recipient identity while pending`() {
        assertTrue(sessions.expect(token, "com.grafium.app", 101))
        assertFalse(sessions.expect(token, "com.microcore.microcore", 102))
        assertFalse(sessions.expect(token, "com.grafium.app", 103))
        assertEquals("com.grafium.app", sessions.consume(token)?.packageName)
    }

    @Test
    fun `late replies expire and backwards clock movement fails closed`() {
        sessions.expect(token, "com.grafium.app", 101)
        now += 120_000
        assertNull(sessions.consume(token))
        sessions.expect(token, "com.grafium.app", 101)
        now--
        assertNull(sessions.consume(token))
    }

    @Test
    fun `cancelled and service-stopped requests cannot accept replies`() {
        sessions.expect(token, "com.grafium.app", 101)
        sessions.cancel(token)
        assertNull(sessions.consume(token))
        sessions.expect(token, "com.grafium.app", 101)
        sessions.clear()
        assertNull(sessions.consume(token))
    }

    @Test
    fun `independent requests remain bound to their own recipients`() {
        val other = UUID.randomUUID().toString()
        sessions.expect(token, "com.grafium.app", 101)
        sessions.expect(other, "com.microcore.microcore", 102)
        assertEquals("com.microcore.microcore", sessions.consume(other)?.packageName)
        assertEquals("com.grafium.app", sessions.consume(token)?.packageName)
    }
}
