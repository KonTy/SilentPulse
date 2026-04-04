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
class MessageTest {

    private lateinit var smsMessage: Message
    private lateinit var mmsMessage: Message

    @Before
    fun setUp() {
        smsMessage = Message().apply {
            id = 1
            contentId = 101
            type = "sms"
            body = "Test SMS body"
            boxId = Telephony.Sms.MESSAGE_TYPE_INBOX
        }

        mmsMessage = Message().apply {
            id = 2
            contentId = 102
            type = "mms"
            subject = "Test Subject"
            boxId = Telephony.Mms.MESSAGE_BOX_INBOX
        }
    }

    @Test
    fun `isSms should return true for SMS messages`() {
        assertTrue(smsMessage.isSms())
        assertFalse(mmsMessage.isSms())
    }

    @Test
    fun `isMms should return true for MMS messages`() {
        assertTrue(mmsMessage.isMms())
        assertFalse(smsMessage.isMms())
    }

    @Test
    fun `isMe should return false for inbox SMS`() {
        smsMessage.boxId = Telephony.Sms.MESSAGE_TYPE_INBOX
        assertFalse(smsMessage.isMe())
    }

    @Test
    fun `isMe should return true for sent SMS`() {
        smsMessage.boxId = Telephony.Sms.MESSAGE_TYPE_SENT
        assertTrue(smsMessage.isMe())
    }

    @Test
    fun `isMe should return false for inbox MMS`() {
        mmsMessage.boxId = Telephony.Mms.MESSAGE_BOX_INBOX
        assertFalse(mmsMessage.isMe())
    }

    @Test
    fun `isMe should return true for sent MMS`() {
        mmsMessage.boxId = Telephony.Mms.MESSAGE_BOX_SENT
        assertTrue(mmsMessage.isMe())
    }

    @Test
    fun `isOutgoingMessage should return true for outbox SMS`() {
        smsMessage.boxId = Telephony.Sms.MESSAGE_TYPE_OUTBOX
        assertTrue(smsMessage.isOutgoingMessage())
    }

    @Test
    fun `isOutgoingMessage should return true for failed SMS`() {
        smsMessage.boxId = Telephony.Sms.MESSAGE_TYPE_FAILED
        assertTrue(smsMessage.isOutgoingMessage())
    }

    @Test
    fun `isOutgoingMessage should return true for queued SMS`() {
        smsMessage.boxId = Telephony.Sms.MESSAGE_TYPE_QUEUED
        assertTrue(smsMessage.isOutgoingMessage())
    }

    @Test
    fun `isOutgoingMessage should return false for inbox SMS`() {
        smsMessage.boxId = Telephony.Sms.MESSAGE_TYPE_INBOX
        assertFalse(smsMessage.isOutgoingMessage())
    }

    @Test
    fun `getText should return body for SMS`() {
        assertEquals("Test SMS body", smsMessage.getText())
    }

    @Test
    fun `getText should return parts text for MMS`() {
        val part1 = mock(MmsPart::class.java).apply {
            `when`(type).thenReturn("text/plain")
            `when`(text).thenReturn("Part 1")
        }
        val part2 = mock(MmsPart::class.java).apply {
            `when`(type).thenReturn("text/plain")
            `when`(text).thenReturn("Part 2")
        }
        mmsMessage.parts = RealmList(part1, part2)

        assertEquals("Part 1\nPart 2", mmsMessage.getText())
    }

    @Test
    fun `getSummary should return body for SMS`() {
        assertEquals("Test SMS body", smsMessage.getSummary())
    }

    @Test
    fun `getSummary should include subject for MMS`() {
        val part = mock(MmsPart::class.java).apply {
            `when`(type).thenReturn("text/plain")
            `when`(text).thenReturn("MMS body")
            `when`(getSummary()).thenReturn("MMS body")
        }
        mmsMessage.parts = RealmList(part)

        val summary = mmsMessage.getSummary()
        assertTrue(summary.contains("Test Subject"))
        assertTrue(summary.contains("MMS body"))
    }

    @Test
    fun `getCleansedSubject should return empty for useless subjects`() {
        mmsMessage.subject = "no subject"
        assertEquals("", mmsMessage.getCleansedSubject())

        mmsMessage.subject = "NoSubject"
        assertEquals("", mmsMessage.getCleansedSubject())

        mmsMessage.subject = "<not present>"
        assertEquals("", mmsMessage.getCleansedSubject())
    }

    @Test
    fun `getCleansedSubject should return actual subject`() {
        mmsMessage.subject = "Important Subject"
        assertEquals("Important Subject", mmsMessage.getCleansedSubject())
    }

    @Test
    fun `isSending should return true for outbox non-failed message`() {
        smsMessage.boxId = Telephony.Sms.MESSAGE_TYPE_OUTBOX
        assertTrue(smsMessage.isSending())
    }

