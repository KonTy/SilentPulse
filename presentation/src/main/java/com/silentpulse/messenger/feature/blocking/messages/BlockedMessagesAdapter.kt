/*
 * Copyright (C) 2019 Moez Bhatti <moez.bhatti@gmail.com>
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
package com.silentpulse.messenger.feature.blocking.messages

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkRealmAdapter
import com.silentpulse.messenger.common.base.QkViewHolder
import com.silentpulse.messenger.common.util.DateFormatter
import com.silentpulse.messenger.common.util.extensions.resolveThemeColor
import com.silentpulse.messenger.common.widget.GroupAvatarView
import com.silentpulse.messenger.common.widget.QkTextView
import com.silentpulse.messenger.model.Conversation
import com.silentpulse.messenger.util.Preferences
import io.reactivex.subjects.PublishSubject
import javax.inject.Inject

class BlockedMessagesAdapter @Inject constructor(
    private val context: Context,
    private val dateFormatter: DateFormatter
) : QkRealmAdapter<Conversation>() {

    val clicks: PublishSubject<Long> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.blocked_list_item, parent, false)

        if (viewType == 0) {
            val title = view.findViewById<QkTextView>(R.id.title)
            val date = view.findViewById<QkTextView>(R.id.date)
            title.setTypeface(title.typeface, Typeface.BOLD)
            date.setTypeface(date.typeface, Typeface.BOLD)
            date.setTextColor(view.context.resolveThemeColor(android.R.attr.textColorPrimary))
        }

        return QkViewHolder(view).apply {
            view.setOnClickListener {
                val conversation = getItem(bindingAdapterPosition) ?: return@setOnClickListener
                when (toggleSelection(conversation.id, false)) {
                    true -> view.isActivated = isSelected(conversation.id)
                    false -> clicks.onNext(conversation.id)
                }
            }
            view.setOnLongClickListener {
                val conversation = getItem(bindingAdapterPosition) ?: return@setOnLongClickListener true
                toggleSelection(conversation.id)
                view.isActivated = isSelected(conversation.id)
                true
            }
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        val conversation = getItem(position) ?: return

        val avatars = holder.itemView.findViewById<GroupAvatarView>(R.id.avatars)
        val title = holder.itemView.findViewById<QkTextView>(R.id.title)
        val date = holder.itemView.findViewById<QkTextView>(R.id.date)
        val blocker = holder.itemView.findViewById<QkTextView>(R.id.blocker)
        val reason = holder.itemView.findViewById<QkTextView>(R.id.reason)

        holder.itemView.isActivated = isSelected(conversation.id)

        avatars.recipients = conversation.recipients
        title.collapseEnabled = conversation.recipients.size > 1
        title.text = conversation.getTitle()
        date.text = dateFormatter.getConversationTimestamp(conversation.date)

        blocker.text = when (conversation.blockingClient) {
            Preferences.BLOCKING_MANAGER_CC -> context.getString(R.string.blocking_manager_call_control_title)
            Preferences.BLOCKING_MANAGER_SIA -> context.getString(R.string.blocking_manager_sia_title)
            else -> null
        }

        reason.text = conversation.blockReason
        blocker.isVisible = blocker.text.isNotEmpty()
        reason.isVisible = blocker.text.isNotEmpty()
    }

    override fun getItemViewType(position: Int): Int {
        val conversation = getItem(position)
        return if (conversation?.unread == false) 1 else 0
    }

}
