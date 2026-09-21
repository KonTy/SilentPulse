package com.silentpulse.messenger.manager

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ChangelogJsonAdapterTest {
    private val adapter = Moshi.Builder().build()
        .adapter<List<ChangelogManagerImpl.Changeset>>(
            Types.newParameterizedType(List::class.java, ChangelogManagerImpl.Changeset::class.java)
        )

    @Test
    fun `changelog uses a generated adapter without Kotlin reflection`() {
        val changes = adapter.fromJson(
            """[{"added":["A feature"],"improved":["An improvement"],"fixed":["A fix"],"versionName":"1.0.18","versionCode":18}]"""
        )!!.single()

        assertEquals(listOf("A feature"), changes.added)
        assertEquals(listOf("An improvement"), changes.improved)
        assertEquals(listOf("A fix"), changes.fixed)
        assertEquals("1.0.18", changes.versionName)
        assertEquals(18, changes.versionCode)
    }

    @Test
    fun `optional changelog sections may be absent or null`() {
        val changes = adapter.fromJson(
            """[{"fixed":null,"versionName":"1.0.18","versionCode":18}]"""
        )!!.single()

        assertNull(changes.added)
        assertNull(changes.improved)
        assertNull(changes.fixed)
    }

    @Test
    fun `bundled changelog parses with the generated adapter`() {
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { File(it, "settings.gradle").isFile }
        val changelog = File(root, "data/src/main/assets/changelog.json")
        val changes = adapter.fromJson(changelog.readText())

        assertNotNull(changes)
        assertFalse(changes!!.isEmpty())
    }
}
