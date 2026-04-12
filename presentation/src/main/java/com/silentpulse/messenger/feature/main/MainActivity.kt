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
package com.silentpulse.messenger.feature.main

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewStub
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.Navigator
import com.silentpulse.messenger.common.androidxcompat.drawerOpen
import com.silentpulse.messenger.common.base.QkThemedActivity
import com.silentpulse.messenger.common.util.extensions.autoScrollToStart
import com.silentpulse.messenger.common.util.extensions.dismissKeyboard
import com.silentpulse.messenger.common.util.extensions.resolveThemeColor
import com.silentpulse.messenger.common.util.extensions.scrapViews
import com.silentpulse.messenger.common.util.extensions.setBackgroundTint
import com.silentpulse.messenger.common.util.extensions.setTint
import com.silentpulse.messenger.common.util.extensions.setVisible
import com.silentpulse.messenger.feature.assistant.VoiceAssistantService
import com.silentpulse.messenger.feature.blocking.BlockingDialog
import com.silentpulse.messenger.feature.changelog.ChangelogDialog
import com.silentpulse.messenger.feature.conversations.ConversationItemTouchCallback
import com.silentpulse.messenger.feature.conversations.ConversationsAdapter
import com.silentpulse.messenger.manager.ChangelogManager
import com.silentpulse.messenger.repository.SyncRepository
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import dagger.android.AndroidInjection
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.silentpulse.messenger.common.widget.QkEditText
import com.silentpulse.messenger.common.widget.QkTextView

class MainActivity : QkThemedActivity(), MainView {

    // View references (migrated from synthetics)
    private val archived: LinearLayout get() = findViewById(R.id.archived)
    private val archivedIcon: ImageView get() = findViewById(R.id.archivedIcon)
    private val backup: LinearLayout get() = findViewById(R.id.backup)
    private val blocking: LinearLayout get() = findViewById(R.id.blocking)
    private val compose: ImageView get() = findViewById(R.id.compose)
    private val drawer: View get() = findViewById(R.id.drawer)
    private val drawerLayout: DrawerLayout get() = findViewById(R.id.drawerLayout)
    private val assistant: LinearLayout get() = findViewById(R.id.assistant)
    private val empty: QkTextView get() = findViewById(R.id.empty)
    private val inbox: LinearLayout get() = findViewById(R.id.inbox)
    private val inboxIcon: ImageView get() = findViewById(R.id.inboxIcon)
    private val invite: LinearLayout get() = findViewById(R.id.invite)
    private val recyclerView: RecyclerView get() = findViewById(R.id.recyclerView)
    private val scheduled: LinearLayout get() = findViewById(R.id.scheduled)
    private val settings: LinearLayout get() = findViewById(R.id.settings)
    private val snackbarButton: QkTextView? get() = findViewById(R.id.snackbarButton)
    private val snackbarMessage: QkTextView? get() = findViewById(R.id.snackbarMessage)
    private val snackbarTitle: QkTextView? get() = findViewById(R.id.snackbarTitle)
    private val syncingProgress: ProgressBar? get() = findViewById(R.id.syncingProgress)
    private val toolbarSearch: QkEditText get() = findViewById(R.id.toolbarSearch)


    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var disposables: CompositeDisposable
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationsAdapter: ConversationsAdapter
    @Inject lateinit var drawerBadgesExperiment: DrawerBadgesExperiment
    @Inject lateinit var searchAdapter: SearchAdapter
    @Inject lateinit var itemTouchCallback: ConversationItemTouchCallback
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    override val onNewIntentIntent: Subject<Intent> = PublishSubject.create()
    override val activityResumedIntent: Subject<Boolean> = PublishSubject.create()
    override val queryChangedIntent by lazy { toolbarSearch.textChanges() }
    override val composeIntent by lazy { compose.clicks() }
    override val drawerOpenIntent: Observable<Boolean> by lazy {
        drawerLayout
                .drawerOpen(Gravity.START)
                .doOnNext { dismissKeyboard() }
    }
    override val homeIntent: Subject<Unit> = PublishSubject.create()
    override val navigationIntent: Observable<NavItem> by lazy {
        Observable.merge(listOf(
                backPressedSubject,
                inbox.clicks().map { NavItem.INBOX },
                archived.clicks().map { NavItem.ARCHIVED },
                backup.clicks().map { NavItem.BACKUP },
                scheduled.clicks().map { NavItem.SCHEDULED },
                blocking.clicks().map { NavItem.BLOCKING },
                settings.clicks().map { NavItem.SETTINGS },
                assistant.clicks().map { NavItem.ASSISTANT },
                invite.clicks().map { NavItem.INVITE }))
    }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val conversationsSelectedIntent by lazy { conversationsAdapter.selectionChanges }
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val swipeConversationIntent by lazy { itemTouchCallback.swipes }
    override val changelogMoreIntent by lazy { changelogDialog.moreClicks }
    override val undoArchiveIntent: Subject<Unit> = PublishSubject.create()
    override val snackbarButtonIntent: Subject<Unit> = PublishSubject.create()

