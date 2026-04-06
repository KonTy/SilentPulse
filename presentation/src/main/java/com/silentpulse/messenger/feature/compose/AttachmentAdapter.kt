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
package com.silentpulse.messenger.feature.compose

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkAdapter
import com.silentpulse.messenger.common.base.QkViewHolder
import com.silentpulse.messenger.common.util.extensions.getDisplayName
import com.silentpulse.messenger.common.widget.BubbleImageView
import com.silentpulse.messenger.common.widget.QkTextView
import com.silentpulse.messenger.extensions.mapNotNull
import com.silentpulse.messenger.model.Attachment
import ezvcard.Ezvcard
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class AttachmentAdapter @Inject constructor(
    private val context: Context
) : QkAdapter<Attachment>() {

    companion object {
        private const val VIEW_TYPE_IMAGE = 0
        private const val VIEW_TYPE_CONTACT = 1
    }

    val attachmentDeleted: Subject<Attachment> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = when (viewType) {
            VIEW_TYPE_IMAGE -> inflater.inflate(R.layout.attachment_image_list_item, parent, false)
                    .apply { findViewById<android.widget.FrameLayout>(R.id.thumbnailBounds).clipToOutline = true }

            VIEW_TYPE_CONTACT -> inflater.inflate(R.layout.attachment_contact_list_item, parent, false)

            else -> null!! // Impossible
        }

        return QkViewHolder(view).apply {
            view.setOnClickListener {
                val attachment = getItem(bindingAdapterPosition)
                attachmentDeleted.onNext(attachment)
            }
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        val attachment = getItem(position)

        when (attachment) {
            is Attachment.Image -> Glide.with(context)
                    .load(attachment.getUri())
                    .into(holder.itemView.findViewById<android.widget.ImageView>(R.id.thumbnail))

            is Attachment.Contact -> Observable.just(attachment.vCard)
                    .mapNotNull { vCard -> Ezvcard.parse(vCard).first() }
                    .map { vcard -> vcard.getDisplayName() ?: "" }
                    .subscribeOn(Schedulers.computation())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe { displayName ->
                        val name = holder.itemView.findViewById<QkTextView>(R.id.name)
                        name?.text = displayName
                        name?.isVisible = displayName.isNotEmpty()
                    }
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Attachment.Image -> VIEW_TYPE_IMAGE
        is Attachment.Contact -> VIEW_TYPE_CONTACT
    }

}