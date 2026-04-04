/*
 * Tests to verify deprecated API usage has been removed from the codebase.
 */
package com.moez.QKSMS

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Scans the source code for deprecated API patterns that would cause issues
 * on modern Android versions.
 */
class DeprecatedApiTest {

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
    fun `no AsyncTask usage remains in source code`() {
        assertPatternNotInSource(
            pattern = "extends AsyncTask|: AsyncTask",
            description = "AsyncTask (deprecated API 30)",
            isRegex = true
        )
    }

    @Test
    fun `no IntentService usage remains in source code`() {
        assertPatternNotInSource(
            pattern = "extends IntentService|: IntentService",
            description = "IntentService (deprecated API 30)",
            isRegex = true
        )
    }

    @Test
    fun `no Environment getExternalStorageDirectory usage remains`() {
        assertPatternNotInSource(
            pattern = "getExternalStorageDirectory",
            description = "Environment.getExternalStorageDirectory() (deprecated API 29)"
        )
    }

    @Test
    fun `no old OkHttp 2 package imports remain`() {
        assertPatternNotInSource(
            pattern = "import com.squareup.okhttp.",
            description = "OkHttp 2.x imports (com.squareup.okhttp)"
        )
    }

    @Test
    fun `no old AutoDispose 1 package imports remain`() {
        val violations = mutableListOf<String>()
        val sourceFiles = projectRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { !it.path.contains("/build/") }
            .filter { !it.path.contains("/test/") }

        for (file in sourceFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                if (line.contains("import com.uber.autodispose.") &&
                    !line.contains("import com.uber.autodispose2.")) {
                    violations.add("${file.relativeTo(projectRoot)}:${index + 1}: $line")
                }
            }
        }

        assertTrue(
            "Old AutoDispose 1.x imports found (should be autodispose2):\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `no old Conductor 2 RouterTransaction with() calls remain`() {
        assertPatternNotInSource(
            pattern = "RouterTransaction.with(",
            description = "Conductor 2.x RouterTransaction.with() (use RouterTransaction() in 3.x)"
        )
    }

    @Test
    fun `no LifecycleController imports remain`() {
        assertPatternNotInSource(
            pattern = "import com.bluelinelabs.conductor.archlifecycle.LifecycleController",
            description = "LifecycleController (removed in Conductor 3.x, use Controller)"
        )
    }

    @Test
    fun `no RetainViewMode usage remains`() {
        assertPatternNotInSource(
            pattern = "RetainViewMode.RETAIN_DETACH",
            description = "RetainViewMode.RETAIN_DETACH (removed in Conductor 3.x)"
        )
    }

    @Test
    fun `no stopForeground with boolean parameter remains`() {
        val violations = mutableListOf<String>()
        val sourceFiles = projectRoot.walkTopDown()
            .filter { it.extension in listOf("kt", "java") }
            .filter { !it.path.contains("/build/") }
            .filter { !it.path.contains("/test/") }

        for (file in sourceFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                if (Regex("stopForeground\\s*\\(\\s*(true|false)\\s*\\)").containsMatchIn(line)) {
                    violations.add("${file.relativeTo(projectRoot)}:${index + 1}: $line")
                }
            }
        }

        assertTrue(
            "stopForeground(boolean) found (deprecated API 33, use STOP_FOREGROUND_REMOVE):\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    private fun assertPatternNotInSource(pattern: String, description: String, isRegex: Boolean = false) {
        val violations = mutableListOf<String>()
        val sourceFiles = projectRoot.walkTopDown()
            .filter { it.extension in listOf("kt", "java") }
            .filter { !it.path.contains("/build/") }
            .filter { !it.path.contains("/test/") }

        val regex = if (isRegex) Regex(pattern) else null

        for (file in sourceFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val found = if (isRegex) regex!!.containsMatchIn(line) else line.contains(pattern)
                if (found) {
                    violations.add("${file.relativeTo(projectRoot)}:${index + 1}: ${line.trim()}")
                }
            }
        }

        assertTrue(
            "$description still found in source:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
