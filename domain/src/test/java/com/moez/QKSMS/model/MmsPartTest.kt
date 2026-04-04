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
package com.moez.QKSMS.model

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class MmsPartTest {

    private lateinit var mmsPart: MmsPart

    @Before
    fun setUp() {
        mmsPart = MmsPart()
    }

    @Test
    fun `mmsPart should have default values`() {
        assertEquals(0L, mmsPart.id)
        assertEquals("", mmsPart.type)
        assertNull(mmsPart.text)
    }

    @Test
    fun `getSummary should return text for text parts`() {
        mmsPart.type = "text/plain"
        mmsPart.text = "Hello world"
        
        assertEquals("Hello world", mmsPart.getSummary())
    }

    @Test
    fun `getSummary should return null for non-text parts`() {
        mmsPart.type = "image/jpeg"
        mmsPart.text = null
        
        assertNull(mmsPart.getSummary())
    }

    @Test
    fun `getSummary should return null when text is null`() {
        mmsPart.type = "text/plain"
        mmsPart.text = null
        
        assertNull(mmsPart.getSummary())
    }

    @Test
    fun `isText should return true for text types`() {
        mmsPart.type = "text/plain"
        assertTrue(mmsPart.isText())
        
        mmsPart.type = "text/html"
        assertTrue(mmsPart.isText())
    }

    @Test
    fun `isText should return false for non-text types`() {
        mmsPart.type = "image/jpeg"
        assertFalse(mmsPart.isText())
        
        mmsPart.type = "video/mp4"
        assertFalse(mmsPart.isText())
    }

    @Test
    fun `isImage should return true for image types`() {
        mmsPart.type = "image/jpeg"
        assertTrue(mmsPart.isImage())
        
        mmsPart.type = "image/png"
        assertTrue(mmsPart.isImage())
    }

    @Test
    fun `isVideo should return true for video types`() {
        mmsPart.type = "video/mp4"
        assertTrue(mmsPart.isVideo())
        
        mmsPart.type = "video/3gpp"
        assertTrue(mmsPart.isVideo())
    }

    @Test
    fun `mmsPart should allow setting all properties`() {
        mmsPart.id = 456L
        mmsPart.type = "image/png"
        mmsPart.text = "Some text"
        
        assertEquals(456L, mmsPart.id)
        assertEquals("image/png", mmsPart.type)
        assertEquals("Some text", mmsPart.text)
    }
}
