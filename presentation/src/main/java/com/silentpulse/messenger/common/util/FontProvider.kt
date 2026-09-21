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
package com.silentpulse.messenger.common.util

import android.content.Context
import android.graphics.Typeface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FontProvider @Inject constructor(@Suppress("UNUSED_PARAMETER") context: Context) {

    // Keep the legacy callback API for existing consumers, using only the OS font.
    fun getLato(callback: (Typeface) -> Unit) {
        callback(Typeface.DEFAULT)
    }

}