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
package com.moez.QKSMS.blocking

import android.content.Context
import com.moez.QKSMS.common.util.extensions.isInstalled
import io.reactivex.Completable
import io.reactivex.Single
import timber.log.Timber
import javax.inject.Inject

/**
 * CallControl blocking integration.
 * The callcontrol datashare library is no longer available on Maven repositories.
 * This stub preserves the interface while the library is unavailable.
 */
class CallControlBlockingClient @Inject constructor(
    private val context: Context
) : BlockingClient {

    override fun isAvailable(): Boolean = context.isInstalled("com.flexaspect.android.everycallcontrol")

    override fun getClientCapability() = BlockingClient.Capability.BLOCK_WITH_PERMISSION

    override fun shouldBlock(address: String): Single<BlockingClient.Action> = isBlacklisted(address)

    override fun isBlacklisted(address: String): Single<BlockingClient.Action> = Single.fromCallable {
        Timber.w("CallControl datashare library not available")
        BlockingClient.Action.DoNothing
    }

    override fun block(addresses: List<String>): Completable = Completable.fromCallable {
        Timber.w("CallControl datashare library not available")
    }

    override fun unblock(addresses: List<String>): Completable = Completable.fromCallable {
        Timber.w("CallControl datashare library not available")
    }

    override fun openSettings() {
        Timber.w("CallControl datashare library not available")
    }

}
