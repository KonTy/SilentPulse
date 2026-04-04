/*
 * Tests to verify dependency versions meet minimum requirements for modern Android.
 */
package com.moez.QKSMS

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Validates that key dependency versions in the root build.gradle
 * meet the minimum requirements for Android 14/15 (API 34/35) support.
 */
class DependencyVersionTest {

    private val projectRoot = findProjectRoot()

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle").exists()) return dir
            dir = dir.parentFile
        }
        return File(".")
    }

    private fun getVersionFromBuildGradle(key: String): String? {
        val buildGradle = File(projectRoot, "build.gradle")
        if (!buildGradle.exists()) return null
        val regex = Regex("ext\\.$key\\s*=\\s*['\"]([^'\"]+)['\"]")
        return regex.find(buildGradle.readText())?.groupValues?.get(1)
    }

    private fun parseVersionParts(version: String): List<Int> {
        return version.split(".")
            .mapNotNull { it.replace(Regex("[^0-9]"), "").toIntOrNull() }
    }

    private fun assertMinVersion(key: String, minVersion: String) {
        val actual = getVersionFromBuildGradle(key)
        assertNotNull("$key not found in build.gradle", actual)
        val actualParts = parseVersionParts(actual!!)
        val minParts = parseVersionParts(minVersion)

        for (i in 0 until maxOf(actualParts.size, minParts.size)) {
            val a = actualParts.getOrElse(i) { 0 }
            val m = minParts.getOrElse(i) { 0 }
            if (a > m) return // actual is higher
            if (a < m) {
                fail("$key version $actual is below minimum $minVersion")
                return
            }
        }
        // Equal is fine
    }

    @Test
    fun `Kotlin version is at least 1_9`() {
        assertMinVersion("kotlin_version", "1.9.0")
    }

    @Test
    fun `Dagger version is at least 2_48`() {
        assertMinVersion("dagger_version", "2.48")
    }

    @Test
    fun `Glide version is at least 4_14`() {
        assertMinVersion("glide_version", "4.14.0")
    }

    @Test
    fun `Coroutines version is at least 1_7`() {
        assertMinVersion("coroutines_version", "1.7.0")
    }

    @Test
    fun `Lifecycle version is at least 2_6`() {
        assertMinVersion("lifecycle_version", "2.6.0")
    }

    @Test
    fun `Material version is at least 1_9`() {
        assertMinVersion("material_version", "1.9.0")
    }

    @Test
    fun `OkHttp version is at least 4_10`() {
        assertMinVersion("okhttp3_version", "4.10.0")
    }

    @Test
    fun `Timber version is at least 5_0`() {
        assertMinVersion("timber_version", "5.0.0")
    }

    @Test
    fun `AGP version is at least 8_0`() {
        val buildGradle = File(projectRoot, "build.gradle")
        val content = buildGradle.readText()
        val regex = Regex("com\\.android\\.tools\\.build:gradle:([\\d.]+)")
        val version = regex.find(content)?.groupValues?.get(1)
        assertNotNull("AGP version not found", version)
        val parts = parseVersionParts(version!!)
        assertTrue("AGP version $version must be >= 8.0", parts[0] >= 8)
    }

    @Test
    fun `Gradle wrapper version is at least 8_0`() {
        val propsFile = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")
        val content = propsFile.readText()
        val regex = Regex("gradle-(\\d+)\\.(\\d+)")
        val match = regex.find(content)
        assertNotNull("Gradle version not found in wrapper properties", match)
        val major = match!!.groupValues[1].toInt()
        assertTrue("Gradle wrapper version must be >= 8.0, found $major.${match.groupValues[2]}", major >= 8)
    }

    @Test
    fun `android useAndroidX is enabled`() {
        val propsFile = File(projectRoot, "gradle.properties")
        val content = propsFile.readText()
        assertTrue("android.useAndroidX must be true", content.contains("android.useAndroidX=true"))
    }

    @Test
    fun `Jetifier is disabled`() {
        val propsFile = File(projectRoot, "gradle.properties")
        val content = propsFile.readText()
        assertTrue(
            "android.enableJetifier should be false (no longer needed)",
            content.contains("android.enableJetifier=false")
        )
    }
}
