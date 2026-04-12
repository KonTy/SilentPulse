package com.silentpulse.messenger.feature.assistant

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.rxbinding2.widget.checkedChanges
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkController
import com.silentpulse.messenger.common.base.QkViewContract
import com.silentpulse.messenger.feature.drivemode.DriveModeMicService
import com.silentpulse.messenger.feature.drivemode.DriveModeWidgetProvider
import com.silentpulse.messenger.feature.drivemode.WidgetPrefs
import com.silentpulse.messenger.injection.appComponent
import com.silentpulse.messenger.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import javax.inject.Inject

// Minimal no-op state/view/presenter so QkController is satisfied
data class NotificationReaderState(val dummy: Unit = Unit)
interface NotificationReaderView : QkViewContract<NotificationReaderState>
class NotificationReaderPresenter @Inject constructor() :
    com.silentpulse.messenger.common.base.QkPresenter<NotificationReaderView, NotificationReaderState>(NotificationReaderState())

class NotificationReaderController :
    QkController<NotificationReaderView, NotificationReaderState, NotificationReaderPresenter>(),
    NotificationReaderView {

    private val enableSwitch: SwitchCompat  get() = rootView!!.findViewById(R.id.notifReaderSwitch)
    private val readAllRow: LinearLayout    get() = rootView!!.findViewById(R.id.readAllRow)
    private val readAllSwitch: SwitchCompat get() = rootView!!.findViewById(R.id.readAllSwitch)
    private val readSmsRow: LinearLayout    get() = rootView!!.findViewById(R.id.readSmsRow)
    private val readSmsSwitch: SwitchCompat get() = rootView!!.findViewById(R.id.readSmsSwitch)

    @Inject override lateinit var presenter: NotificationReaderPresenter
    @Inject lateinit var prefs: Preferences

    init {
        appComponent.inject(this)
        layoutRes = R.layout.notification_reader_controller
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.title_notification_reader)
        showBackButton(true)

        // Initialise state from prefs
        val enabled = prefs.driveModeEnabled.get()
        enableSwitch.isChecked  = enabled
        readAllRow.isVisible    = enabled
        readSmsRow.isVisible    = enabled
        readAllSwitch.isChecked = prefs.driveModeReadAllNotifications.get()
        readSmsSwitch.isChecked = prefs.driveModeReadSms.get()

        enableSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { checked ->
                prefs.driveModeEnabled.set(checked)
                readAllRow.isVisible = checked
                readSmsRow.isVisible = checked
                if (checked) {
                    checkNotificationAccess()
                    DriveModeMicService.start(requireContext())
                } else {
                    DriveModeMicService.stop(requireContext())
                }
                WidgetPrefs.broadcastStateChanged(requireContext())
                DriveModeWidgetProvider.refreshAll(requireContext())
            }

        readAllSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { prefs.driveModeReadAllNotifications.set(it) }

        readSmsSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { prefs.driveModeReadSms.set(it) }
    }

    override fun render(state: NotificationReaderState) = Unit

    private fun requireContext() = activity!!.applicationContext

    private fun checkNotificationAccess() {
        val ctx = activity ?: return
        val granted = androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(ctx).contains(ctx.packageName)
        if (!granted) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Notification Access Required")
                .setMessage(
                    "Notification Reader reads incoming notifications aloud.\n\n" +
                    "It needs \"Notification Access\" — a system permission that lets " +
                    "SilentPulse see notifications.\n\n" +
                    "Tap \"Grant Access\" and enable SilentPulse in the list."
                )
                .setPositiveButton("Grant Access") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Not Now", null)
                .show()
        }
    }
}
