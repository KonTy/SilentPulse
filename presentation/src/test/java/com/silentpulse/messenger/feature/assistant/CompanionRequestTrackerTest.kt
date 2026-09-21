package com.silentpulse.messenger.feature.assistant

import org.junit.Assert.*
import org.junit.Test

class CompanionRequestTrackerTest {
    private var time = 1_000L
    private val tracker = CompanionRequestTracker { time }

    @Test
    fun `one response completes the request and the next command starts fresh`() {
        val first = tracker.begin("com.microcore.microcore", "Microcore")
        assertEquals(first, tracker.current()?.sessionId)
        tracker.complete(first)
        assertNull(tracker.current())
        val second = tracker.begin("com.microcore.microcore", "Microcore")
        assertNotEquals(first, second)
    }

    @Test
    fun `an old reply cannot complete a newer request`() {
        val first = tracker.begin("com.grafium.app", "Grafium")
        val second = tracker.begin("com.microcore.microcore", "Microcore")
        tracker.complete(first)
        assertEquals(second, tracker.current()?.sessionId)
        assertEquals("com.microcore.microcore", tracker.current()?.targetPackage)
    }

    @Test
    fun `expired or cancelled requests cannot continue a conversation`() {
        tracker.begin("com.grafium.app", "Grafium")
        time += CompanionRequestTracker.TIMEOUT_MS
        assertNull(tracker.current())
        tracker.begin("com.grafium.app", "Grafium")
        tracker.clear()
        assertNull(tracker.current())
    }
}
