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
package com.silentpulse.messenger.feature.blocking.numbers

import android.view.LayoutInflater
import android.view.ViewGroup
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkRealmAdapter
import com.silentpulse.messenger.common.base.QkViewHolder
import com.silentpulse.messenger.common.widget.QkTextView
import com.silentpulse.messenger.model.BlockedNumber
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject

class BlockedNumbersAdapter : QkRealmAdapter<BlockedNumber>() {

    val unblockAddress: Subject<Long> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.blocked_number_list_item, parent, false)
        return QkViewHolder(view).apply {
            val unblock = itemView.findViewById<android.widget.ImageView>(R.id.unblock)
            unblock.setOnClickListener {
                val number = getItem(bindingAdapterPosition) ?: return@setOnClickListener
                unblockAddress.onNext(number.id)
            }
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        val item = getItem(position)!!

        val number = holder.itemView.findViewById<QkTextView>(R.id.number)
        number.text = item.address
    }

}
