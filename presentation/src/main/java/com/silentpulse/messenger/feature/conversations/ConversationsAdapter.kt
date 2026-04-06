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
package com.silentpulse.messenger.feature.conversations

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.Navigator
import com.silentpulse.messenger.common.base.QkRealmAdapter
import com.silentpulse.messenger.common.base.QkViewHolder
import com.silentpulse.messenger.common.util.Colors
import com.silentpulse.messenger.common.util.DateFormatter
import com.silentpulse.messenger.common.util.extensions.resolveThemeColor
import com.silentpulse.messenger.common.util.extensions.setTint
import com.silentpulse.messenger.common.widget.GroupAvatarView
import com.silentpulse.messenger.common.widget.QkTextView
import com.silentpulse.messenger.model.Conversation
import android.widget.ImageView
import com.silentpulse.messenger.util.PhoneNumberUtils
import javax.inject.Inject

class ConversationsAdapter @Inject constructor(
    private val colors: Colors,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val navigator: Navigator,
    private val phoneNumberUtils: PhoneNumberUtils
) : QkRealmAdapter<Conversation>() {

    init {
        // This is how we access the threadId for the swipe actions
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = layoutInflater.inflate(R.layout.conversation_list_item, parent, false)

        if (viewType == 1) {
            val textColorPrimary = parent.context.resolveThemeColor(android.R.attr.textColorPrimary)

            val title = view.findViewById<QkTextView>(R.id.title)
            val snippet = view.findViewById<QkTextView>(R.id.snippet)
            val unread = view.findViewById<ImageView>(R.id.unread)
            val date = view.findViewById<QkTextView>(R.id.date)

            title.setTypeface(title.typeface, Typeface.BOLD)

            snippet.setTypeface(snippet.typeface, Typeface.BOLD)
            snippet.setTextColor(textColorPrimary)
            snippet.maxLines = 5

            unread.isVisible = true

            date.setTypeface(date.typeface, Typeface.BOLD)
            date.setTextColor(textColorPrimary)
        }

        return QkViewHolder(view).apply {
            view.setOnClickListener {
                val conversation = getItem(bindingAdapterPosition) ?: return@setOnClickListener
                when (toggleSelection(conversation.id, false)) {
                    true -> view.isActivated = isSelected(conversation.id)
                    false -> navigator.showConversation(conversation.id)
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

        // If the last message wasn't incoming, then the colour doesn't really matter anyway
        val lastMessage = conversation.lastMessage
        val recipient = when {
            conversation.recipients.size == 1 || lastMessage == null -> conversation.recipients.firstOrNull()
            else -> conversation.recipients.find { recipient ->
                phoneNumberUtils.compare(recipient.address, lastMessage.address)
            }
        }
        val theme = colors.theme(recipient).theme

        holder.itemView.isActivated = isSelected(conversation.id)

        val avatars = holder.itemView.findViewById<GroupAvatarView>(R.id.avatars)
        val title = holder.itemView.findViewById<QkTextView>(R.id.title)
        val date = holder.itemView.findViewById<QkTextView>(R.id.date)
        val snippet = holder.itemView.findViewById<QkTextView>(R.id.snippet)
        val pinned = holder.itemView.findViewById<ImageView>(R.id.pinned)
        val unread = holder.itemView.findViewById<ImageView>(R.id.unread)

        avatars.recipients = conversation.recipients
        title.collapseEnabled = conversation.recipients.size > 1
        title.text = buildSpannedString {
            append(conversation.getTitle())
            if (conversation.draft.isNotEmpty()) {
                color(theme) { append(" " + context.getString(R.string.main_draft)) }
            }
        }
        date.text = conversation.date.takeIf { it > 0 }?.let(dateFormatter::getConversationTimestamp)
        snippet.text = when {
            conversation.draft.isNotEmpty() -> conversation.draft
            conversation.me -> context.getString(R.string.main_sender_you, conversation.snippet)
            else -> conversation.snippet
        }
        pinned.isVisible = conversation.pinned
        unread.setTint(theme)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position)?.id ?: -1
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position)?.unread == false) 0 else 1
    }
}
