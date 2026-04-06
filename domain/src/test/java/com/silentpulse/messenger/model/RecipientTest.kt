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

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class RecipientTest {

    private lateinit var recipient: Recipient

    @Before
    fun setUp() {
        recipient = Recipient()
    }

    @Test
    fun `recipient should have default values`() {
        assertEquals(0L, recipient.id)
        assertNull(recipient.address)
        assertNull(recipient.contact)
    }

    @Test
    fun `getDisplayName should return contact name when available`() {
        val contact = Contact().apply {
            name = "John Doe"
        }
        recipient.contact = contact
        recipient.address = "1234567890"
        
        assertEquals("John Doe", recipient.getDisplayName())
    }

    @Test
    fun `getDisplayName should return address when contact is null`() {
        recipient.contact = null
        recipient.address = "1234567890"
        
        assertEquals("1234567890", recipient.getDisplayName())
    }

    @Test
    fun `getDisplayName should return address when contact name is empty`() {
        val contact = Contact().apply {
            name = ""
        }
        recipient.contact = contact
        recipient.address = "1234567890"
        
        assertEquals("1234567890", recipient.getDisplayName())
    }

    @Test
    fun `getDisplayName should return empty when both contact and address are null`() {
        recipient.contact = null
        recipient.address = null
        
        assertEquals("", recipient.getDisplayName())
    }

    @Test
    fun `recipient should allow setting all properties`() {
        val contact = Contact().apply {
            name = "Jane Smith"
        }
        
        recipient.id = 123L
        recipient.address = "5555555555"
        recipient.contact = contact
        
        assertEquals(123L, recipient.id)
        assertEquals("5555555555", recipient.address)
        assertEquals(contact, recipient.contact)
    }
}
