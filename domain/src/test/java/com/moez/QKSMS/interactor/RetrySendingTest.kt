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

import com.moez.QKSMS.model.Message
import com.moez.QKSMS.repository.MessageRepository
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class RetrySendingTest {

    @Mock private lateinit var messageRepo: MessageRepository
    @Mock private lateinit var message: Message

    private lateinit var retrySending: RetrySending

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        retrySending = RetrySending(messageRepo)
    }

    @Test
    fun `valid SMS message ID triggers resend`() {
        // Arrange
        val messageId = 123L
        `when`(messageRepo.getMessage(messageId)).thenReturn(message)
        `when`(message.isSms()).thenReturn(true)

        // Act
        val testObserver = retrySending.buildObservable(messageId).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertValue(message)
        
        verify(messageRepo).markSending(messageId)
        verify(messageRepo).sendSms(message)
        verify(messageRepo, never()).resendMms(message)
    }

    @Test
    fun `valid MMS message ID triggers resend`() {
        // Arrange
        val messageId = 456L
        `when`(messageRepo.getMessage(messageId)).thenReturn(message)
        `when`(message.isSms()).thenReturn(false)

        // Act
        val testObserver = retrySending.buildObservable(messageId).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertValue(message)
        
        verify(messageRepo).markSending(messageId)
        verify(messageRepo).resendMms(message)
        verify(messageRepo, never()).sendSms(message)
    }

    @Test
    fun `missing message ID is handled gracefully`() {
        // Arrange
        val messageId = 999L
        `when`(messageRepo.getMessage(messageId)).thenReturn(null)

        // Act
        val testObserver = retrySending.buildObservable(messageId).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertNoValues()
        
        verify(messageRepo).markSending(messageId)
        verify(messageRepo, never()).sendSms(org.mockito.ArgumentMatchers.any())
        verify(messageRepo, never()).resendMms(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `wrong message ID returns no values`() {
        // Arrange
        val wrongId = -1L
        `when`(messageRepo.getMessage(wrongId)).thenReturn(null)

        // Act
        val testObserver = retrySending.buildObservable(wrongId).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertNoValues()
        
        verify(messageRepo).markSending(wrongId)
    }

    @Test
    fun `markSending is called before retrieving message`() {
        // Arrange
        val messageId = 100L
        `when`(messageRepo.getMessage(messageId)).thenReturn(message)
        `when`(message.isSms()).thenReturn(true)

        // Act
        retrySending.buildObservable(messageId).test()

        // Assert - verify order of operations
        val inOrder = org.mockito.Mockito.inOrder(messageRepo)
        inOrder.verify(messageRepo).markSending(messageId)
        inOrder.verify(messageRepo).getMessage(messageId)
        inOrder.verify(messageRepo).sendSms(message)
    }

    @Test
    fun `multiple retry attempts work correctly`() {
        // Arrange
        val messageId1 = 100L
        val messageId2 = 200L
        val message1 = org.mockito.Mockito.mock(Message::class.java)
        val message2 = org.mockito.Mockito.mock(Message::class.java)
        
        `when`(messageRepo.getMessage(messageId1)).thenReturn(message1)
        `when`(messageRepo.getMessage(messageId2)).thenReturn(message2)
        `when`(message1.isSms()).thenReturn(true)
        `when`(message2.isSms()).thenReturn(false)

        // Act
        retrySending.buildObservable(messageId1).test()
        retrySending.buildObservable(messageId2).test()

        // Assert
        verify(messageRepo).markSending(messageId1)
        verify(messageRepo).markSending(messageId2)
        verify(messageRepo).sendSms(message1)
        verify(messageRepo).resendMms(message2)
    }

    @Test
    fun `zero message ID is handled`() {
        // Arrange
        val messageId = 0L
        `when`(messageRepo.getMessage(messageId)).thenReturn(null)

        // Act
        val testObserver = retrySending.buildObservable(messageId).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertNoValues()
        
        verify(messageRepo).markSending(0L)
    }
}
