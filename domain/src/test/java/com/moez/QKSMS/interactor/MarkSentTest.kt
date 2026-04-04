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
package com.moez.QKSMS.interactor

import com.moez.QKSMS.repository.MessageRepository
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class MarkSentTest {

    @Mock private lateinit var messageRepo: MessageRepository

    private lateinit var markSent: MarkSent

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        markSent = MarkSent(messageRepo)
    }

    @Test
    fun `valid message ID triggers markSent on repository`() {
        // Arrange
        val messageId = 123L

        // Act
        val testObserver = markSent.buildObservable(messageId).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertValue(Unit)
        
        verify(messageRepo).markSent(123L)
    }

    @Test
    fun `correct message ID is passed to repository`() {
        // Arrange
        val messageId = 789L

        // Act
        val testObserver = markSent.buildObservable(messageId).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).markSent(789L)
    }

    @Test
    fun `zero message ID is handled`() {
        // Arrange
        val messageId = 0L

        // Act
        val testObserver = markSent.buildObservable(messageId).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).markSent(0L)
    }

    @Test
    fun `negative message ID is handled`() {
        // Arrange
        val messageId = -1L

        // Act
        val testObserver = markSent.buildObservable(messageId).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).markSent(-1L)
    }

    @Test
    fun `multiple sequential calls work correctly`() {
        // Act
        markSent.buildObservable(100L).test()
        markSent.buildObservable(200L).test()
        markSent.buildObservable(300L).test()

        // Assert
        verify(messageRepo).markSent(100L)
        verify(messageRepo).markSent(200L)
        verify(messageRepo).markSent(300L)
    }

    @Test
    fun `status transition from pending to sent works`() {
        // This test verifies the interactor calls the correct repo method
        // The actual status transition logic is in MessageRepository
        
        // Arrange
        val messageId = 999L

        // Act
        val testObserver = markSent.buildObservable(messageId).test()

        // Assert
        testObserver.assertComplete()
        verify(messageRepo).markSent(999L)
    }
}
