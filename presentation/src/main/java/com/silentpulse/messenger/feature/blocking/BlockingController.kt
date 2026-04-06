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
package com.silentpulse.messenger.feature.blocking

import android.view.View
import com.bluelinelabs.conductor.RouterTransaction
import com.jakewharton.rxbinding2.view.clicks
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.QkChangeHandler
import com.silentpulse.messenger.common.base.QkController
import com.silentpulse.messenger.common.util.Colors
import com.silentpulse.messenger.common.util.extensions.animateLayoutChanges
import com.silentpulse.messenger.feature.blocking.manager.BlockingManagerController
import com.silentpulse.messenger.feature.blocking.messages.BlockedMessagesController
import com.silentpulse.messenger.feature.blocking.numbers.BlockedNumbersController
import com.silentpulse.messenger.injection.appComponent
import javax.inject.Inject
import android.widget.LinearLayout
import com.silentpulse.messenger.common.widget.PreferenceView

class BlockingController : QkController<BlockingView, BlockingState, BlockingPresenter>(), BlockingView {

    // View references (migrated from synthetics)
    private val blockedMessages: PreferenceView get() = rootView.findViewById(R.id.blockedMessages)
    private val blockedNumbers: PreferenceView get() = rootView.findViewById(R.id.blockedNumbers)
    private val blockingManager: PreferenceView get() = rootView.findViewById(R.id.blockingManager)
    private val drop: PreferenceView get() = rootView.findViewById(R.id.drop)
    private val parent: LinearLayout get() = rootView.findViewById(R.id.parent)


    override val blockingManagerIntent by lazy { blockingManager.clicks() }
    override val blockedNumbersIntent by lazy { blockedNumbers.clicks() }
    override val blockedMessagesIntent by lazy { blockedMessages.clicks() }
    override val dropClickedIntent by lazy { drop.clicks() }

    @Inject lateinit var colors: Colors
    @Inject override lateinit var presenter: BlockingPresenter

    init {
        appComponent.inject(this)
        layoutRes = R.layout.blocking_controller
    }

    override fun onViewCreated() {
        super.onViewCreated()
        parent.postDelayed({ parent?.animateLayoutChanges = true }, 100)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.blocking_title)
        showBackButton(true)
    }

    override fun render(state: BlockingState) {
        blockingManager.summary = state.blockingManager
        drop.checkbox.isChecked = state.dropEnabled
        blockedMessages.isEnabled = !state.dropEnabled
    }

    override fun openBlockedNumbers() {
        router.pushController(RouterTransaction.with(BlockedNumbersController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun openBlockedMessages() {
        router.pushController(RouterTransaction.with(BlockedMessagesController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun openBlockingManager() {
        router.pushController(RouterTransaction.with(BlockingManagerController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

}
