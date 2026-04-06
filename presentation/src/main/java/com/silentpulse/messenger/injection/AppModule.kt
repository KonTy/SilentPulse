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
package com.silentpulse.messenger.injection

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.Context.MODE_PRIVATE
import androidx.lifecycle.ViewModelProvider
import com.f2prateek.rx.preferences2.RxSharedPreferences
import com.silentpulse.messenger.blocking.BlockingClient
import com.silentpulse.messenger.blocking.BlockingManager
import com.silentpulse.messenger.common.ViewModelFactory
import com.silentpulse.messenger.common.util.BillingManagerImpl
import com.silentpulse.messenger.common.util.NotificationManagerImpl
import com.silentpulse.messenger.common.util.ShortcutManagerImpl
import com.silentpulse.messenger.feature.conversationinfo.injection.ConversationInfoComponent
import com.silentpulse.messenger.feature.themepicker.injection.ThemePickerComponent
import com.silentpulse.messenger.listener.ContactAddedListener
import com.silentpulse.messenger.listener.ContactAddedListenerImpl
import com.silentpulse.messenger.manager.ActiveConversationManager
import com.silentpulse.messenger.manager.ActiveConversationManagerImpl
import com.silentpulse.messenger.manager.AlarmManager
import com.silentpulse.messenger.manager.AlarmManagerImpl
import com.silentpulse.messenger.manager.AnalyticsManager
import com.silentpulse.messenger.manager.AnalyticsManagerImpl
import com.silentpulse.messenger.manager.BillingManager
import com.silentpulse.messenger.manager.ChangelogManager
import com.silentpulse.messenger.manager.ChangelogManagerImpl
import com.silentpulse.messenger.manager.KeyManager
import com.silentpulse.messenger.manager.KeyManagerImpl
import com.silentpulse.messenger.manager.NotificationManager
import com.silentpulse.messenger.manager.PermissionManager
import com.silentpulse.messenger.manager.PermissionManagerImpl
import com.silentpulse.messenger.manager.RatingManager
import com.silentpulse.messenger.manager.ReferralManager
import com.silentpulse.messenger.manager.ReferralManagerImpl
import com.silentpulse.messenger.manager.ShortcutManager
import com.silentpulse.messenger.manager.WidgetManager
import com.silentpulse.messenger.manager.WidgetManagerImpl
import com.silentpulse.messenger.mapper.CursorToContact
import com.silentpulse.messenger.mapper.CursorToContactGroup
import com.silentpulse.messenger.mapper.CursorToContactGroupImpl
import com.silentpulse.messenger.mapper.CursorToContactGroupMember
import com.silentpulse.messenger.mapper.CursorToContactGroupMemberImpl
import com.silentpulse.messenger.mapper.CursorToContactImpl
import com.silentpulse.messenger.mapper.CursorToConversation
import com.silentpulse.messenger.mapper.CursorToConversationImpl
import com.silentpulse.messenger.mapper.CursorToMessage
import com.silentpulse.messenger.mapper.CursorToMessageImpl
import com.silentpulse.messenger.mapper.CursorToPart
import com.silentpulse.messenger.mapper.CursorToPartImpl
import com.silentpulse.messenger.mapper.CursorToRecipient
import com.silentpulse.messenger.mapper.CursorToRecipientImpl
import com.silentpulse.messenger.mapper.RatingManagerImpl
import com.silentpulse.messenger.repository.BackupRepository
import com.silentpulse.messenger.repository.BackupRepositoryImpl
import com.silentpulse.messenger.repository.BlockingRepository
import com.silentpulse.messenger.repository.BlockingRepositoryImpl
import com.silentpulse.messenger.repository.ContactRepository
import com.silentpulse.messenger.repository.ContactRepositoryImpl
import com.silentpulse.messenger.repository.ConversationRepository
import com.silentpulse.messenger.repository.ConversationRepositoryImpl
import com.silentpulse.messenger.repository.MessageRepository
import com.silentpulse.messenger.repository.MessageRepositoryImpl
import com.silentpulse.messenger.repository.ScheduledMessageRepository
import com.silentpulse.messenger.repository.ScheduledMessageRepositoryImpl
import com.silentpulse.messenger.repository.SyncRepository
import com.silentpulse.messenger.repository.SyncRepositoryImpl
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module(subcomponents = [
    ConversationInfoComponent::class,
    ThemePickerComponent::class])
