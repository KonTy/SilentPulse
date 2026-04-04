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

import io.realm.RealmList
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class ScheduledMessageTest {

    private lateinit var scheduledMessage: ScheduledMessage

    @Before
    fun setUp() {
        scheduledMessage = ScheduledMessage()
    }

    @Test
    fun `scheduledMessage should have default values`() {
        assertEquals(0L, scheduledMessage.id)
        assertEquals(0L, scheduledMessage.date)
        assertEquals(-1, scheduledMessage.subId)
        assertNotNull(scheduledMessage.recipients)
        assertTrue(scheduledMessage.sendAsGroup)
        assertEquals("", scheduledMessage.body)
        assertNotNull(scheduledMessage.attachments)
    }

    @Test
    fun `scheduledMessage should allow setting all properties`() {
        val recipients = RealmList("1234567890", "0987654321")
        val attachments = RealmList("content://attachment1", "content://attachment2")
        
        scheduledMessage.id = 123L
        scheduledMessage.date = 1234567890L
        scheduledMessage.subId = 1
        scheduledMessage.recipients = recipients
        scheduledMessage.sendAsGroup = false
        scheduledMessage.body = "Scheduled message"
        scheduledMessage.attachments = attachments
        
        assertEquals(123L, scheduledMessage.id)
        assertEquals(1234567890L, scheduledMessage.date)
        assertEquals(1, scheduledMessage.subId)
        assertEquals(2, scheduledMessage.recipients.size)
        assertFalse(scheduledMessage.sendAsGroup)
        assertEquals("Scheduled message", scheduledMessage.body)
        assertEquals(2, scheduledMessage.attachments.size)
    }

    @Test
    fun `scheduledMessage recipients should be mutable`() {
        scheduledMessage.recipients.add("1234567890")
        scheduledMessage.recipients.add("0987654321")
        
        assertEquals(2, scheduledMessage.recipients.size)
        assertTrue(scheduledMessage.recipients.contains("1234567890"))
        assertTrue(scheduledMessage.recipients.contains("0987654321"))
    }

    @Test
    fun `scheduledMessage attachments should be mutable`() {
        scheduledMessage.attachments.add("content://image1")
        scheduledMessage.attachments.add("content://image2")
        
        assertEquals(2, scheduledMessage.attachments.size)
        assertTrue(scheduledMessage.attachments.contains("content://image1"))
        assertTrue(scheduledMessage.attachments.contains("content://image2"))
    }

    @Test
    fun `scheduledMessage should support individual messages`() {
        scheduledMessage.sendAsGroup = false
        assertFalse(scheduledMessage.sendAsGroup)
    }

    @Test
    fun `scheduledMessage should support group messages`() {
        scheduledMessage.sendAsGroup = true
        assertTrue(scheduledMessage.sendAsGroup)
    }
}
