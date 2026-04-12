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

import com.silentpulse.messenger.common.QKApplication
import com.silentpulse.messenger.common.QkDialog
import com.silentpulse.messenger.common.util.QkChooserTargetService
import com.silentpulse.messenger.common.widget.AvatarView
import com.silentpulse.messenger.common.widget.PagerTitleView
import com.silentpulse.messenger.common.widget.PreferenceView
import com.silentpulse.messenger.common.widget.QkEditText
import com.silentpulse.messenger.common.widget.QkSwitch
import com.silentpulse.messenger.common.widget.QkTextView
import com.silentpulse.messenger.common.widget.RadioPreferenceView
import com.silentpulse.messenger.feature.assistant.AssistantController
import com.silentpulse.messenger.feature.assistant.NotificationReaderController
import com.silentpulse.messenger.feature.backup.BackupController
import com.silentpulse.messenger.feature.blocking.BlockingController
import com.silentpulse.messenger.feature.blocking.manager.BlockingManagerController
import com.silentpulse.messenger.feature.blocking.messages.BlockedMessagesController
import com.silentpulse.messenger.feature.blocking.numbers.BlockedNumbersController
import com.silentpulse.messenger.feature.compose.editing.DetailedChipView
import com.silentpulse.messenger.feature.conversationinfo.injection.ConversationInfoComponent
import com.silentpulse.messenger.feature.drivemode.SilentPulseNotificationListener
import com.silentpulse.messenger.feature.settings.SettingsController
import com.silentpulse.messenger.feature.settings.about.AboutController
import com.silentpulse.messenger.feature.settings.swipe.SwipeActionsController
import com.silentpulse.messenger.feature.themepicker.injection.ThemePickerComponent
import com.silentpulse.messenger.feature.widget.WidgetAdapter
import com.silentpulse.messenger.injection.android.ActivityBuilderModule
import com.silentpulse.messenger.injection.android.BroadcastReceiverBuilderModule
import com.silentpulse.messenger.injection.android.ServiceBuilderModule
import dagger.Component
import dagger.android.support.AndroidSupportInjectionModule
import javax.inject.Singleton

@Singleton
@Component(modules = [
    AndroidSupportInjectionModule::class,
    AppModule::class,
    ActivityBuilderModule::class,
    BroadcastReceiverBuilderModule::class,
    ServiceBuilderModule::class])
interface AppComponent {

    fun conversationInfoBuilder(): ConversationInfoComponent.Builder
    fun themePickerBuilder(): ThemePickerComponent.Builder

    fun inject(application: QKApplication)

    fun inject(controller: AboutController)
    fun inject(controller: AssistantController)
    fun inject(controller: NotificationReaderController)
    fun inject(controller: BackupController)
    fun inject(controller: BlockedMessagesController)
    fun inject(controller: BlockedNumbersController)
    fun inject(controller: BlockingController)
    fun inject(controller: BlockingManagerController)
    fun inject(controller: SettingsController)
    fun inject(controller: SwipeActionsController)

    fun inject(dialog: QkDialog)

    fun inject(service: WidgetAdapter)

    /**
     * This can't use AndroidInjection, or else it will crash on pre-marshmallow devices
     */
    fun inject(service: QkChooserTargetService)
    fun inject(listener: SilentPulseNotificationListener)

    fun inject(view: AvatarView)
    fun inject(view: DetailedChipView)
    fun inject(view: PagerTitleView)
    fun inject(view: PreferenceView)
    fun inject(view: RadioPreferenceView)
    fun inject(view: QkEditText)
    fun inject(view: QkSwitch)
    fun inject(view: QkTextView)

}
