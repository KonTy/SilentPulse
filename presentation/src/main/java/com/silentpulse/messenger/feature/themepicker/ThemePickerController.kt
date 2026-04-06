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
package com.silentpulse.messenger.feature.themepicker

import android.animation.ObjectAnimator
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkController
import com.silentpulse.messenger.common.util.Colors
import com.silentpulse.messenger.common.util.extensions.dpToPx
import com.silentpulse.messenger.common.util.extensions.setBackgroundTint
import com.silentpulse.messenger.common.util.extensions.setVisible
import com.silentpulse.messenger.feature.themepicker.injection.ThemePickerModule
import com.silentpulse.messenger.injection.appComponent
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.Group
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.silentpulse.messenger.common.widget.PagerTitleView
import com.silentpulse.messenger.common.widget.QkEditText
import com.silentpulse.messenger.common.widget.QkTextView
import com.silentpulse.messenger.feature.themepicker.HSVPickerView

class ThemePickerController(
    val recipientId: Long = 0L
) : QkController<ThemePickerView, ThemePickerState, ThemePickerPresenter>(), ThemePickerView {

    // View references (migrated from synthetics)
    private val applyButton: QkTextView get() = rootView.findViewById(R.id.apply)
    private val applyGroup: Group get() = rootView.findViewById(R.id.applyGroup)
    private val clear: ImageView get() = rootView.findViewById(R.id.clear)
    private val contentView: LinearLayout get() = rootView.findViewById(R.id.contentView)
    private val hex: QkEditText get() = rootView.findViewById(R.id.hex)
    private val materialColors: RecyclerView get() = rootView.findViewById(R.id.materialColors)
    private val pager: ViewPager get() = rootView.findViewById(R.id.pager)
    private val picker: HSVPickerView get() = rootView.findViewById(R.id.picker)
    private val tabs: PagerTitleView get() = rootView.findViewById(R.id.tabs)


    @Inject override lateinit var presenter: ThemePickerPresenter

    @Inject lateinit var colors: Colors
    @Inject lateinit var themeAdapter: ThemeAdapter
    @Inject lateinit var themePagerAdapter: ThemePagerAdapter

    private val viewQksmsPlusSubject: Subject<Unit> = PublishSubject.create()

    init {
        appComponent
                .themePickerBuilder()
                .themePickerModule(ThemePickerModule(this))
                .build()
                .inject(this)

        layoutRes = R.layout.theme_picker_controller
    }

    override fun onViewCreated() {
        pager.offscreenPageLimit = 1
        pager.adapter = themePagerAdapter
        tabs.pager = pager

        themeAdapter.data = colors.materialColors

        materialColors.layoutManager = LinearLayoutManager(activity)
        materialColors.adapter = themeAdapter
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.title_theme)
        showBackButton(true)

        themedActivity?.supportActionBar?.let { toolbar ->
            ObjectAnimator.ofFloat(toolbar, "elevation", toolbar.elevation, 0f).start()
        }
    }

    override fun onDetach(view: View) {
        super.onDetach(view)

        themedActivity?.supportActionBar?.let { toolbar ->
            ObjectAnimator.ofFloat(toolbar, "elevation", toolbar.elevation, 8.dpToPx(toolbar.themedContext).toFloat()).start()
        }
    }

    override fun showQksmsPlusSnackbar() {
        Snackbar.make(contentView, R.string.toast_qksms_plus, Snackbar.LENGTH_LONG).run {
            setAction(R.string.button_more) { viewQksmsPlusSubject.onNext(Unit) }
            setActionTextColor(colors.theme().theme)
            show()
        }
    }

    override fun themeSelected(): Observable<Int> = themeAdapter.colorSelected

    override fun hsvThemeSelected(): Observable<Int> = picker.selectedColor

    override fun clearHsvThemeClicks(): Observable<*> = clear.clicks()

    override fun applyHsvThemeClicks(): Observable<*> = applyButton.clicks()

    override fun viewQksmsPlusClicks(): Observable<*> = viewQksmsPlusSubject

    override fun render(state: ThemePickerState) {
        tabs.setRecipientId(state.recipientId)

        hex.setText(Integer.toHexString(state.newColor).takeLast(6))

        applyGroup.setVisible(state.applyThemeVisible)
        applyButton.setBackgroundTint(state.newColor)
        applyButton.setTextColor(state.newTextColor)
    }

    override fun setCurrentTheme(color: Int) {
        picker.setColor(color)
        themeAdapter.selectedColor = color
    }

}