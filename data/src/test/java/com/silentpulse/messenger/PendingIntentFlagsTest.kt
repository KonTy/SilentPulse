/*
 * Tests to verify that all PendingIntent calls include FLAG_IMMUTABLE or FLAG_MUTABLE
 * as required by Android 12+ (API 31+).
 */
package com.silentpulse.messenger

import android.app.PendingIntent
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Static analysis test that scans source files for PendingIntent calls
 * and verifies they include FLAG_IMMUTABLE or FLAG_MUTABLE.
 */
class PendingIntentFlagsTest {

    private val projectRoot = findProjectRoot()

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle").exists()) return dir
            dir = dir.parentFile
        }
        return File(".")
    }

    @Test
    fun `all PendingIntent getBroadcast calls include immutability flag`() {
        assertPendingIntentFlagsPresent("getBroadcast")
    }

    @Test
    fun `all PendingIntent getActivity calls include immutability flag`() {
        assertPendingIntentFlagsPresent("getActivity")
    }

    @Test
    fun `all PendingIntent getService calls include immutability flag`() {
        assertPendingIntentFlagsPresent("getService")
    }

    @Test
    fun `all getPendingIntent calls include immutability flag`() {
        assertPendingIntentFlagsPresent("getPendingIntent")
    }

    private fun assertPendingIntentFlagsPresent(methodName: String) {
        val violations = mutableListOf<String>()
        val sourceFiles = projectRoot.walkTopDown()
            .filter { it.extension in listOf("kt", "java") }
            .filter { !it.path.contains("/test/") }
            .filter { !it.path.contains("/androidTest/") }
            .filter { !it.path.contains("/build/") }

        for (file in sourceFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                if (line.contains("$methodName(") && line.contains("PendingIntent")) {
                    // Check this line and surrounding lines for FLAG_IMMUTABLE or FLAG_MUTABLE
                    val context = lines.subList(
                        maxOf(0, index - 2),
                        minOf(lines.size, index + 3)
                    ).joinToString("\n")

                    if (!context.contains("FLAG_IMMUTABLE") && !context.contains("FLAG_MUTABLE")) {
                        violations.add("${file.relativeTo(projectRoot)}:${index + 1}: $methodName missing FLAG_IMMUTABLE/FLAG_MUTABLE")
                    }
                }
            }
        }

        assertTrue(
            "PendingIntent calls missing immutability flags:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
