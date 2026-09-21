package com.silentpulse.messenger.common.util

import android.content.Context
import com.silentpulse.messenger.BuildConfig
import com.silentpulse.messenger.util.Preferences
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class DiagnosticMetadataTest {
    @Test
    fun `diagnostic formatting never reads exception messages causes or stack traces`() {
        val privateMarker = "synthetic-private-transcript-token"
        val error = IllegalArgumentException(privateMarker, IllegalStateException(privateMarker))
        val result = DiagnosticMetadata.describe(error, arrayOf(
            StackTraceElement("timber.log.Timber", "e", "Timber.kt", 9),
            StackTraceElement(FileLoggingTree::class.java.name, "log", "FileLoggingTree.kt", 10),
            StackTraceElement("com.silentpulse.messenger.Example", "run", "Example.kt", 42)
        ))
        assertEquals(
            "diagnostic source=com.silentpulse.messenger.Example.run:42 type=IllegalArgumentException", result)
        assertFalse(result.contains(privateMarker))
        assertFalse(result.contains("IllegalStateException"))
    }

    @Test
    fun `missing source and throwable produce fixed metadata`() {
        assertEquals("diagnostic source=unknown type=none", DiagnosticMetadata.describe(null, emptyArray()))
    }

    @Test
    fun `release file logger cannot access preferences or storage even when directly invoked`() {
        assumeFalse(BuildConfig.DEBUG)
        val context = mock(Context::class.java)
        val preferences = mock(Preferences::class.java)
        val tree = FileLoggingTree(context, preferences)
        tree.log(6, "synthetic-private-tag", "synthetic-private-message",
            IllegalArgumentException("synthetic-private-token"))
        verifyNoInteractions(context, preferences)
    }
}
