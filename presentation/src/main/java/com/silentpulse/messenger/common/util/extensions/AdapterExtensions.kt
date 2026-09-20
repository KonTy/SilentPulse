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
package com.silentpulse.messenger.common.util.extensions

import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView

fun RecyclerView.Adapter<*>.autoScrollToStart(
    recyclerView: RecyclerView,
    shouldScrollToEnd: () -> Boolean = { false }
) {
    var previousItemCount = itemCount
    var scrollToEndPending = false

    fun scrollToEnd(layoutManager: LinearLayoutManager) {
        if (itemCount == 0) return

        recyclerView.scrollToPosition(itemCount - 1)
        if (scrollToEndPending) return
        scrollToEndPending = true
        recyclerView.doOnNextLayout {
            scrollToEndPending = false
            val lastView = layoutManager.findViewByPosition(itemCount - 1) ?: return@doOnNextLayout
            val orientation = OrientationHelper.createVerticalHelper(layoutManager)
            val distance = orientation.getDecoratedEnd(lastView) - orientation.endAfterPadding
            // scrollToPosition only makes a row visible; a tall SMS can still be clipped below it.
            if (distance > 0) recyclerView.scrollBy(0, distance)
        }
    }

    registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            val oldItemCount = previousItemCount
            previousItemCount = getItemCount()
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return

            // Realm delivers full refreshes, not just individual insertion callbacks.
            if (layoutManager.stackFromEnd && getItemCount() > oldItemCount &&
                    (oldItemCount == 0 || scrollToEndPending ||
                            layoutManager.findLastVisibleItemPosition() >= oldItemCount - 1 ||
                            shouldScrollToEnd())) {
                scrollToEnd(layoutManager)
            }
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            previousItemCount = getItemCount()
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return

            if (layoutManager.stackFromEnd) {
                if (positionStart > 0) {
                    notifyItemChanged(positionStart - 1)
                }

                val lastPosition = layoutManager.findLastVisibleItemPosition()
                if (positionStart + itemCount == getItemCount() &&
                        (positionStart == 0 || scrollToEndPending ||
                                lastPosition >= positionStart - 1 || shouldScrollToEnd())) {
                    scrollToEnd(layoutManager)
                }
            } else {
                val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
                if (firstVisiblePosition == 0) {
                    recyclerView.scrollToPosition(positionStart)
                }
            }
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            previousItemCount = getItemCount()
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return

            if (!layoutManager.stackFromEnd) {
                onItemRangeInserted(positionStart, itemCount)
            }
        }
    })
}