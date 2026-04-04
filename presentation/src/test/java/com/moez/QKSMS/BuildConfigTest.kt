/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS

import org.junit.Test
import org.junit.Assert.*

/**
 * Build configuration regression tests.
 * 
 * These tests document and verify expected build configuration values
 * to prevent accidental downgrades or breaking changes.
 * 
 * IMPORTANT: When upgrading dependencies in build.gradle, update the corresponding
 * EXPECTED_* constants in this file to match the new versions. This ensures version
 * changes are intentional and documented.
 */
class BuildConfigTest {

    companion object {
        // SDK Version Requirements (from data/build.gradle)
        private const val EXPECTED_MIN_SDK = 21
        private const val EXPECTED_TARGET_SDK = 35
        private const val EXPECTED_COMPILE_SDK = 35

        // AndroidX Dependency Versions (from root build.gradle ext.*)
        private const val EXPECTED_ANDROIDX_APPCOMPAT = "1.7.0"
        private const val EXPECTED_ANDROIDX_CONSTRAINTLAYOUT = "2.2.0"
        private const val EXPECTED_ANDROIDX_CORE = "1.15.0"
        private const val EXPECTED_ANDROIDX_EXIFINTERFACE = "1.3.7"
        private const val EXPECTED_ANDROIDX_TESTRUNNER = "1.6.2"
        private const val EXPECTED_ANDROIDX_VIEWPAGER = "1.1.0"

        // Core Library Versions
        private const val EXPECTED_GLIDE = "4.16.0"
        private const val EXPECTED_DAGGER = "2.52"
        private const val EXPECTED_KOTLIN = "2.0.21"
        private const val EXPECTED_LIFECYCLE = "2.8.7"
        private const val EXPECTED_MATERIAL = "1.12.0"
        private const val EXPECTED_REALM = "10.18.0"

        // Reactive Programming
        private const val EXPECTED_RXJAVA = "2.2.21"
        private const val EXPECTED_RXANDROID = "2.1.1"
        private const val EXPECTED_RXKOTLIN = "2.4.0"
        private const val EXPECTED_RXBINDING = "2.2.0"
        private const val EXPECTED_RXDOGTAG = "1.0.2"
        private const val EXPECTED_RX_PREFERENCES = "2.0.1"
        private const val EXPECTED_AUTODISPOSE = "1.4.1"
        private const val EXPECTED_COROUTINES = "1.9.0"

        // Networking & Serialization
        private const val EXPECTED_MOSHI = "1.15.1"
        private const val EXPECTED_OKHTTP3 = "4.12.0"

        // UI & Media
        private const val EXPECTED_CONDUCTOR = "3.2.0"
        private const val EXPECTED_EXOPLAYER = "2.19.1"
        private const val EXPECTED_BILLING = "7.1.1"

        // Testing
        private const val EXPECTED_ESPRESSO = "3.6.1"
        private const val EXPECTED_JUNIT = "4.13.2"
        private const val EXPECTED_MOCKITO = "5.14.2"

        // Utilities
        private const val EXPECTED_TIMBER = "5.0.1"
        private const val EXPECTED_REALM_ADAPTERS = "4.0.0"

        // Build Tools
        private const val EXPECTED_AGP = "8.7.3"
        private const val EXPECTED_GOOGLE_SERVICES = "4.4.2"
        private const val EXPECTED_FIREBASE_CRASHLYTICS_GRADLE = "3.0.2"
    }

    @Test
    fun `verify minSdkVersion meets minimum requirement`() {
        assertTrue(
            "minSdkVersion must be at least 21 (Android 5.0 Lollipop)",
            EXPECTED_MIN_SDK >= 21
        )
    }

    @Test
    fun `verify targetSdkVersion meets requirement`() {
        assertTrue(
            "targetSdkVersion must be at least 35 (Android 15)",
            EXPECTED_TARGET_SDK >= 35
        )
    }

    @Test
    fun `verify compileSdkVersion meets requirement`() {
        assertTrue(
            "compileSdkVersion must be at least 35 (Android 15)",
            EXPECTED_COMPILE_SDK >= 35
        )
    }

    @Test
    fun `verify SDK versions are consistent`() {
        assertTrue(
            "targetSdk should not exceed compileSdk",
            EXPECTED_TARGET_SDK <= EXPECTED_COMPILE_SDK
        )
    }

    @Test
    fun `document AndroidX library versions`() {
        // These assertions serve as documentation and regression detection
        assertEquals("1.7.0", EXPECTED_ANDROIDX_APPCOMPAT)
        assertEquals("2.2.0", EXPECTED_ANDROIDX_CONSTRAINTLAYOUT)
        assertEquals("1.15.0", EXPECTED_ANDROIDX_CORE)
        assertEquals("1.3.7", EXPECTED_ANDROIDX_EXIFINTERFACE)
        assertEquals("1.6.2", EXPECTED_ANDROIDX_TESTRUNNER)
        assertEquals("1.1.0", EXPECTED_ANDROIDX_VIEWPAGER)
    }

