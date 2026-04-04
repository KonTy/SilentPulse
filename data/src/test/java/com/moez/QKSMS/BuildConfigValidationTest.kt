/*
 * Tests to verify that the project meets modern Android SDK requirements.
 * Scans build files and manifests for API level compliance.
 */
package com.moez.QKSMS

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Validates that the project's build configuration meets Google Play requirements:
 * - targetSdkVersion >= 34
 * - compileSdkVersion >= 34
 * - No jcenter() repository references
 * - No deprecated kotlin-android-extensions plugin
 */
class BuildConfigValidationTest {

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
    fun `targetSdkVersion is at least 34 in all modules`() {
        val buildFiles = projectRoot.walkTopDown()
            .filter { it.name == "build.gradle" }
            .filter { !it.path.contains("/build/") }

        for (file in buildFiles) {
            val content = file.readText()
            val regex = Regex("targetSdkVersion\\s+(\\d+)")
            val matches = regex.findAll(content)
            for (match in matches) {
                val version = match.groupValues[1].toInt()
                assertTrue(
                    "${file.relativeTo(projectRoot)}: targetSdkVersion is $version, must be >= 34",
                    version >= 34
                )
            }
        }
    }

    @Test
    fun `compileSdkVersion is at least 34 in all modules`() {
        val buildFiles = projectRoot.walkTopDown()
            .filter { it.name == "build.gradle" }
            .filter { !it.path.contains("/build/") }

        for (file in buildFiles) {
            val content = file.readText()
            val regex = Regex("compileSdkVersion\\s+(\\d+)")
            val matches = regex.findAll(content)
            for (match in matches) {
                val version = match.groupValues[1].toInt()
                assertTrue(
                    "${file.relativeTo(projectRoot)}: compileSdkVersion is $version, must be >= 34",
                    version >= 34
                )
            }
        }
    }

    @Test
    fun `jcenter repository is not used`() {
        val buildFiles = projectRoot.walkTopDown()
            .filter { it.name == "build.gradle" || it.name == "build.gradle.kts" }
            .filter { !it.path.contains("/build/") }

        for (file in buildFiles) {
            val content = file.readText()
            assertFalse(
                "${file.relativeTo(projectRoot)}: Still uses jcenter() which is shut down",
                content.contains("jcenter()")
            )
        }
    }

    @Test
    fun `kotlin-android-extensions plugin is not used`() {
        val buildFiles = projectRoot.walkTopDown()
            .filter { it.name == "build.gradle" || it.name == "build.gradle.kts" }
            .filter { !it.path.contains("/build/") }

        for (file in buildFiles) {
            val content = file.readText()
            assertFalse(
                "${file.relativeTo(projectRoot)}: Still uses deprecated kotlin-android-extensions",
                content.contains("kotlin-android-extensions")
            )
        }
    }

    @Test
    fun `no kotlinx synthetic imports remain in source code`() {
        val violations = mutableListOf<String>()
        val sourceFiles = projectRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { !it.path.contains("/build/") }
            .filter { !it.path.contains("/test/") }

        for (file in sourceFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                if (line.trimStart().startsWith("import kotlinx.android.synthetic")) {
                    violations.add("${file.relativeTo(projectRoot)}:${index + 1}: $line")
                }
            }
        }

        assertTrue(
            "kotlinx.android.synthetic imports still present:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
