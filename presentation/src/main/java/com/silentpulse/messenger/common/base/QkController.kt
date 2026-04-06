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
package com.silentpulse.messenger.common.base

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.bluelinelabs.conductor.archlifecycle.LifecycleController
import com.silentpulse.messenger.R

abstract class QkController<ViewContract : QkViewContract<State>, State : Any, Presenter : QkPresenter<ViewContract, State>> : LifecycleController() {

    abstract var presenter: Presenter

    private val appCompatActivity: AppCompatActivity?
        get() = activity as? AppCompatActivity

    protected val themedActivity: QkThemedActivity?
        get() = activity as? QkThemedActivity

    @LayoutRes
    var layoutRes: Int = 0

    // Conductor sets `view` only after onCreateView() returns, so we store the
    // in-flight inflated view here so rootView works during onViewCreated().
    private var _createdView: View? = null

    /**
     * Use this instead of `view!!` in property getters.
     * Falls back to the in-progress inflated view when called during onViewCreated().
     */
    protected val rootView: View
        get() = view ?: checkNotNull(_createdView) { "rootView accessed outside view lifecycle" }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup, savedViewState: android.os.Bundle?): View {
        return inflater.inflate(layoutRes, container, false).also { v ->
            _createdView = v
            onViewCreated()
        }
    }

    override fun onDestroyView(view: View) {
        super.onDestroyView(view)
        _createdView = null
    }

    open fun onViewCreated() {
    }

    fun setTitle(@StringRes titleId: Int) {
        setTitle(activity?.getString(titleId))
    }

    fun setTitle(title: CharSequence?) {
        activity?.title = title
        view?.findViewById<TextView>(R.id.toolbarTitle)?.text = title
    }

    fun showBackButton(show: Boolean) {
        appCompatActivity?.supportActionBar?.setDisplayHomeAsUpEnabled(show)
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onCleared()
    }

}
