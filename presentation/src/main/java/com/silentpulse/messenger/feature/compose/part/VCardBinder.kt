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
package com.silentpulse.messenger.feature.compose.part

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkViewHolder
import com.silentpulse.messenger.common.util.Colors
import com.silentpulse.messenger.common.util.extensions.getDisplayName
import com.silentpulse.messenger.common.util.extensions.resolveThemeColor
import com.silentpulse.messenger.common.util.extensions.setBackgroundTint
import com.silentpulse.messenger.common.util.extensions.setTint
import com.silentpulse.messenger.extensions.isVCard
import com.silentpulse.messenger.extensions.mapNotNull
import com.silentpulse.messenger.feature.compose.BubbleUtils
import com.silentpulse.messenger.model.Message
import com.silentpulse.messenger.model.MmsPart
import ezvcard.Ezvcard
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import com.silentpulse.messenger.common.widget.QkTextView

class VCardBinder @Inject constructor(colors: Colors, private val context: Context) : PartBinder() {

    override val partLayout = R.layout.mms_vcard_list_item
    override var theme = colors.theme()

    override fun canBindPart(part: MmsPart) = part.isVCard()

    override fun bindPart(
        holder: QkViewHolder,
        part: MmsPart,
        message: Message,
        canGroupWithPrevious: Boolean,
        canGroupWithNext: Boolean
    ) {
        val vCardBackground = holder.itemView.findViewById<ConstraintLayout>(R.id.vCardBackground)
        val vCardAvatar = holder.itemView.findViewById<ImageView>(R.id.vCardAvatar)
        val name = holder.itemView.findViewById<QkTextView>(R.id.name)
        val label = holder.itemView.findViewById<QkTextView>(R.id.label)

        BubbleUtils.getBubble(false, canGroupWithPrevious, canGroupWithNext, message.isMe())
                .let(vCardBackground::setBackgroundResource)

        holder.itemView.setOnClickListener { clicks.onNext(part.id) }

        Observable.just(part.getUri())
                .map(context.contentResolver::openInputStream)
                .mapNotNull { inputStream -> inputStream.use { Ezvcard.parse(it).first() } }
                .map { vcard -> vcard.getDisplayName() ?: "" }
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { displayName ->
                    name?.text = displayName
                    name.isVisible = displayName.isNotEmpty()
                }

        val params = vCardBackground.layoutParams as FrameLayout.LayoutParams
        if (!message.isMe()) {
            vCardBackground.layoutParams = params.apply { gravity = Gravity.START }
            vCardBackground.setBackgroundTint(theme.theme)
            vCardAvatar.setTint(theme.textPrimary)
            name.setTextColor(theme.textPrimary)
            label.setTextColor(theme.textTertiary)
        } else {
            vCardBackground.layoutParams = params.apply { gravity = Gravity.END }
            vCardBackground.setBackgroundTint(holder.itemView.context.resolveThemeColor(R.attr.bubbleColor))
            vCardAvatar.setTint(holder.itemView.context.resolveThemeColor(android.R.attr.textColorSecondary))
            name.setTextColor(holder.itemView.context.resolveThemeColor(android.R.attr.textColorPrimary))
            label.setTextColor(holder.itemView.context.resolveThemeColor(android.R.attr.textColorTertiary))
        }
    }

}
