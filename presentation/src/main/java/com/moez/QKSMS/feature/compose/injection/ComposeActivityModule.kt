package com.moez.QKSMS.feature.compose.injection

import com.moez.QKSMS.feature.compose.ComposeActivity
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
abstract class ComposeActivityModule {
    @ContributesAndroidInjector(modules = [ComposeModule::class])
    abstract fun contributeComposeActivity(): ComposeActivity
}
