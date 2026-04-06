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
class MarkReadTest {

    @Mock private lateinit var messageRepo: MessageRepository
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var updateBadge: UpdateBadge

    private lateinit var markRead: MarkRead

    @Before
    fun setUp() {
        `when`(updateBadge.buildObservable(Unit)).thenReturn(Flowable.just(Unit))
        markRead = MarkRead(messageRepo, notificationManager, updateBadge)
    }

    @Test
    fun `mark read single thread should update repository and notifications`() {
        // Given
        val threadIds = listOf(1L)

        // When
        val testSubscriber = markRead.buildObservable(threadIds).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        verify(messageRepo).markRead(1L)
        verify(notificationManager).update(1L)
        verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `mark read multiple threads should update repository and notifications for each`() {
        // Given
        val threadIds = listOf(1L, 2L, 3L)

        // When
        val testSubscriber = markRead.buildObservable(threadIds).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        verify(messageRepo).markRead(1L, 2L, 3L)
        verify(notificationManager).update(1L)
        verify(notificationManager).update(2L)
        verify(notificationManager).update(3L)
        verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `mark read empty list should complete without errors`() {
        // Given
        val threadIds = emptyList<Long>()

        // When
        val testSubscriber = markRead.buildObservable(threadIds).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        verify(messageRepo).markRead()
        verify(notificationManager, never()).update(anyLong())
        verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `mark read should call operations in correct order`() {
        // Given
        val threadIds = listOf(1L)
        val inOrder = inOrder(messageRepo, notificationManager, updateBadge)

        // When
        markRead.buildObservable(threadIds).test()

        // Then
        inOrder.verify(messageRepo).markRead(1L)
        inOrder.verify(notificationManager).update(1L)
        inOrder.verify(updateBadge).buildObservable(Unit)
    }

    @Test
    fun `mark read should propagate repository errors`() {
        // Given
        val threadIds = listOf(1L)
        val exception = RuntimeException("Repository error")
        doThrow(exception).`when`(messageRepo).markRead(anyLong())

        // When
        val testSubscriber = markRead.buildObservable(threadIds).test()

        // Then
        testSubscriber.assertError(exception)
        testSubscriber.assertNotComplete()
    }

    @Test
    fun `mark read should handle notification manager errors gracefully`() {
        // Given
        val threadIds = listOf(1L)
        val exception = RuntimeException("Notification error")
        doThrow(exception).`when`(notificationManager).update(anyLong())

        // When
        val testSubscriber = markRead.buildObservable(threadIds).test()

        // Then
        testSubscriber.assertError(exception)
        verify(messageRepo).markRead(1L)
    }

    @Test
    fun `mark read should handle update badge errors`() {
        // Given
        val threadIds = listOf(1L)
        val exception = RuntimeException("Badge error")
        `when`(updateBadge.buildObservable(Unit)).thenReturn(Flowable.error(exception))

        // When
        val testSubscriber = markRead.buildObservable(threadIds).test()

        // Then
        testSubscriber.assertError(exception)
        verify(messageRepo).markRead(1L)
        verify(notificationManager).update(1L)
    }
}
