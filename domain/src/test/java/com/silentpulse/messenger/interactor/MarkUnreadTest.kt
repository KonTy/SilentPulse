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
import com.silentpulse.messenger.repository.MessageRepository
import io.reactivex.Flowable
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class MarkUnreadTest {

    @Mock private lateinit var messageRepo: MessageRepository
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var updateBadge: UpdateBadge

    private lateinit var markUnread: MarkUnread

    @Before
    fun setUp() {
        markUnread = MarkUnread(messageRepo, notificationManager, updateBadge)
        
        // Setup default mocks
        `when`(updateBadge.buildObservable(Unit)).thenReturn(Flowable.just(Unit))
    }

    @Test
    fun `buildObservable should mark single thread as unread`() {
        // Given
        val threadIds = listOf(123L)

        // When
        val testSubscriber = markUnread.buildObservable(threadIds).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        
        verify(messageRepo).markUnread(123L)
        verify(notificationManager).update(123L)
        verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `buildObservable should mark multiple threads as unread`() {
        // Given
        val threadIds = listOf(123L, 456L, 789L)

        // When
        val testSubscriber = markUnread.buildObservable(threadIds).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        
        verify(messageRepo).markUnread(123L, 456L, 789L)
        verify(notificationManager).update(123L)
        verify(notificationManager).update(456L)
        verify(notificationManager).update(789L)
        verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `buildObservable should handle empty list`() {
        // Given
        val threadIds = emptyList<Long>()

        // When
        val testSubscriber = markUnread.buildObservable(threadIds).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        
        verify(messageRepo).markUnread()
        verify(notificationManager, never()).update(anyLong())
        verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `buildObservable should propagate errors from messageRepo`() {
        // Given
        val threadIds = listOf(123L)
        val exception = RuntimeException("Database error")
        doThrow(exception).`when`(messageRepo).markUnread(anyLong())

        // When
        val testSubscriber = markUnread.buildObservable(threadIds).test()

        // Then
        testSubscriber.assertError(exception)
        testSubscriber.assertNotComplete()
        
        verify(notificationManager, never()).update(anyLong())
        verify(updateBadge, never()).buildObservable(Unit)
    }

    @Test
    fun `buildObservable should execute operations in correct order`() {
        // Given
        val threadIds = listOf(123L)
        val inOrder = inOrder(messageRepo, notificationManager, updateBadge)

        // When
        markUnread.buildObservable(threadIds).test()

        // Then
        inOrder.verify(messageRepo).markUnread(123L)
        inOrder.verify(notificationManager).update(123L)
        inOrder.verify(updateBadge).buildObservable(Unit)
    }
}
