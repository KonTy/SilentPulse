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

import com.silentpulse.messenger.manager.WidgetManager
import com.silentpulse.messenger.repository.MessageRepository
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class UpdateBadgeTest {

    @Mock private lateinit var messageRepo: MessageRepository
    @Mock private lateinit var widgetManager: WidgetManager

    private lateinit var updateBadge: UpdateBadge

    @Before
    fun setUp() {
        updateBadge = UpdateBadge(messageRepo, widgetManager)
    }

    @Test
    fun `buildObservable should update widget with unread count`() {
        // Given
        val unreadCount = 5L
        `when`(messageRepo.getUnreadCount()).thenReturn(unreadCount)

        // When
        val testSubscriber = updateBadge.buildObservable(Unit).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        testSubscriber.assertValue(Unit)
        
        verify(messageRepo).getUnreadCount()
        verify(widgetManager).updateBadge(unreadCount)
    }

    @Test
    fun `buildObservable should update widget with zero when no unread messages`() {
        // Given
        `when`(messageRepo.getUnreadCount()).thenReturn(0L)

        // When
        val testSubscriber = updateBadge.buildObservable(Unit).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        
        verify(widgetManager).updateBadge(0L)
    }

    @Test
    fun `buildObservable should handle large unread counts`() {
        // Given
        val largeCount = 999L
        `when`(messageRepo.getUnreadCount()).thenReturn(largeCount)

        // When
        val testSubscriber = updateBadge.buildObservable(Unit).test()

        // Then
        testSubscriber.assertComplete()
        testSubscriber.assertNoErrors()
        
        verify(widgetManager).updateBadge(largeCount)
    }

    @Test
    fun `buildObservable should propagate repository errors`() {
        // Given
        val exception = RuntimeException("Database error")
        `when`(messageRepo.getUnreadCount()).thenThrow(exception)

        // When
        val testSubscriber = updateBadge.buildObservable(Unit).test()

        // Then
        testSubscriber.assertError(exception)
        testSubscriber.assertNotComplete()
        
        verify(widgetManager, never()).updateBadge(anyLong())
    }

    @Test
    fun `buildObservable should call repository before widget manager`() {
        // Given
        `when`(messageRepo.getUnreadCount()).thenReturn(3L)
        val inOrder = inOrder(messageRepo, widgetManager)

        // When
        updateBadge.buildObservable(Unit).test()

        // Then
        inOrder.verify(messageRepo).getUnreadCount()
        inOrder.verify(widgetManager).updateBadge(3L)
    }
}
