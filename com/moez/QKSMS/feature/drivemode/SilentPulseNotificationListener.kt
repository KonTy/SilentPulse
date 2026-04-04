package com.moez.QKSMS.feature.drivemode

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class SilentPulseNotificationListener : NotificationListenerService() {

    @Inject lateinit var driveModeService: DriveModeService

    companion object {
        private val storedNotifications = ConcurrentHashMap<String, StatusBarNotification>()
        
        fun getStoredNotification(key: String): StatusBarNotification? {
            return storedNotifications[key]
        }
        
        fun getStoredReplyAction(key: String): Notification.Action? {
            val sbn = storedNotifications[key] ?: return null
            val notification = sbn.notification ?: return null
            
            // Look for RemoteInput action
            notification.actions?.forEach { action ->
                if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                    // Found a reply action
                    return action
                }
            }
            
            return null
        }
        
        fun clearStoredNotification(key: String) {
            storedNotifications.remove(key)
        }
        
        fun clearAllStoredNotifications() {
            storedNotifications.clear()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        
        if (sbn == null) return
        
        // Store the notification
        storedNotifications[sbn.key] = sbn
        
        // Clean up old notifications (keep only last 50)
        if (storedNotifications.size > 50) {
            val oldestKey = storedNotifications.keys.firstOrNull()
            oldestKey?.let { storedNotifications.remove(it) }
        }

        if (isDriveModeEnabledForAllNotifications()) {
            Timber.d("Drive Mode enabled - intercepting notification from ${sbn.packageName}")
            
            try {
                // Cancel the notification to keep it silent
                cancelNotification(sbn.key)
                
                // Process the notification through Drive Mode
                processDriveModeNotification(sbn)
            } catch (e: Exception) {
                Timber.e(e, "Error processing notification in Drive Mode")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        
        sbn?.key?.let { key ->
            clearStoredNotification(key)
        }
    }

    private fun processDriveModeNotification(sbn: StatusBarNotification) {
        try {
            val notification = sbn.notification
            val extras = notification.extras
            
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Unknown"
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val appName = getAppName(sbn.packageName)
            
            Timber.d("Processing notification: $appName - $title: $text")
            
            if (::driveModeService.isInitialized) {
                driveModeService.handleIncomingNotification(
                    app = appName,
                    sender = title,
                    messageBody = text,
                    notificationKey = sbn.key
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing Drive Mode notification")
        }
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "org.thoughtcrime.securesms" -> "Signal"
            "com.whatsapp" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.google.android.gm" -> "Gmail"
            "com.facebook.orca" -> "Messenger"
            "com.android.mms", "com.google.android.apps.messaging" -> "SMS"
            else -> {
                try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    packageName
                }
            }
        }
    }

    private fun isDriveModeEnabledForAllNotifications(): Boolean {
        return try {
            if (::driveModeService.isInitialized) {
                driveModeService.isDriveModeEnabledForAllNotifications()
            } else {
                Timber.w("DriveModeService not initialized yet")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking Drive Mode status")
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearAllStoredNotifications()
    }
}
