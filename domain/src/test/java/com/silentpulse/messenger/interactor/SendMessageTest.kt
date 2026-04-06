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
package com.silentpulse.messenger.interactor

import android.content.Context
import com.silentpulse.messenger.model.Conversation
import com.silentpulse.messenger.repository.ConversationRepository
import com.silentpulse.messenger.repository.MessageRepository
import io.reactivex.Flowable
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class SendMessageTest {

    @Mock private lateinit var context: Context
    @Mock private lateinit var conversationRepo: ConversationRepository
    @Mock private lateinit var messageRepo: MessageRepository
    @Mock private lateinit var updateBadge: UpdateBadge

    private lateinit var sendMessage: SendMessage

    @Before
    fun setUp() {
        `when`(updateBadge.buildObservable(Unit)).thenReturn(Flowable.just(Unit))
        sendMessage = SendMessage(context, conversationRepo, messageRepo, updateBadge)
    }

    @Test
    fun `send message with existing thread should send and update conversation`() {
        // Given
        val params = SendMessage.Params(
            subId = 1,
            threadId = 100L,
            addresses = listOf("+1234567890"),
            body = "Test message"
        )

        // When
        val testSubscriber = sendMessage.buildObservable(params).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        verify(messageRepo).sendMessage(
            params.subId,
            params.threadId,
            params.addresses,
            params.body,
            params.attachments,
            params.delay
        )
        verify(conversationRepo).updateConversations(params.threadId)
        verify(conversationRepo).markUnarchived(params.threadId)
        verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `send message without thread should create conversation`() {
        // Given
        val mockConversation = mock(Conversation::class.java)
        `when`(mockConversation.id).thenReturn(200L)
        `when`(conversationRepo.getOrCreateConversation(anyList())).thenReturn(mockConversation)
        
        val params = SendMessage.Params(
            subId = 1,
            threadId = 0L,
            addresses = listOf("+1234567890"),
            body = "Test message"
        )

        // When
        val testSubscriber = sendMessage.buildObservable(params).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        verify(conversationRepo).getOrCreateConversation(params.addresses)
        verify(conversationRepo).updateConversations(200L)
        verify(conversationRepo).markUnarchived(200L)
    }

    @Test
    fun `send message with empty addresses should not send`() {
        // Given
        val params = SendMessage.Params(
            subId = 1,
            threadId = 100L,
            addresses = emptyList(),
            body = "Test message"
        )

        // When
        val testSubscriber = sendMessage.buildObservable(params).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        testSubscriber.assertNoValues()
        verify(messageRepo, never()).sendMessage(anyInt(), anyLong(), anyList(), anyString(), anyList(), anyInt())
    }

    @Test
    fun `send message with multiple recipients should send to all`() {
        // Given
        val params = SendMessage.Params(
            subId = 1,
            threadId = 100L,
            addresses = listOf("+1234567890", "+0987654321", "+1111111111"),
            body = "Test message"
        )

        // When
        val testSubscriber = sendMessage.buildObservable(params).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        verify(messageRepo).sendMessage(
            params.subId,
            params.threadId,
            params.addresses,
            params.body,
            params.attachments,
            params.delay
        )
    }

    @Test
    fun `send message with delay should pass delay parameter`() {
        // Given
        val params = SendMessage.Params(
            subId = 1,
            threadId = 100L,
            addresses = listOf("+1234567890"),
            body = "Test message",
            delay = 5000
        )

        // When
        sendMessage.buildObservable(params).test()

        // Then
        verify(messageRepo).sendMessage(
            params.subId,
            params.threadId,
            params.addresses,
            params.body,
            params.attachments,
            5000
        )
    }

    @Test
    fun `send message should call operations in correct order`() {
        // Given
        val params = SendMessage.Params(
            subId = 1,
            threadId = 100L,
            addresses = listOf("+1234567890"),
            body = "Test message"
        )
        val inOrder = inOrder(messageRepo, conversationRepo, updateBadge)

        // When
        sendMessage.buildObservable(params).test()

        // Then
        inOrder.verify(messageRepo).sendMessage(anyInt(), anyLong(), anyList(), anyString(), anyList(), anyInt())
        inOrder.verify(conversationRepo).updateConversations(params.threadId)
        inOrder.verify(conversationRepo).markUnarchived(params.threadId)
        inOrder.verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `send message should propagate repository errors`() {
        // Given
        val params = SendMessage.Params(
            subId = 1,
            threadId = 100L,
            addresses = listOf("+1234567890"),
            body = "Test message"
        )
        val exception = RuntimeException("Send failed")
        doThrow(exception).`when`(messageRepo).sendMessage(
            anyInt(), anyLong(), anyList(), anyString(), anyList(), anyInt()
        )

        // When
        val testSubscriber = sendMessage.buildObservable(params).test()

        // Then
        testSubscriber.assertError(exception)
        testSubscriber.assertNotComplete()
    }

    @Test
    fun `params should contain correct default values`() {
        // Given/When
        val params = SendMessage.Params(
            subId = 1,
            threadId = 100L,
            addresses = listOf("+1234567890"),
            body = "Test"
        )

        // Then
        assert(params.attachments.isEmpty())
        assert(params.delay == 0)
    }
}
