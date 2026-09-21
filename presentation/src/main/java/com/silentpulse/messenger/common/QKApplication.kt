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
@file:Suppress("OPT_IN_USAGE")
package com.silentpulse.messenger.common

import android.app.Application
import android.os.Build
import com.silentpulse.messenger.BuildConfig
import com.silentpulse.messenger.common.util.FileLoggingTree
import com.silentpulse.messenger.common.util.MetadataDebugTree
import com.silentpulse.messenger.injection.AppComponentManager
import com.silentpulse.messenger.injection.appComponent
import com.silentpulse.messenger.manager.AnalyticsManager
import com.silentpulse.messenger.manager.BillingManager
import com.silentpulse.messenger.manager.ReferralManager
import com.silentpulse.messenger.migration.QkMigration
import com.silentpulse.messenger.migration.QkRealmMigration
import com.silentpulse.messenger.util.NightModeManager
import com.uber.rxdogtag.RxDogTag
import com.uber.rxdogtag.autodispose.AutoDisposeConfigurer
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasAndroidInjector
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.log.LogLevel
import io.realm.log.RealmLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class QKApplication : Application(), HasAndroidInjector {

    /**
     * Inject these so that they are forced to initialize
     */
    @Suppress("unused")
    @Inject lateinit var analyticsManager: AnalyticsManager
    @Suppress("unused")
    @Inject lateinit var qkMigration: QkMigration

    @Inject lateinit var billingManager: BillingManager
    @Inject lateinit var androidInjector: DispatchingAndroidInjector<Any>
    @Inject lateinit var fileLoggingTree: FileLoggingTree
    @Inject lateinit var nightModeManager: NightModeManager
    @Inject lateinit var realmMigration: QkRealmMigration
    @Inject lateinit var referralManager: ReferralManager

    override fun onCreate() {
        super.onCreate()

        AppComponentManager.init(this)
        appComponent.inject(this)

        if (BuildConfig.DEBUG) {
            Timber.plant(MetadataDebugTree(), fileLoggingTree)
            setupUncaughtExceptionHandler()
            Timber.i("app_started version=${BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT}")
        }

        Realm.init(this)
        if (!BuildConfig.DEBUG) RealmLog.setLevel(LogLevel.OFF)
        Realm.setDefaultConfiguration(RealmConfiguration.Builder()
                .compactOnLaunch()
                .migration(realmMigration)
                .schemaVersion(QkRealmMigration.SchemaVersion)
                .build())

        qkMigration.performMigration()

        GlobalScope.launch(Dispatchers.IO) {
            referralManager.trackReferrer()
            billingManager.checkForPurchases()
            billingManager.queryProducts()
        }

        nightModeManager.updateCurrentTheme()

        if (BuildConfig.DEBUG) {
            RxDogTag.builder()
                    .configureWith(AutoDisposeConfigurer::configure)
                    .install()
        }
    }

    private fun setupUncaughtExceptionHandler() {
        if (!BuildConfig.DEBUG) return
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Timber.e("uncaught_exception type=${throwable.javaClass.simpleName}")
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    override fun androidInjector(): AndroidInjector<Any> {
        return androidInjector
    }

}
