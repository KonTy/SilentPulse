package com.moez.QKSMS.feature.compose.injection

import android.app.Activity
import android.content.Intent
import dagger.Module
import dagger.Provides
import javax.inject.Named

@Module
class ComposeModule(private val activity: Activity) {

    @Provides
    @Named("query")
    fun provideQuery(): String {
        return activity.intent.getStringExtra("query") ?: ""
    }

    @Provides
    @Named("threadId")
    fun provideThreadId(): Long {
        return activity.intent.getLongExtra("threadId", 0L)
    }

    @Provides
    @Named("addresses")
    fun provideAddresses(): List<String> {
        return activity.intent.getStringArrayListExtra("addresses") ?: emptyList()
    }

    @Provides
    @Named("text")
    fun provideSharedText(): String {
        return activity.intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
    }

    @Provides
    @Named("attachments")
    fun provideSharedAttachments(): com.moez.QKSMS.model.Attachments {
        // This is a placeholder; actual implementation may vary
        return activity.intent.getParcelableArrayListExtra("attachments")
            ?: com.moez.QKSMS.model.Attachments()
    }
}
