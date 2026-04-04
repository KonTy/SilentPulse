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
package com.moez.QKSMS.common.util

import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.moez.QKSMS.util.Preferences
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Based off Vipin Kumar's FileLoggingTree: https://medium.com/@vicky7230/file-logging-with-timber-4e63a1b86a66
 */
@Singleton
class FileLoggingTree @Inject constructor(
    private val context: Context,
    private val prefs: Preferences
) : Timber.DebugTree() {

    private val fileLock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS", Locale.getDefault())

    init {
        // Clean up old log files on initialization
        cleanupOldLogs()
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!prefs.logging.get()) return

        val timestamp = timestampFormat.format(System.currentTimeMillis())
        val priorityString = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "WTF"
        }

        // Log to Firebase Crashlytics
        try {
            FirebaseCrashlytics.getInstance().log("$priorityString/$tag: $message")
            
            // For errors, record the exception
            if (priority >= Log.ERROR) {
                FirebaseCrashlytics.getInstance().recordException(t ?: Exception(message))
            }
        } catch (e: Exception) {
            // Crashlytics might not be available in noAnalytics build
            Log.e("FileLoggingTree", "Error logging to Crashlytics", e)
        }

        // Log to file asynchronously
        Schedulers.io().scheduleDirect {
            // Format the log to be written to the file
            val log = "$timestamp $priorityString/$tag: $message ${Log.getStackTraceString(t)}\n".toByteArray()

            // Ensure that only one thread is writing to the file at a time
            synchronized(fileLock) {
                try {
                    // Create the directory
                    val dir = File(context.getExternalFilesDir(null), "Logs").apply { mkdirs() }

                    // Create the file with today's date
                    val file = File(dir, "${dateFormat.format(System.currentTimeMillis())}.log")

                    // Write the log to the file
                    FileOutputStream(file, true).use { fileOutputStream -> 
                        fileOutputStream.write(log) 
                    }
                } catch (e: Exception) {
                    Log.e("FileLoggingTree", "Error while logging into file", e)
                }
            }
        }
    }

    /**
     * Delete log files older than 7 days
     */
    private fun cleanupOldLogs() {
        Schedulers.io().scheduleDirect {
            try {
                val dir = File(context.getExternalFilesDir(null), "Logs")
                if (!dir.exists()) return@scheduleDirect

                val sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < sevenDaysAgo) {
                        val deleted = file.delete()
                        if (deleted) {
                            Log.d("FileLoggingTree", "Deleted old log file: ${file.name}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FileLoggingTree", "Error cleaning up old logs", e)
            }
        }
    }
}