class AppModule(private var application: Application) {

    @Provides
    @Singleton
    fun provideContext(): Context = application

    @Provides
    fun provideContentResolver(context: Context): ContentResolver = context.contentResolver

    @Provides
    @Singleton
    fun provideSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences("${context.packageName}_preferences", MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideRxPreferences(preferences: SharedPreferences): RxSharedPreferences {
        return RxSharedPreferences.create(preferences)
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
    }

    @Provides
    fun provideViewModelFactory(factory: ViewModelFactory): ViewModelProvider.Factory = factory

    // Listener

    @Provides
    fun provideContactAddedListener(listener: ContactAddedListenerImpl): ContactAddedListener = listener

    // Manager

    @Provides
    fun provideBillingManager(manager: BillingManagerImpl): BillingManager = manager

    @Provides
    fun provideActiveConversationManager(manager: ActiveConversationManagerImpl): ActiveConversationManager = manager

    @Provides
    fun provideAlarmManager(manager: AlarmManagerImpl): AlarmManager = manager

    @Provides
    fun provideAnalyticsManager(manager: AnalyticsManagerImpl): AnalyticsManager = manager

    @Provides
    fun blockingClient(manager: BlockingManager): BlockingClient = manager

    @Provides
    fun changelogManager(manager: ChangelogManagerImpl): ChangelogManager = manager

    @Provides
    fun provideKeyManager(manager: KeyManagerImpl): KeyManager = manager

    @Provides
    fun provideNotificationsManager(manager: NotificationManagerImpl): NotificationManager = manager

    @Provides
    fun providePermissionsManager(manager: PermissionManagerImpl): PermissionManager = manager

    @Provides
    fun provideRatingManager(manager: RatingManagerImpl): RatingManager = manager

    @Provides
    fun provideShortcutManager(manager: ShortcutManagerImpl): ShortcutManager = manager

    @Provides
    fun provideReferralManager(manager: ReferralManagerImpl): ReferralManager = manager

    @Provides
    fun provideWidgetManager(manager: WidgetManagerImpl): WidgetManager = manager

    // Mapper

    @Provides
    fun provideCursorToContact(mapper: CursorToContactImpl): CursorToContact = mapper

    @Provides
    fun provideCursorToContactGroup(mapper: CursorToContactGroupImpl): CursorToContactGroup = mapper

    @Provides
    fun provideCursorToContactGroupMember(mapper: CursorToContactGroupMemberImpl): CursorToContactGroupMember = mapper

    @Provides
    fun provideCursorToConversation(mapper: CursorToConversationImpl): CursorToConversation = mapper

    @Provides
    fun provideCursorToMessage(mapper: CursorToMessageImpl): CursorToMessage = mapper

    @Provides
    fun provideCursorToPart(mapper: CursorToPartImpl): CursorToPart = mapper

    @Provides
    fun provideCursorToRecipient(mapper: CursorToRecipientImpl): CursorToRecipient = mapper

    // Repository

    @Provides
    fun provideBackupRepository(repository: BackupRepositoryImpl): BackupRepository = repository

    @Provides
    fun provideBlockingRepository(repository: BlockingRepositoryImpl): BlockingRepository = repository

    @Provides
    fun provideContactRepository(repository: ContactRepositoryImpl): ContactRepository = repository

    @Provides
    fun provideConversationRepository(repository: ConversationRepositoryImpl): ConversationRepository = repository

    @Provides
    fun provideMessageRepository(repository: MessageRepositoryImpl): MessageRepository = repository

    @Provides
    fun provideScheduledMessagesRepository(repository: ScheduledMessageRepositoryImpl): ScheduledMessageRepository = repository

    @Provides
    fun provideSyncRepository(repository: SyncRepositoryImpl): SyncRepository = repository

}