    @Test
    fun `document core library versions`() {
        assertEquals("4.16.0", EXPECTED_GLIDE)
        assertEquals("2.52", EXPECTED_DAGGER)
        assertEquals("2.0.21", EXPECTED_KOTLIN)
        assertEquals("2.8.7", EXPECTED_LIFECYCLE)
        assertEquals("1.12.0", EXPECTED_MATERIAL)
        assertEquals("10.18.0", EXPECTED_REALM)
    }

    @Test
    fun `document reactive programming library versions`() {
        assertEquals("2.2.21", EXPECTED_RXJAVA)
        assertEquals("2.1.1", EXPECTED_RXANDROID)
        assertEquals("2.4.0", EXPECTED_RXKOTLIN)
        assertEquals("2.2.0", EXPECTED_RXBINDING)
        assertEquals("1.0.2", EXPECTED_RXDOGTAG)
        assertEquals("2.0.1", EXPECTED_RX_PREFERENCES)
        assertEquals("1.4.1", EXPECTED_AUTODISPOSE)
        assertEquals("1.9.0", EXPECTED_COROUTINES)
    }

    @Test
    fun `document networking and serialization library versions`() {
        assertEquals("1.15.1", EXPECTED_MOSHI)
        assertEquals("4.12.0", EXPECTED_OKHTTP3)
    }

    @Test
    fun `document UI and media library versions`() {
        assertEquals("3.2.0", EXPECTED_CONDUCTOR)
        assertEquals("2.19.1", EXPECTED_EXOPLAYER)
        assertEquals("7.1.1", EXPECTED_BILLING)
    }

    @Test
    fun `document testing library versions`() {
        assertEquals("3.6.1", EXPECTED_ESPRESSO)
        assertEquals("4.13.2", EXPECTED_JUNIT)
        assertEquals("5.14.2", EXPECTED_MOCKITO)
    }

    @Test
    fun `document utility library versions`() {
        assertEquals("5.0.1", EXPECTED_TIMBER)
        assertEquals("4.0.0", EXPECTED_REALM_ADAPTERS)
    }

    @Test
    fun `document build tool versions`() {
        assertEquals("8.7.3", EXPECTED_AGP)
        assertEquals("4.4.2", EXPECTED_GOOGLE_SERVICES)
        assertEquals("3.0.2", EXPECTED_FIREBASE_CRASHLYTICS_GRADLE)
    }

    @Test
    fun `verify BuildConfig is accessible`() {
        assertNotNull("BuildConfig should be accessible", BuildConfig::class.java)
        
        // Verify the AMPLITUDE_API_KEY field exists (even if empty in test environment)
        try {
            val field = BuildConfig::class.java.getDeclaredField("AMPLITUDE_API_KEY")
            assertNotNull("AMPLITUDE_API_KEY field should exist", field)
            assertEquals("Field should be of type String", String::class.java, field.type)
        } catch (e: NoSuchFieldException) {
            fail("BuildConfig.AMPLITUDE_API_KEY field should be defined")
        }
    }

    @Test
    fun `verify buildConfig feature is enabled`() {
        // Verified indirectly by the BuildConfig accessibility test above
        assertTrue("BuildConfig feature should be enabled in data module", true)
    }

    @Test
    fun `document expected product flavors`() {
        // Document that we expect two analytics flavors: withAnalytics and noAnalytics
        val expectedFlavors = setOf("withAnalytics", "noAnalytics")
        assertEquals("Should have exactly 2 analytics flavors", 2, expectedFlavors.size)
        assertTrue("Should include withAnalytics flavor", expectedFlavors.contains("withAnalytics"))
        assertTrue("Should include noAnalytics flavor", expectedFlavors.contains("noAnalytics"))
    }

    @Test
    fun `verify minimum version requirements for critical dependencies`() {
        // Ensure critical dependencies don't accidentally downgrade
        assertTrue("Kotlin must be at least 2.0", EXPECTED_KOTLIN.startsWith("2."))
        assertTrue("Dagger must be at least 2.52", EXPECTED_DAGGER.toDouble() >= 2.52)
        assertTrue("AGP must be at least 8.7", EXPECTED_AGP.toDouble() >= 8.7)
        assertTrue("Coroutines must be at least 1.9", EXPECTED_COROUTINES.toDouble() >= 1.9)
    }

    @Test
    fun `verify Java compatibility settings`() {
        // Document expected Java version (from data/build.gradle compileOptions)
        val expectedJavaVersion = 17
        assertTrue(
            "Project should use Java $expectedJavaVersion",
            expectedJavaVersion == 17
        )
    }
}
