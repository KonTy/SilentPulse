package com.silentpulse.messenger.feature.conversationinfo

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.jakewharton.rxbinding2.view.clicks
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkAdapter
import com.silentpulse.messenger.common.base.QkViewHolder
import com.silentpulse.messenger.common.util.Colors
import com.silentpulse.messenger.common.util.extensions.setTint
import com.silentpulse.messenger.common.util.extensions.setVisible
import com.silentpulse.messenger.common.widget.AvatarView
import com.silentpulse.messenger.common.widget.SquareImageView
import com.silentpulse.messenger.common.widget.PreferenceView
import com.silentpulse.messenger.common.widget.QkTextView
import com.silentpulse.messenger.extensions.isVideo
import com.silentpulse.messenger.feature.conversationinfo.ConversationInfoItem.*
import com.silentpulse.messenger.util.GlideApp
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class ConversationInfoAdapter @Inject constructor(
    private val context: Context,
    private val colors: Colors
) : QkAdapter<ConversationInfoItem>() {

    val recipientClicks: Subject<Long> = PublishSubject.create()
    val recipientLongClicks: Subject<Long> = PublishSubject.create()
    val themeClicks: Subject<Long> = PublishSubject.create()
    val nameClicks: Subject<Unit> = PublishSubject.create()
    val notificationClicks: Subject<Unit> = PublishSubject.create()
    val archiveClicks: Subject<Unit> = PublishSubject.create()
    val blockClicks: Subject<Unit> = PublishSubject.create()
    val deleteClicks: Subject<Unit> = PublishSubject.create()
    val mediaClicks: Subject<Long> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> QkViewHolder(inflater.inflate(R.layout.conversation_recipient_list_item, parent, false)).apply {
                itemView.setOnClickListener {
                    val item = getItem(bindingAdapterPosition) as? ConversationInfoRecipient
                    item?.value?.id?.run(recipientClicks::onNext)
                }

                itemView.setOnLongClickListener {
                    val item = getItem(bindingAdapterPosition) as? ConversationInfoRecipient
                    item?.value?.id?.run(recipientLongClicks::onNext)
                    true
                }

                val themeView = itemView.findViewById<android.view.View>(R.id.theme)
                themeView.setOnClickListener {
                    val item = getItem(bindingAdapterPosition) as? ConversationInfoRecipient
                    item?.value?.id?.run(themeClicks::onNext)
                }
            }

            1 -> QkViewHolder(inflater.inflate(R.layout.conversation_info_settings, parent, false)).apply {
                val groupName = itemView.findViewById<PreferenceView>(R.id.groupName)
                val notifications = itemView.findViewById<PreferenceView>(R.id.notifications)
                val archive = itemView.findViewById<PreferenceView>(R.id.archive)
                val block = itemView.findViewById<PreferenceView>(R.id.block)
                val delete = itemView.findViewById<PreferenceView>(R.id.delete)
                groupName.clicks().subscribe(nameClicks)
                notifications.clicks().subscribe(notificationClicks)
                archive.clicks().subscribe(archiveClicks)
                block.clicks().subscribe(blockClicks)
                delete.clicks().subscribe(deleteClicks)
            }

            2 -> QkViewHolder(inflater.inflate(R.layout.conversation_media_list_item, parent, false)).apply {
                itemView.setOnClickListener {
                    val item = getItem(bindingAdapterPosition) as? ConversationInfoMedia
                    item?.value?.id?.run(mediaClicks::onNext)
                }
            }

            else -> throw IllegalStateException()
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ConversationInfoRecipient -> {
                val recipient = item.value
                val avatar = holder.itemView.findViewById<AvatarView>(R.id.avatar)
                val name = holder.itemView.findViewById<QkTextView>(R.id.name)
                val address = holder.itemView.findViewById<QkTextView>(R.id.address)
                val add = holder.itemView.findViewById<android.widget.ImageView>(R.id.add)
                val themeView = holder.itemView.findViewById<android.widget.ImageView>(R.id.theme)

                avatar.setRecipient(recipient)

                name.text = recipient.contact?.name ?: recipient.address

                address.text = recipient.address
                address.setVisible(recipient.contact != null)

                add.setVisible(recipient.contact == null)

                val theme = colors.theme(recipient)
                themeView.setTint(theme.theme)
            }

            is ConversationInfoSettings -> {
                val groupName = holder.itemView.findViewById<PreferenceView>(R.id.groupName)
                val notifications = holder.itemView.findViewById<PreferenceView>(R.id.notifications)
                val archive = holder.itemView.findViewById<PreferenceView>(R.id.archive)
                val block = holder.itemView.findViewById<PreferenceView>(R.id.block)

                groupName.isVisible = item.recipients.size > 1
                groupName.summary = item.name

                notifications.isEnabled = !item.blocked

                archive.isEnabled = !item.blocked
                archive.title = context.getString(when (item.archived) {
                    true -> R.string.info_unarchive
                    false -> R.string.info_archive
                })

                block.title = context.getString(when (item.blocked) {
                    true -> R.string.info_unblock
                    false -> R.string.info_block
                })
            }

            is ConversationInfoMedia -> {
                val part = item.value
                val thumbnail = holder.itemView.findViewById<SquareImageView>(R.id.thumbnail)
                val video = holder.itemView.findViewById<android.widget.ImageView>(R.id.video)

                GlideApp.with(context)
                        .load(part.getUri())
                        .fitCenter()
                        .into(thumbnail)

                video.isVisible = part.isVideo()
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (data[position]) {
            is ConversationInfoRecipient -> 0
            is ConversationInfoSettings -> 1
            is ConversationInfoMedia -> 2
        }
    }

    override fun areItemsTheSame(old: ConversationInfoItem, new: ConversationInfoItem): Boolean {
        return when {
            old is ConversationInfoRecipient && new is ConversationInfoRecipient -> {
               old.value.id == new.value.id
            }

            old is ConversationInfoSettings && new is ConversationInfoSettings -> {
                true
            }

            old is ConversationInfoMedia && new is ConversationInfoMedia -> {
                old.value.id == new.value.id
            }

            else -> false
        }
    }

}
