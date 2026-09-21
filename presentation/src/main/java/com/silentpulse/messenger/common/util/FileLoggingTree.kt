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
package com.silentpulse.messenger.common.util

import android.content.Context
import android.util.Log
import com.silentpulse.messenger.BuildConfig
import com.silentpulse.messenger.util.Preferences
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
        if (BuildConfig.DEBUG) cleanupOldLogs()
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!BuildConfig.DEBUG || !prefs.logging.get()) return

        // Timber formats exceptions into message too. Neither it nor arbitrary tags
        // are safe to persist, even when a caller forgets to redact its payload.
        val metadata = DiagnosticMetadata.describe(t)
        val priorityString = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "WTF"
        }

        // Log to file asynchronously
        Schedulers.io().scheduleDirect {
            if (!BuildConfig.DEBUG) return@scheduleDirect

            // Ensure that only one thread is writing to the file at a time
            synchronized(fileLock) {
                try {
                    val timestamp = timestampFormat.format(System.currentTimeMillis())
                    val log = "$timestamp $priorityString $metadata\n".toByteArray()
                    // Create the directory
                    val dir = File(context.filesDir, "Logs").apply { mkdirs() }

                    // Create the file with today's date
                    val file = File(dir, "${dateFormat.format(System.currentTimeMillis())}.log")

                    // Write the log to the file
                    FileOutputStream(file, true).use { fileOutputStream -> 
                        fileOutputStream.write(log) 
                    }
                } catch (e: Exception) {
                    Log.e("FileLoggingTree", "log_write_failed type=${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Delete log files older than 7 days
     */
    private fun cleanupOldLogs() {
        if (!BuildConfig.DEBUG) return
        Schedulers.io().scheduleDirect {
            if (!BuildConfig.DEBUG) return@scheduleDirect
            try {
                val dir = File(context.filesDir, "Logs")
                if (!dir.exists()) return@scheduleDirect

                val sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < sevenDaysAgo) {
                        val deleted = file.delete()
                        if (deleted) {
                            Log.d("FileLoggingTree", "expired_log_removed")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FileLoggingTree", "log_cleanup_failed type=${e.javaClass.simpleName}")
            }
        }
    }
}
