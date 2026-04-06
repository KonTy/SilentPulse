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
package com.silentpulse.messenger.feature.settings.about

import android.view.View
import com.jakewharton.rxbinding2.view.clicks
import com.silentpulse.messenger.BuildConfig
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkController
import com.silentpulse.messenger.common.widget.PreferenceView
import com.silentpulse.messenger.common.widget.QkTextView
import com.silentpulse.messenger.injection.appComponent
import io.reactivex.Observable
import javax.inject.Inject
import android.widget.LinearLayout

class AboutController : QkController<AboutView, Unit, AboutPresenter>(), AboutView {

    // View references (migrated from synthetics)
    private val preferences: LinearLayout get() = rootView.findViewById(R.id.preferences)
    private val version: PreferenceView get() = rootView.findViewById(R.id.version)

    @Inject override lateinit var presenter: AboutPresenter

    init {
        appComponent.inject(this)
        layoutRes = R.layout.about_controller
    }

    override fun onViewCreated() {
        version.summary = BuildConfig.VERSION_NAME
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.about_title)
        showBackButton(true)
    }

    override fun preferenceClicks(): Observable<PreferenceView> = (0 until preferences.childCount)
            .map { index -> preferences.getChildAt(index) }
            .mapNotNull { view -> view as? PreferenceView }
            .map { preference -> preference.clicks().map { preference } }
            .let { preferences -> Observable.merge(preferences) }

    override fun render(state: Unit) {
        // No special rendering required
    }

}
