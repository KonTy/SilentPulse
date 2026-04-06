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

import com.silentpulse.messenger.manager.NotificationManager
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
class DeleteMessagesTest {

    @Mock private lateinit var conversationRepo: ConversationRepository
    @Mock private lateinit var messageRepo: MessageRepository
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var updateBadge: UpdateBadge

    private lateinit var deleteMessages: DeleteMessages

    @Before
    fun setUp() {
        `when`(updateBadge.buildObservable(Unit)).thenReturn(Flowable.just(Unit))
        deleteMessages = DeleteMessages(conversationRepo, messageRepo, notificationManager, updateBadge)
    }

    @Test
    fun `delete single message should update all components`() {
        // Given
        val messageIds = listOf(100L)
        val threadId = 1L
        val params = DeleteMessages.Params(messageIds, threadId)

        // When
        val testSubscriber = deleteMessages.buildObservable(params).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        verify(messageRepo).deleteMessages(100L)
        verify(conversationRepo).updateConversations(threadId)
        verify(notificationManager).update(threadId)
        verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `delete multiple messages should delete all messages`() {
        // Given
        val messageIds = listOf(100L, 200L, 300L)
        val threadId = 1L
        val params = DeleteMessages.Params(messageIds, threadId)

        // When
        val testSubscriber = deleteMessages.buildObservable(params).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        verify(messageRepo).deleteMessages(100L, 200L, 300L)
        verify(conversationRepo).updateConversations(threadId)
        verify(notificationManager).update(threadId)
    }

    @Test
    fun `delete empty message list should complete without errors`() {
        // Given
        val messageIds = emptyList<Long>()
        val threadId = 1L
        val params = DeleteMessages.Params(messageIds, threadId)

        // When
        val testSubscriber = deleteMessages.buildObservable(params).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        verify(messageRepo).deleteMessages()
        verify(conversationRepo).updateConversations(threadId)
    }

    @Test
    fun `delete messages should call operations in correct order`() {
        // Given
        val messageIds = listOf(100L)
        val threadId = 1L
        val params = DeleteMessages.Params(messageIds, threadId)
        val inOrder = inOrder(messageRepo, conversationRepo, notificationManager, updateBadge)

        // When
        deleteMessages.buildObservable(params).test()

        // Then
        inOrder.verify(messageRepo).deleteMessages(100L)
        inOrder.verify(conversationRepo).updateConversations(threadId)
        inOrder.verify(notificationManager).update(threadId)
        inOrder.verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `delete messages should propagate repository errors`() {
        // Given
        val messageIds = listOf(100L)
        val threadId = 1L
        val params = DeleteMessages.Params(messageIds, threadId)
        val exception = RuntimeException("Delete failed")
        doThrow(exception).`when`(messageRepo).deleteMessages(anyLong())

        // When
        val testSubscriber = deleteMessages.buildObservable(params).test()

        // Then
        testSubscriber.assertError(exception)
        testSubscriber.assertNotComplete()
    }

    @Test
    fun `delete messages should handle conversation update errors`() {
        // Given
        val messageIds = listOf(100L)
        val threadId = 1L
        val params = DeleteMessages.Params(messageIds, threadId)
        val exception = RuntimeException("Update failed")
        doThrow(exception).`when`(conversationRepo).updateConversations(anyLong())

        // When
        val testSubscriber = deleteMessages.buildObservable(params).test()

        // Then
        testSubscriber.assertError(exception)
        verify(messageRepo).deleteMessages(100L)
    }

    @Test
    fun `params should contain correct data`() {
        // Given
        val messageIds = listOf(1L, 2L, 3L)
        val threadId = 5L

        // When
        val params = DeleteMessages.Params(messageIds, threadId)

        // Then
        assert(params.messageIds == messageIds)
        assert(params.threadId == threadId)
    }
}
