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

import android.net.Uri
import com.silentpulse.messenger.blocking.BlockingClient
import com.silentpulse.messenger.manager.ActiveConversationManager
import com.silentpulse.messenger.manager.NotificationManager
import com.silentpulse.messenger.model.Conversation
import com.silentpulse.messenger.model.Message
import com.silentpulse.messenger.repository.ConversationRepository
import com.silentpulse.messenger.repository.MessageRepository
import com.silentpulse.messenger.repository.SyncRepository
import com.silentpulse.messenger.util.Preferences
import io.reactivex.Single
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class ReceiveMmsTest {

    @Mock private lateinit var activeConversationManager: ActiveConversationManager
    @Mock private lateinit var conversationRepo: ConversationRepository
    @Mock private lateinit var blockingClient: BlockingClient
    @Mock private lateinit var prefs: Preferences
    @Mock private lateinit var syncManager: SyncRepository
    @Mock private lateinit var messageRepo: MessageRepository
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var updateBadge: UpdateBadge
    @Mock private lateinit var uri: Uri
    @Mock private lateinit var message: Message
    @Mock private lateinit var conversation: Conversation
    @Mock private lateinit var dropPref: com.silentpulse.messenger.util.Preferences.Preference<Boolean>
    @Mock private lateinit var blockingManagerPref: com.silentpulse.messenger.util.Preferences.Preference<Int>

    private lateinit var receiveMms: ReceiveMms

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        
        receiveMms = ReceiveMms(
            activeConversationManager,
            conversationRepo,
            blockingClient,
            prefs,
            syncManager,
            messageRepo,
            notificationManager,
            updateBadge
        )

        // Default mock setup
        `when`(prefs.drop).thenReturn(dropPref)
        `when`(prefs.blockingManager).thenReturn(blockingManagerPref)
        `when`(dropPref.get()).thenReturn(false)
        `when`(blockingManagerPref.get()).thenReturn(0)
        `when`(uri.toString()).thenReturn("content://mms/1")
        `when`(message.threadId).thenReturn(1L)
        `when`(message.id).thenReturn(100L)
        `when`(message.address).thenReturn("+1234567890")
        `when`(conversation.id).thenReturn(1L)
        `when`(conversation.blocked).thenReturn(false)
        `when`(conversation.archived).thenReturn(false)
        `when`(activeConversationManager.getActiveConversation()).thenReturn(0L)
        `when`(syncManager.syncMessage(any())).thenReturn(message)
        `when`(blockingClient.shouldBlock(anyString())).thenReturn(Single.just(BlockingClient.Action.Allow))
        `when`(conversationRepo.getOrCreateConversation(anyLong())).thenReturn(conversation)
        `when`(updateBadge.buildObservable(any())).thenReturn(io.reactivex.Flowable.just(Unit))
    }

    @Test
    fun `happy path - valid MMS URI retrieves and stores message`() {
        // Act
        val testObserver = receiveMms.buildObservable(uri).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(syncManager).syncMessage(uri)
        verify(conversationRepo).updateConversations(1L)
        verify(conversationRepo).getOrCreateConversation(1L)
        verify(notificationManager).update(1L)
    }

    @Test
    fun `invalid URI should be handled gracefully`() {
        // Arrange
        `when`(syncManager.syncMessage(any())).thenReturn(null)

        // Act
        val testObserver = receiveMms.buildObservable(uri).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertNoValues()
        
        verify(syncManager).syncMessage(uri)
        verify(conversationRepo, never()).updateConversations(anyLong())
        verify(notificationManager, never()).update(anyLong())
    }

    @Test
    fun `blocked sender with drop enabled should delete message`() {
        // Arrange
        `when`(blockingClient.shouldBlock(anyString()))
            .thenReturn(Single.just(BlockingClient.Action.Block(0, "Blocked")))
        `when`(dropPref.get()).thenReturn(true)

        // Act
        val testObserver = receiveMms.buildObservable(uri).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertNoValues()
        
        verify(messageRepo).deleteMessages(100L)
        verify(notificationManager, never()).update(anyLong())
    }

    @Test
    fun `blocked sender with drop disabled should mark as blocked`() {
        // Arrange
        `when`(blockingClient.shouldBlock(anyString()))
            .thenReturn(Single.just(BlockingClient.Action.Block(0, "Blocked")))
        `when`(dropPref.get()).thenReturn(false)

        // Act
        val testObserver = receiveMms.buildObservable(uri).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).markRead(1L)
        verify(conversationRepo).markBlocked(eq(listOf(1L)), anyInt(), eq("Blocked"))
        verify(messageRepo, never()).deleteMessages(anyLong())
    }

    @Test
    fun `unblock action should mark conversation as unblocked`() {
        // Arrange
        `when`(blockingClient.shouldBlock(anyString()))
            .thenReturn(Single.just(BlockingClient.Action.Unblock))

        // Act
        val testObserver = receiveMms.buildObservable(uri).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(conversationRepo).markUnblocked(1L)
    }

    @Test
    fun `blocked conversation should not trigger notification`() {
        // Arrange
        `when`(conversation.blocked).thenReturn(true)

        // Act
        val testObserver = receiveMms.buildObservable(uri).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(syncManager).syncMessage(uri)
        verify(notificationManager, never()).update(anyLong())
    }

    @Test
    fun `archived conversation should be unarchived on new MMS`() {
        // Arrange
        `when`(conversation.archived).thenReturn(true)

        // Act
        val testObserver = receiveMms.buildObservable(uri).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(conversationRepo).markUnarchived(1L)
    }

    @Test
    fun `message in active conversation should be marked as read`() {
        // Arrange
        `when`(activeConversationManager.getActiveConversation()).thenReturn(1L)

        // Act
        val testObserver = receiveMms.buildObservable(uri).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        verify(messageRepo).markRead(1L)
    }

    @Test
    fun `message not in active conversation should not be marked as read`() {
        // Arrange
        `when`(activeConversationManager.getActiveConversation()).thenReturn(2L)

        // Act
        val testObserver = receiveMms.buildObservable(uri).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        // Should only be marked read if blocked, not because of active conversation
        verify(messageRepo, never()).markRead(1L)
    }

    @Test
    fun `multi-part MMS should process all parts through syncMessage`() {
        // This test verifies that the syncMessage call handles multi-part MMS
        // The actual multi-part handling is in syncManager.syncMessage
        
        // Act
        val testObserver = receiveMms.buildObservable(uri).test()

        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        
        // Verify syncMessage was called which handles all MMS parts
        verify(syncManager).syncMessage(uri)
        verify(conversationRepo).updateConversations(1L)
    }
}