    @Test
    fun `isSending should return false for failed message`() {
        smsMessage.boxId = Telephony.Sms.MESSAGE_TYPE_FAILED
        assertFalse(smsMessage.isSending())
    }

    @Test
    fun `isDelivered should return true for completed delivery`() {
        smsMessage.deliveryStatus = Telephony.Sms.STATUS_COMPLETE
        assertTrue(smsMessage.isDelivered())
    }

    @Test
    fun `isDelivered should return false for pending delivery`() {
        smsMessage.deliveryStatus = Telephony.Sms.STATUS_PENDING
        assertFalse(smsMessage.isDelivered())
    }

    @Test
    fun `isFailedMessage should return true for failed SMS`() {
        smsMessage.boxId = Telephony.Sms.MESSAGE_TYPE_FAILED
        assertTrue(smsMessage.isFailedMessage())
    }

    @Test
    fun `isFailedMessage should return true for failed MMS box`() {
        mmsMessage.boxId = Telephony.Mms.MESSAGE_BOX_FAILED
        assertTrue(mmsMessage.isFailedMessage())
    }

    @Test
    fun `isFailedMessage should return true for MMS with error type`() {
        mmsMessage.errorType = Telephony.MmsSms.ERR_TYPE_GENERIC_PERMANENT
        assertTrue(mmsMessage.isFailedMessage())
    }

    @Test
    fun `isFailedMessage should return false for successful message`() {
        smsMessage.boxId = Telephony.Sms.MESSAGE_TYPE_SENT
        assertFalse(smsMessage.isFailedMessage())
    }

    @Test
    fun `compareSender should return true for same sender outgoing messages`() {
        val msg1 = Message().apply {
            type = "sms"
            boxId = Telephony.Sms.MESSAGE_TYPE_SENT
            subId = 1
        }
        val msg2 = Message().apply {
            type = "sms"
            boxId = Telephony.Sms.MESSAGE_TYPE_SENT
            subId = 1
        }

        assertTrue(msg1.compareSender(msg2))
    }

    @Test
    fun `compareSender should return false for different subId outgoing messages`() {
        val msg1 = Message().apply {
            type = "sms"
            boxId = Telephony.Sms.MESSAGE_TYPE_SENT
            subId = 1
        }
        val msg2 = Message().apply {
            type = "sms"
            boxId = Telephony.Sms.MESSAGE_TYPE_SENT
            subId = 2
        }

        assertFalse(msg1.compareSender(msg2))
    }

    @Test
    fun `compareSender should return true for same incoming sender`() {
        val msg1 = Message().apply {
            type = "sms"
            boxId = Telephony.Sms.MESSAGE_TYPE_INBOX
            address = "+1234567890"
            subId = 1
        }
        val msg2 = Message().apply {
            type = "sms"
            boxId = Telephony.Sms.MESSAGE_TYPE_INBOX
            address = "+1234567890"
            subId = 1
        }

        assertTrue(msg1.compareSender(msg2))
    }

    @Test
    fun `compareSender should return false for different incoming address`() {
        val msg1 = Message().apply {
            type = "sms"
            boxId = Telephony.Sms.MESSAGE_TYPE_INBOX
            address = "+1234567890"
            subId = 1
        }
        val msg2 = Message().apply {
            type = "sms"
            boxId = Telephony.Sms.MESSAGE_TYPE_INBOX
            address = "+0987654321"
            subId = 1
        }

        assertFalse(msg1.compareSender(msg2))
    }

    @Test
    fun `compareSender should return false for different direction messages`() {
        val msg1 = Message().apply {
            type = "sms"
            boxId = Telephony.Sms.MESSAGE_TYPE_SENT
            subId = 1
        }
        val msg2 = Message().apply {
            type = "sms"
            boxId = Telephony.Sms.MESSAGE_TYPE_INBOX
            address = "+1234567890"
            subId = 1
        }

        assertFalse(msg1.compareSender(msg2))
    }

    @Test
    fun `attachmentType should set and get correctly`() {
        smsMessage.attachmentType = Message.AttachmentType.IMAGE
        assertEquals(Message.AttachmentType.IMAGE, smsMessage.attachmentType)
        assertEquals("IMAGE", smsMessage.attachmentTypeString)
    }

    @Test
    fun `getUri should return SMS content URI`() {
        smsMessage.contentId = 123
        val uri = smsMessage.getUri()
        assertTrue(uri.toString().contains("sms"))
        assertTrue(uri.toString().contains("123"))
    }

    @Test
    fun `getUri should return MMS content URI`() {
        mmsMessage.contentId = 456
        val uri = mmsMessage.getUri()
        assertTrue(uri.toString().contains("mms"))
        assertTrue(uri.toString().contains("456"))
    }
}
