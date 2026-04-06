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

import android.telephony.SmsMessage
import com.silentpulse.messenger.blocking.BlockingClient
import com.silentpulse.messenger.manager.NotificationManager
import com.silentpulse.messenger.manager.ShortcutManager
import com.silentpulse.messenger.model.Conversation
import com.silentpulse.messenger.model.Message
import com.silentpulse.messenger.repository.ConversationRepository
import com.silentpulse.messenger.repository.MessageRepository
import com.silentpulse.messenger.util.Preferences
import io.reactivex.Single
import io.realm.RealmList
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.anyList
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReceiveSmsTest {

    @Mock private lateinit var conversationRepo: ConversationRepository
    @Mock private lateinit var blockingClient: BlockingClient
    @Mock private lateinit var prefs: Preferences
    @Mock private lateinit var messageRepo: MessageRepository
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var updateBadge: UpdateBadge
    @Mock private lateinit var shortcutManager: ShortcutManager
    @Mock private lateinit var smsMessage: SmsMessage
    @Mock private lateinit var message: Message
    @Mock private lateinit var conversation: Conversation
    @Mock private lateinit var dropPref: com.silentpulse.messenger.util.Preferences.Preference<Boolean>
    @Mock private lateinit var blockingManagerPref: com.silentpulse.messenger.util.Preferences.Preference<Int>

    private lateinit var receiveSms: ReceiveSms

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        
        receiveSms = ReceiveSms(
            conversationRepo,
            blockingClient,
            prefs,
            messageRepo,
            notificationManager,
            updateBadge,
            shortcutManager
        )

        // Default mock setup
        `when`(prefs.drop).thenReturn(dropPref)
        `when`(prefs.blockingManager).thenReturn(blockingManagerPref)
        `when`(dropPref.get()).thenReturn(false)
        `when`(blockingManagerPref.get()).thenReturn(0)
        `when`(smsMessage.displayOriginatingAddress).thenReturn("+1234567890")
        `when`(smsMessage.displayMessageBody).thenReturn("Test message")
        `when`(smsMessage.timestampMillis).thenReturn(1234567890L)
        `when`(blockingClient.shouldBlock(anyString())).thenReturn(Single.just(BlockingClient.Action.Allow))
        `when`(message.threadId).thenReturn(1L)
        `when`(message.id).thenReturn(100L)
        `when`(conversation.id).thenReturn(1L)
        `when`(conversation.blocked).thenReturn(false)
        `when`(conversation.archived).thenReturn(false)
        `when`(messageRepo.insertReceivedSms(anyInt(), anyString(), anyString(), anyLong()))
            .thenReturn(message)
        `when`(conversationRepo.getOrCreateConversation(anyLong())).thenReturn(conversation)
        `when`(updateBadge.buildObservable(any())).thenReturn(io.reactivex.Flowable.just(Unit))
    }

    @Test
    fun `happy path - valid SMS with address and body stores message`() {
        // Arrange
        val params = ReceiveSms.Params(subId = 1, messages = arrayOf(smsMessage))

        // Act
        val testObserver = receiveSms.buildObservable(params).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).insertReceivedSms(
            eq(1),
            eq("+1234567890"),
            eq("Test message"),
            eq(1234567890L)
        )
        verify(conversationRepo).updateConversations(1L)
        verify(notificationManager).update(1L)
        verify(shortcutManager).updateShortcuts()
    }

    @Test
    fun `empty body should still store message`() {
        // Arrange
        `when`(smsMessage.displayMessageBody).thenReturn("")
        val params = ReceiveSms.Params(subId = 1, messages = arrayOf(smsMessage))

        // Act
        val testObserver = receiveSms.buildObservable(params).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).insertReceivedSms(
            eq(1),
            eq("+1234567890"),
            eq(""),
            eq(1234567890L)
        )
    }

    @Test
    fun `blocked sender with drop enabled should not store message`() {
        // Arrange
        `when`(blockingClient.shouldBlock(anyString()))
            .thenReturn(Single.just(BlockingClient.Action.Block(0, "Blocked")))
        `when`(dropPref.get()).thenReturn(true)
        val params = ReceiveSms.Params(subId = 1, messages = arrayOf(smsMessage))

        // Act
        val testObserver = receiveSms.buildObservable(params).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertNoValues()
        
        verify(messageRepo, never()).insertReceivedSms(anyInt(), anyString(), anyString(), anyLong())
        verify(notificationManager, never()).update(anyLong())
    }

    @Test
    fun `blocked sender with drop disabled should store and mark as blocked`() {
        // Arrange
        `when`(blockingClient.shouldBlock(anyString()))
            .thenReturn(Single.just(BlockingClient.Action.Block(0, "Blocked")))
        `when`(dropPref.get()).thenReturn(false)
        val params = ReceiveSms.Params(subId = 1, messages = arrayOf(smsMessage))

        // Act
        val testObserver = receiveSms.buildObservable(params).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).insertReceivedSms(anyInt(), anyString(), anyString(), anyLong())
        verify(messageRepo).markRead(1L)
        verify(conversationRepo).markBlocked(eq(listOf(1L)), anyInt(), eq("Blocked"))
    }

    @Test
    fun `unblock action should mark conversation as unblocked`() {
        // Arrange
        `when`(blockingClient.shouldBlock(anyString()))
            .thenReturn(Single.just(BlockingClient.Action.Unblock))
        val params = ReceiveSms.Params(subId = 1, messages = arrayOf(smsMessage))

        // Act
        val testObserver = receiveSms.buildObservable(params).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(conversationRepo).markUnblocked(1L)
    }

    @Test
    fun `blocked conversation should not trigger notification`() {
        // Arrange
        `when`(conversation.blocked).thenReturn(true)
        val params = ReceiveSms.Params(subId = 1, messages = arrayOf(smsMessage))

        // Act
        val testObserver = receiveSms.buildObservable(params).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).insertReceivedSms(anyInt(), anyString(), anyString(), anyLong())
        verify(notificationManager, never()).update(anyLong())
    }

    @Test
    fun `archived conversation should be unarchived on new message`() {
        // Arrange
        `when`(conversation.archived).thenReturn(true)
        val params = ReceiveSms.Params(subId = 1, messages = arrayOf(smsMessage))

        // Act
        val testObserver = receiveSms.buildObservable(params).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(conversationRepo).markUnarchived(1L)
    }

    @Test
    fun `multi-part SMS should concatenate message bodies`() {
        // Arrange
        val smsMessage2 = org.mockito.Mockito.mock(SmsMessage::class.java)
        `when`(smsMessage.displayMessageBody).thenReturn("Part 1")
        `when`(smsMessage2.displayOriginatingAddress).thenReturn("+1234567890")
        `when`(smsMessage2.displayMessageBody).thenReturn(" Part 2")
        `when`(smsMessage2.timestampMillis).thenReturn(1234567890L)
        
        val params = ReceiveSms.Params(subId = 1, messages = arrayOf(smsMessage, smsMessage2))

        // Act
        val testObserver = receiveSms.buildObservable(params).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).insertReceivedSms(
            eq(1),
            eq("+1234567890"),
            eq("Part 1 Part 2"),
            eq(1234567890L)
        )
    }

    @Test
    fun `empty messages array should not process anything`() {
        // Arrange
        val params = ReceiveSms.Params(subId = 1, messages = arrayOf())

        // Act
        val testObserver = receiveSms.buildObservable(params).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertNoValues()
        
        verify(messageRepo, never()).insertReceivedSms(anyInt(), anyString(), anyString(), anyLong())
    }

    @Test
    fun `Params data class equality test`() {
        // Arrange
        val messages1 = arrayOf(smsMessage)
        val messages2 = arrayOf(smsMessage)
        val params1 = ReceiveSms.Params(1, messages1)
        val params2 = ReceiveSms.Params(1, messages1)
        val params3 = ReceiveSms.Params(2, messages1)

        // Assert
        assertEquals(params1.subId, params2.subId)
        assertEquals(params1.messages, params2.messages)
        assertNotEquals(params1.subId, params3.subId)
    }

    @Test
    fun `null message body part should be filtered out`() {
        // Arrange
        val smsMessage2 = org.mockito.Mockito.mock(SmsMessage::class.java)
        `when`(smsMessage.displayMessageBody).thenReturn("Part 1")
        `when`(smsMessage2.displayOriginatingAddress).thenReturn("+1234567890")
        `when`(smsMessage2.displayMessageBody).thenReturn(null)
        `when`(smsMessage2.timestampMillis).thenReturn(1234567890L)
        
        val params = ReceiveSms.Params(subId = 1, messages = arrayOf(smsMessage, smsMessage2))

        // Act
        val testObserver = receiveSms.buildObservable(params).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).insertReceivedSms(
            eq(1),
            eq("+1234567890"),
            eq("Part 1"),
            eq(1234567890L)
        )
    }
}
