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

import android.provider.Telephony
import io.realm.RealmList
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class ConversationTest {

    private lateinit var conversation: Conversation
    private lateinit var mockRecipient1: Recipient
    private lateinit var mockRecipient2: Recipient
    private lateinit var mockMessage: Message

    @Before
    fun setUp() {
        mockRecipient1 = mock(Recipient::class.java).apply {
            `when`(getDisplayName()).thenReturn("John Doe")
        }
        mockRecipient2 = mock(Recipient::class.java).apply {
            `when`(getDisplayName()).thenReturn("Jane Smith")
        }
        mockMessage = mock(Message::class.java).apply {
            `when`(date).thenReturn(1234567890L)
            `when`(getSummary()).thenReturn("Test message")
            `when`(read).thenReturn(false)
            `when`(isMe()).thenReturn(false)
        }

        conversation = Conversation(
            id = 1L,
            recipients = RealmList(mockRecipient1),
            lastMessage = mockMessage
        )
    }

    @Test
    fun `date should return lastMessage date`() {
        assertEquals(1234567890L, conversation.date)
    }

    @Test
    fun `date should return 0 when no lastMessage`() {
        conversation.lastMessage = null
        assertEquals(0L, conversation.date)
    }

    @Test
    fun `snippet should return lastMessage summary`() {
        assertEquals("Test message", conversation.snippet)
    }

    @Test
    fun `snippet should return null when no lastMessage`() {
        conversation.lastMessage = null
        assertNull(conversation.snippet)
    }

    @Test
    fun `unread should return true when lastMessage is unread`() {
        `when`(mockMessage.read).thenReturn(false)
        assertTrue(conversation.unread)
    }

    @Test
    fun `unread should return false when lastMessage is read`() {
        `when`(mockMessage.read).thenReturn(true)
        assertFalse(conversation.unread)
    }

    @Test
    fun `unread should return false when no lastMessage`() {
        conversation.lastMessage = null
        assertFalse(conversation.unread)
    }

    @Test
    fun `me should return true when lastMessage isMe`() {
        `when`(mockMessage.isMe()).thenReturn(true)
        assertTrue(conversation.me)
    }

    @Test
    fun `me should return false when lastMessage is not me`() {
        `when`(mockMessage.isMe()).thenReturn(false)
        assertFalse(conversation.me)
    }

    @Test
    fun `me should return false when no lastMessage`() {
        conversation.lastMessage = null
        assertFalse(conversation.me)
    }

    @Test
    fun `getTitle should return custom name when set`() {
        conversation.name = "My Custom Group"
        assertEquals("My Custom Group", conversation.getTitle())
    }

    @Test
    fun `getTitle should return single recipient name when no custom name`() {
        conversation.name = ""
        assertEquals("John Doe", conversation.getTitle())
    }

    @Test
    fun `getTitle should return all recipient names joined when multiple recipients`() {
        conversation.recipients = RealmList(mockRecipient1, mockRecipient2)
        conversation.name = ""
        assertEquals("John Doe, Jane Smith", conversation.getTitle())
    }

    @Test
    fun `getTitle should ignore blank custom name`() {
        conversation.name = "   "
        assertEquals("John Doe", conversation.getTitle())
    }

    @Test
    fun `archived should default to false`() {
        val newConversation = Conversation()
        assertFalse(newConversation.archived)
    }

    @Test
    fun `blocked should default to false`() {
        val newConversation = Conversation()
        assertFalse(newConversation.blocked)
    }

    @Test
    fun `pinned should default to false`() {
        val newConversation = Conversation()
        assertFalse(newConversation.pinned)
    }

    @Test
    fun `draft should default to empty string`() {
        val newConversation = Conversation()
        assertEquals("", newConversation.draft)
    }

    @Test
    fun `blockingClient should be nullable`() {
        conversation.blockingClient = 5
        assertEquals(5, conversation.blockingClient)
        
        conversation.blockingClient = null
        assertNull(conversation.blockingClient)
    }

    @Test
    fun `blockReason should be nullable`() {
        conversation.blockReason = "Spam"
        assertEquals("Spam", conversation.blockReason)
        
        conversation.blockReason = null
        assertNull(conversation.blockReason)
    }

    @Test
    fun `conversation with all properties set`() {
        val fullConversation = Conversation(
            id = 123L,
            archived = true,
            blocked = true,
            pinned = true,
            recipients = RealmList(mockRecipient1, mockRecipient2),
            lastMessage = mockMessage,
            draft = "Draft message",
            blockingClient = 1,
            blockReason = "Blocked reason",
            name = "Group Chat"
        )

        assertEquals(123L, fullConversation.id)
        assertTrue(fullConversation.archived)
        assertTrue(fullConversation.blocked)
        assertTrue(fullConversation.pinned)
        assertEquals(2, fullConversation.recipients.size)
        assertEquals(mockMessage, fullConversation.lastMessage)
        assertEquals("Draft message", fullConversation.draft)
        assertEquals(1, fullConversation.blockingClient)
        assertEquals("Blocked reason", fullConversation.blockReason)
        assertEquals("Group Chat", fullConversation.name)
    }
}
