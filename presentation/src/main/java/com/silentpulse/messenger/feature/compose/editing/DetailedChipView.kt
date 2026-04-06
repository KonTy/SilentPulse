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
package com.silentpulse.messenger.feature.compose.editing

import android.content.Context
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.RelativeLayout
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.util.Colors
import com.silentpulse.messenger.common.util.extensions.setBackgroundTint
import com.silentpulse.messenger.common.util.extensions.setTint
import com.silentpulse.messenger.injection.appComponent
import com.silentpulse.messenger.model.Recipient
import javax.inject.Inject
import androidx.constraintlayout.widget.ConstraintLayout
import com.silentpulse.messenger.common.widget.AvatarView
import com.silentpulse.messenger.common.widget.QkTextView

class DetailedChipView(context: Context) : RelativeLayout(context) {

    // View references (migrated from synthetics)
    private val avatar: AvatarView get() = findViewById(R.id.avatar)
    private val card: ConstraintLayout get() = findViewById(R.id.card)
    private val delete: android.widget.ImageView get() = findViewById(R.id.delete)
    private val info: QkTextView get() = findViewById(R.id.info)
    private val name: QkTextView get() = findViewById(R.id.name)


    @Inject lateinit var colors: Colors

    init {
        View.inflate(context, R.layout.contact_chip_detailed, this)
        appComponent.inject(this)

        setOnClickListener { hide() }

        visibility = View.GONE

        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setRecipient(recipient: Recipient) {
        avatar.setRecipient(recipient)
        name.text = recipient.contact?.name?.takeIf { it.isNotBlank() } ?: recipient.address
        info.text = recipient.address

        colors.theme(recipient).let { theme ->
            card.setBackgroundTint(theme.theme)
            name.setTextColor(theme.textPrimary)
            info.setTextColor(theme.textTertiary)
            delete.setTint(theme.textPrimary)
        }
    }

    fun show() {
        startAnimation(AlphaAnimation(0f, 1f).apply { duration = 200 })

        visibility = View.VISIBLE
        requestFocus()
        isClickable = true
    }

    fun hide() {
        startAnimation(AlphaAnimation(1f, 0f).apply { duration = 200 })

        visibility = View.GONE
        clearFocus()
        isClickable = false
    }

    fun setOnDeleteListener(listener: (View) -> Unit) {
        delete.setOnClickListener(listener)
    }

}
