package com.silentpulse.messenger.feature.drivemode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.silentpulse.messenger.R
import com.silentpulse.messenger.injection.appComponent
import timber.log.Timber
import javax.inject.Inject

/**
 * Persistent foreground service with FOREGROUND_SERVICE_TYPE_MICROPHONE.
 *
 * Android 11+ silences the microphone for background services (including
 * NotificationListenerService). Android 14+/SDK 35 further restricts
 * starting a foreground service with MICROPHONE type from the background.
 *
 * This service solves both problems:
 *   - It is started from the **Activity** (foreground context) when Drive Mode
 *     is enabled, so the system allows the foreground‐with‐mic transition.
 *   - Once running, it stays alive (START_STICKY) and holds the foreground‐mic
 *     token for the entire process. All AudioRecord calls from other services
 *     (e.g. the NotificationListener STT) benefit from the unsilenced mic.
 *
 * Lifecycle:
 *   1. User enables Drive Mode → Activity starts this service.
 *   2. Service goes foreground with MICROPHONE type immediately.
 *   3. Service stays alive until Drive Mode is disabled or explicitly stopped.
 *   4. On app resume, MainActivity re-starts the service if Drive Mode is still on.
 *
 * No network calls. No data leaves the device. Ever.
 */
class DriveModeMicService : Service() {

    companion object {
        private const val CHANNEL_ID = "drive_mode_mic_channel"
        private const val NOTIFICATION_ID = 1778
        private const val TAG = "DriveModeMicSvc"

        /** Action sent to tell this service to stop itself. */
        const val ACTION_STOP = "com.silentpulse.messenger.STOP_MIC_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, DriveModeMicService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "start() called")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start DriveModeMicService")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DriveModeMicService::class.java))
                Log.d(TAG, "stop() called")
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop DriveModeMicService")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        ensureNotificationChannel()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Received STOP action")
            stopSelf()
            return START_NOT_STICKY
        }
        // Re-assert foreground in case the system recreated us
        goForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        super.onDestroy()
    }

    private fun goForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SilentPulse Drive Mode")
            .setContentText("Voice commands active")
            .setSmallIcon(R.drawable.ic_notifications_black_24dp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground with MICROPHONE type started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start foreground — mic may be silenced")
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drive Mode Voice Input",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while listening for your voice command"
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
