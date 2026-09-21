package com.silentpulse.messenger.common.util

import android.util.Log
import com.silentpulse.messenger.BuildConfig
import timber.log.Timber

/** Legacy messaging libraries can pass phone numbers and entire PDUs to Timber. */
class MetadataDebugTree : Timber.DebugTree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (BuildConfig.DEBUG) Log.println(priority, "SilentPulse", DiagnosticMetadata.describe(t))
    }
}
