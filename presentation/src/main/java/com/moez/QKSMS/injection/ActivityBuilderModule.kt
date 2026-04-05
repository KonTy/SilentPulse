package com.moez.QKSMS.injection

import com.moez.QKSMS.feature.compose.injection.ComposeActivityModule
import dagger.Module

@Module(includes = [
    ComposeActivityModule::class
    // ... other activity modules
])
abstract class ActivityBuilderModule