    private val viewModel by lazy { ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java] }
    private val toggle by lazy { ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.main_drawer_open_cd, 0) }
    private val itemTouchHelper by lazy { ItemTouchHelper(itemTouchCallback) }
    private val progressAnimator by lazy { ObjectAnimator.ofInt(syncingProgress!!, "progress", 0, 0) }
    private val changelogDialog by lazy { ChangelogDialog(this) }
    private val snackbar by lazy { findViewById<View>(R.id.snackbar) }
    private val syncing by lazy { findViewById<View>(R.id.syncing) }
    private val backPressedSubject: Subject<NavItem> = PublishSubject.create()
    private var driveModeAccessDialogShown = false
    private var defaultSmsDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        viewModel.bindView(this)
        onNewIntentIntent.onNext(intent)

        (snackbar as? ViewStub)?.setOnInflateListener { _, _ ->
            snackbarButton!!.clicks()
                    .autoDispose(scope(Lifecycle.Event.ON_DESTROY))
                    .subscribe(snackbarButtonIntent)
        }

        (syncing as? ViewStub)?.setOnInflateListener { _, _ ->
            syncingProgress?.progressTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
            syncingProgress?.indeterminateTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
        }

        toggle.syncState()
        toolbar?.setNavigationOnClickListener {
            dismissKeyboard()
            homeIntent.onNext(Unit)
        }

        itemTouchCallback.adapter = conversationsAdapter
        conversationsAdapter.autoScrollToStart(recyclerView)

        // Don't allow clicks to pass through the drawer layout
        drawer.clicks().autoDispose(scope()).subscribe()

        // Set the theme color tint to the recyclerView, progressbar, and FAB
        theme
                .autoDispose(scope())
                .subscribe { theme ->
                    // Set the color for the drawer icons
                    val states = arrayOf(
                            intArrayOf(android.R.attr.state_activated),
                            intArrayOf(-android.R.attr.state_activated))

                    resolveThemeColor(android.R.attr.textColorSecondary)
                            .let { textSecondary -> ColorStateList(states, intArrayOf(theme.theme, textSecondary)) }
                            .let { tintList ->
                                inboxIcon.imageTintList = tintList
                                archivedIcon.imageTintList = tintList
                            }

                    // Miscellaneous views
                    syncingProgress?.progressTintList = ColorStateList.valueOf(theme.theme)
                    syncingProgress?.indeterminateTintList = ColorStateList.valueOf(theme.theme)
                    compose.setBackgroundTint(theme.theme)

                    // Set the FAB compose icon color
                    compose.setTint(theme.textPrimary)
                }

        // These theme attributes don't apply themselves on API 21
        if (Build.VERSION.SDK_INT <= 22) {
            toolbarSearch.setBackgroundTint(resolveThemeColor(R.attr.bubbleColor))
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.run(onNewIntentIntent::onNext)
    }

    override fun render(state: MainState) {
        if (state.hasError) {
            finish()
            return
        }

        val addContact = when (state.page) {
            is Inbox -> state.page.addContact
            is Archived -> state.page.addContact
            else -> false
        }

        val markPinned = when (state.page) {
            is Inbox -> state.page.markPinned
            is Archived -> state.page.markPinned
            else -> true
        }

        val markRead = when (state.page) {
            is Inbox -> state.page.markRead
            is Archived -> state.page.markRead
            else -> true
        }

        val selectedConversations = when (state.page) {
            is Inbox -> state.page.selected
            is Archived -> state.page.selected
            else -> 0
        }

        toolbarSearch.setVisible(state.page is Inbox && state.page.selected == 0 || state.page is Searching)
        toolbarTitle?.setVisible(toolbarSearch.visibility != View.VISIBLE)

        toolbar?.menu?.findItem(R.id.archive)?.isVisible = state.page is Inbox && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.unarchive)?.isVisible = state.page is Archived && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.delete)?.isVisible = selectedConversations != 0
        toolbar?.menu?.findItem(R.id.add)?.isVisible = addContact && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.pin)?.isVisible = markPinned && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.unpin)?.isVisible = !markPinned && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.read)?.isVisible = markRead && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.unread)?.isVisible = !markRead && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.block)?.isVisible = selectedConversations != 0

        compose.setVisible(state.page is Inbox || state.page is Archived)
        conversationsAdapter.emptyView = empty.takeIf { state.page is Inbox || state.page is Archived }
        searchAdapter.emptyView = empty.takeIf { state.page is Searching }

        when (state.page) {
            is Inbox -> {
                showBackButton(state.page.selected > 0)
                title = getString(R.string.main_title_selected, state.page.selected)
                if (recyclerView.adapter !== conversationsAdapter) recyclerView.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(recyclerView)
                empty.setText(R.string.inbox_empty_text)
            }

            is Searching -> {
                showBackButton(true)
                if (recyclerView.adapter !== searchAdapter) recyclerView.adapter = searchAdapter
                searchAdapter.data = state.page.data ?: listOf()
                itemTouchHelper.attachToRecyclerView(null)
                empty.setText(R.string.inbox_search_empty_text)
            }

            is Archived -> {
                showBackButton(state.page.selected > 0)
                title = when (state.page.selected != 0) {
                    true -> getString(R.string.main_title_selected, state.page.selected)
                    false -> getString(R.string.title_archived)
                }
                if (recyclerView.adapter !== conversationsAdapter) recyclerView.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(null)
                empty.setText(R.string.archived_empty_text)
            }
        }

        inbox.isActivated = state.page is Inbox
        archived.isActivated = state.page is Archived

        if (drawerLayout.isDrawerOpen(GravityCompat.START) && !state.drawerOpen) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (!drawerLayout.isDrawerVisible(GravityCompat.START) && state.drawerOpen) {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        when (state.syncing) {
            is SyncRepository.SyncProgress.Idle -> {
                syncing.isVisible = false
                snackbar.isVisible = !state.defaultSms || !state.smsPermission || !state.contactPermission
            }

            is SyncRepository.SyncProgress.Running -> {
                syncing.isVisible = true
                syncingProgress?.let { progress ->
                    progress.max = state.syncing.max
                    progressAnimator.apply { setIntValues(progress.progress, state.syncing.progress) }.start()
                    progress.isIndeterminate = state.syncing.indeterminate
                }
                snackbar.isVisible = false
            }
        }

        when {
            !state.defaultSms -> {
                snackbarTitle?.setText(R.string.main_default_sms_title)
                snackbarMessage?.setText(R.string.main_default_sms_message)
                snackbarButton?.setText(R.string.main_default_sms_change)
            }

            !state.smsPermission -> {
                snackbarTitle?.setText(R.string.main_permission_required)
                snackbarMessage?.setText(R.string.main_permission_sms)
                snackbarButton?.setText(R.string.main_permission_allow)
            }

            !state.contactPermission -> {
                snackbarTitle?.setText(R.string.main_permission_required)
                snackbarMessage?.setText(R.string.main_permission_contacts)
                snackbarButton?.setText(R.string.main_permission_allow)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumedIntent.onNext(true)
        checkDriveModeNotificationAccess()
        ensureDriveModeMicService()
        autoStartVoiceAssistant()
        updateAssistantMenuIcon()
        if (!defaultSmsDialogShown &&
            android.provider.Telephony.Sms.getDefaultSmsPackage(this) != packageName) {
            defaultSmsDialogShown = true
            navigator.showDefaultSmsDialog(this)
        }
    }

    override fun onPause() {
        super.onPause()
        activityResumedIntent.onNext(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.dispose()
    }

    override fun showBackButton(show: Boolean) {
        toggle.onDrawerSlide(drawer, if (show) 1f else 0f)
        toggle.drawerArrowDrawable.color = when (show) {
            true -> resolveThemeColor(android.R.attr.textColorSecondary)
            false -> resolveThemeColor(android.R.attr.textColorPrimary)
        }
    }

    override fun requestDefaultSms() {
        navigator.showDefaultSmsDialog(this)
    }

    private fun checkDriveModeNotificationAccess() {
        val driveModeOn = getSharedPreferences("${packageName}_preferences", android.content.Context.MODE_PRIVATE)
            .getBoolean("drive_mode_enabled", false)
        if (!driveModeOn) return

        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        if (enabledListeners.contains(packageName)) return
        if (driveModeAccessDialogShown) return

        driveModeAccessDialogShown = true

        AlertDialog.Builder(this)
            .setTitle("Drive Mode: Permission Required")
            .setMessage(
                "Drive Mode reads your incoming messages aloud so you can stay hands-free while driving.\n\n" +
                "To work, it needs \"Notification Access\" — a system permission that lets SilentPulse see notifications.\n\n" +
                "Tap \"Grant Access\" and enable SilentPulse in the list."
            )
            .setPositiveButton("Grant Access") { _, _ ->
                driveModeAccessDialogShown = false // reset so we re-check when they return
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Not Now", null)
            .show()
    }

    /**
     * If Drive Mode is enabled, (re-)start [DriveModeMicService] so
     * the process holds a foreground‐with‐MICROPHONE token.
     * Must be called from an Activity context so Android 14+ allows
     * the foreground service transition.
     */
    private fun ensureDriveModeMicService() {
        val driveModeOn = getSharedPreferences("${packageName}_preferences", android.content.Context.MODE_PRIVATE)
            .getBoolean("drive_mode_enabled", false)
        if (driveModeOn) {
            com.silentpulse.messenger.feature.drivemode.DriveModeMicService.start(this)
        }
    }

    /**
     * If the wake-word preference is enabled, (re-)start VoiceAssistantService
     * automatically when the app opens — no need to navigate to Settings.
     */
    private fun autoStartVoiceAssistant() {
        val prefs = getSharedPreferences("${packageName}_preferences", MODE_PRIVATE)
        val wakeWordOn = prefs.getBoolean("drive_mode_wake_word", false)
        if (!wakeWordOn) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        // Only start if not already running — avoids re-posting the notification
        // every time MainActivity resumes
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        val alreadyRunning = am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == VoiceAssistantService::class.java.name
        }
        if (alreadyRunning) return
        val intent = Intent(this, VoiceAssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateAssistantMenuIcon() {
        val prefs = getSharedPreferences("${packageName}_preferences", MODE_PRIVATE)
        val isOn = prefs.getBoolean("drive_mode_wake_word", false)
        toolbar?.menu?.findItem(R.id.assistant_toggle)?.let { item ->
            item.setIcon(if (isOn) R.drawable.ic_mic_black_24dp else R.drawable.ic_mic_off_black_24dp)
            item.title = if (isOn) "Stop offline assistant" else "Start offline assistant"
        }
    }

    private fun toggleVoiceAssistant() {
        val prefs = getSharedPreferences("${packageName}_preferences", MODE_PRIVATE)
        val isOn = prefs.getBoolean("drive_mode_wake_word", false)
        if (isOn) {
            // Turn off
            prefs.edit().putBoolean("drive_mode_wake_word", false).apply()
            stopService(Intent(this, VoiceAssistantService::class.java))
        } else {
            // Check RECORD_AUDIO first
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.RECORD_AUDIO), 445)
                return
            }
            prefs.edit().putBoolean("drive_mode_wake_word", true).apply()
            val intent = Intent(this, VoiceAssistantService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        updateAssistantMenuIcon()
        // Keep AppWidget and QS tiles in sync with in-app voice-assistant toggle
        com.silentpulse.messenger.feature.drivemode.WidgetPrefs.broadcastStateChanged(this)
        com.silentpulse.messenger.feature.drivemode.DriveModeWidgetProvider.refreshAll(this)
    }

    override fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS), 0)
    }

    override fun clearSearch() {
        dismissKeyboard()
        toolbarSearch.text = null
    }

    override fun clearSelection() {
        conversationsAdapter.clearSelection()
    }

    override fun themeChanged() {
        recyclerView.scrapViews()
    }

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(this, conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        val count = conversations.size
        AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(resources.getQuantityString(R.plurals.dialog_delete_message, count, count))
                .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteIntent.onNext(conversations) }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
    }

    override fun showChangelog(changelog: ChangelogManager.CumulativeChangelog) {
        changelogDialog.show(changelog)
    }

    override fun showArchivedSnackbar() {
        Snackbar.make(drawerLayout, R.string.toast_archived, Snackbar.LENGTH_LONG).apply {
            setAction(R.string.button_undo) { undoArchiveIntent.onNext(Unit) }
            setActionTextColor(colors.theme().theme)
            show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.assistant_toggle) {
            toggleVoiceAssistant()
            return true
        }
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 445 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleVoiceAssistant()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        backPressedSubject.onNext(NavItem.BACK)
    }

}
