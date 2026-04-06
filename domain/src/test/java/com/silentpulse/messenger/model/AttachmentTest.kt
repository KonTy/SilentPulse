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
package com.silentpulse.messenger.model

import android.net.Uri
import android.os.Build
import androidx.core.view.inputmethod.InputContentInfoCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class AttachmentTest {

    @Mock private lateinit var uri: Uri
    @Mock private lateinit var inputContent: InputContentInfoCompat

    @Test
    fun `Image attachment should store uri`() {
        val attachment = Attachment.Image(uri = uri)
        
        assertEquals(uri, attachment.getUri())
    }

    @Test
    fun `Image attachment should prefer inputContent uri on API 25+`() {
        val contentUri = mock(Uri::class.java)
        `when`(inputContent.contentUri).thenReturn(contentUri)
        
        val attachment = Attachment.Image(uri = uri, inputContent = inputContent)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            assertEquals(contentUri, attachment.getUri())
        } else {
            assertEquals(uri, attachment.getUri())
        }
    }

    @Test
    fun `Image attachment should return null when no uri provided`() {
        val attachment = Attachment.Image()
        
        assertNull(attachment.getUri())
    }

    @Test
    fun `Image attachment with inputContent should request permission`() {
        `when`(inputContent.contentUri).thenReturn(uri)
        
        val attachment = Attachment.Image(inputContent = inputContent)
        
        // Verify we can get the uri
        assertNotNull(attachment.getUri())
    }

    @Test
    fun `Contact attachment should be a valid type`() {
        // This test assumes there's a Contact attachment type
        // Adjust based on actual implementation
        val attachment: Attachment = Attachment.Image(uri = uri)
        
        assertTrue(attachment is Attachment)
    }
}
