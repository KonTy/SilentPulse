/*
 * Tests to verify AndroidManifest.xml meets Android 12+ (API 31+) requirements.
 */
package com.silentpulse.messenger

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Validates that the AndroidManifest meets modern Android requirements:
 * - All components with intent-filters have android:exported set
 * - Required permissions are declared (POST_NOTIFICATIONS, FOREGROUND_SERVICE_DATA_SYNC, etc.)
 * - No requestLegacyExternalStorage
 * - No package attribute (now uses namespace in build.gradle for AGP 8.x)
 * - Foreground services declare foregroundServiceType
 */
class ManifestValidationTest {

    private val projectRoot = findProjectRoot()
    private val manifestFile = File(projectRoot, "presentation/src/main/AndroidManifest.xml")

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle").exists()) return dir
            dir = dir.parentFile
        }
        return File(".")
    }

    @Test
    fun `manifest file exists`() {
        assertTrue("AndroidManifest.xml not found", manifestFile.exists())
    }

    @Test
    fun `package attribute is not present in manifest`() {
        val content = manifestFile.readText()
        val manifestTag = Regex("<manifest[^>]*>").find(content)?.value ?: ""
        assertFalse(
            "Manifest still has package attribute (should use namespace in build.gradle for AGP 8.x)",
            manifestTag.contains("package=")
        )
    }

    @Test
    fun `requestLegacyExternalStorage is not present`() {
        val content = manifestFile.readText()
        assertFalse(
            "requestLegacyExternalStorage is present but ignored on API 30+",
            content.contains("requestLegacyExternalStorage")
        )
    }

    @Test
    fun `POST_NOTIFICATIONS permission is declared`() {
        val content = manifestFile.readText()
        assertTrue(
            "POST_NOTIFICATIONS permission missing (required for API 33+)",
            content.contains("android.permission.POST_NOTIFICATIONS")
        )
    }

    @Test
    fun `FOREGROUND_SERVICE_DATA_SYNC permission is declared`() {
        val content = manifestFile.readText()
        assertTrue(
            "FOREGROUND_SERVICE_DATA_SYNC permission missing (required for API 34+)",
            content.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
        )
    }

    @Test
    fun `SCHEDULE_EXACT_ALARM permission is declared`() {
        val content = manifestFile.readText()
        assertTrue(
            "SCHEDULE_EXACT_ALARM permission missing (required for API 31+)",
            content.contains("android.permission.SCHEDULE_EXACT_ALARM")
        )
    }

    @Test
    fun `READ_MEDIA permissions are declared`() {
        val content = manifestFile.readText()
        assertTrue(
            "READ_MEDIA_IMAGES permission missing (required for API 33+)",
            content.contains("android.permission.READ_MEDIA_IMAGES")
        )
    }

    @Test
    fun `WRITE_EXTERNAL_STORAGE is scoped to maxSdkVersion 28`() {
        val content = manifestFile.readText()
        if (content.contains("WRITE_EXTERNAL_STORAGE")) {
            assertTrue(
                "WRITE_EXTERNAL_STORAGE should have maxSdkVersion=\"28\"",
                content.contains("maxSdkVersion=\"28\"")
            )
        }
    }

    @Test
    fun `all activities with intent-filters have exported attribute`() {
        assertExportedPresent("activity")
    }

    @Test
    fun `all receivers with intent-filters have exported attribute`() {
        assertExportedPresent("receiver")
    }

    @Test
    fun `all services with intent-filters have exported attribute`() {
        assertExportedPresent("service")
    }

    @Test
    fun `foreground services declare foregroundServiceType`() {
        val content = manifestFile.readText()
        // Services that use startForeground should have foregroundServiceType
        if (content.contains("RestoreBackupService")) {
            assertTrue(
                "RestoreBackupService should declare foregroundServiceType",
                content.contains("foregroundServiceType")
            )
        }
    }

    private fun assertExportedPresent(componentType: String) {
        val content = manifestFile.readText()
        val violations = mutableListOf<String>()

        // Simple regex-based check: find components with intent-filters but no android:exported
        val componentRegex = Regex(
            "<$componentType\\s[^>]*?(?:>\\s*<intent-filter)",
            RegexOption.DOT_MATCHES_ALL
        )

        for (match in componentRegex.findAll(content)) {
            val componentTag = match.value
            if (!componentTag.contains("android:exported")) {
                val nameRegex = Regex("android:name=\"([^\"]+)\"")
                val name = nameRegex.find(componentTag)?.groupValues?.get(1) ?: "unknown"
                violations.add("$componentType '$name' has intent-filter but no android:exported attribute")
            }
        }

        assertTrue(
            "Components with intent-filters missing android:exported (required for API 31+):\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
