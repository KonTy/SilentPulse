package com.silentpulse.messenger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.silentpulse.messenger.feature.assistant.VoiceAssistantService

/**
 * Starts the VoiceAssistantService on device boot if the user previously
 * enabled the wake-word preference.
 *
 * Registered in AndroidManifest.xml alongside the existing BootReceiver.
 * Requires RECEIVE_BOOT_COMPLETED (already declared) and RECORD_AUDIO.
 */
class AssistantBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(
            "${context.packageName}_preferences", Context.MODE_PRIVATE
        )
        val wakeWordOn = prefs.getBoolean("drive_mode_wake_word", false)
        if (!wakeWordOn) {
            Log.d("AssistantBootReceiver", "Wake word disabled — skipping auto-start")
            return
        }

        // Can't start microphone service without RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("AssistantBootReceiver", "RECORD_AUDIO not granted — skipping auto-start")
            return
        }

        Log.d("AssistantBootReceiver", "Boot completed — starting VoiceAssistantService")
        val svcIntent = Intent(context, VoiceAssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }
    }
}
