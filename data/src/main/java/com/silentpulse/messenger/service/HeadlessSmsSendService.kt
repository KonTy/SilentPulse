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
package com.silentpulse.messenger.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.telephony.TelephonyManager
import com.silentpulse.messenger.interactor.SendMessage
import com.silentpulse.messenger.repository.ConversationRepository
import dagger.android.AndroidInjection
import java.util.concurrent.Executors
import javax.inject.Inject

class HeadlessSmsSendService : Service() {

    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var sendMessage: SendMessage

    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        executor.execute {
            try {
                handleIntent(intent)
            } finally {
                stopSelf(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) return

        AndroidInjection.inject(this)
        intent.extras?.getString(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { body ->
            val intentUri = intent.data
            val recipients = intentUri?.let(::getRecipients)?.split(";") ?: return@let
            val threadId = conversationRepo.getOrCreateConversation(recipients)?.id ?: 0L
            sendMessage.execute(SendMessage.Params(-1, threadId, recipients, body))
        }
    }

    private fun getRecipients(uri: Uri): String {
        val base = uri.schemeSpecificPart
        val position = base.indexOf('?')
        return if (position == -1) base else base.substring(0, position)
    }

}