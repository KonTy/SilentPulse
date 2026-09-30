package com.silentpulse.messenger.feature.settingsbackup

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.AtomicFile
import com.silentpulse.messenger.feature.stocks.StockWidgetPreferences
import com.silentpulse.messenger.feature.worldclock.WorldClockPreferences
import com.squareup.moshi.Moshi
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class AndroidSettingsStore(private val context: Context) : SettingsStore {
    private fun preferences(section: String): SharedPreferences {
        val name = when (section) {
            SettingsPolicy.APP -> "${context.packageName}_preferences"
            SettingsPolicy.FAMILY -> "family_hub"
            SettingsPolicy.STOCKS -> "stock_widgets"
            SettingsPolicy.CLOCKS -> "world_clock_widgets"
            SettingsPolicy.PRESETS -> "settings_backup_presets"
            else -> error("Unsupported settings section")
        }
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    override fun read(section: String): Map<String, Any> =
        preferences(section).all.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

    override fun patch(section: String, values: Map<String, SettingValue?>): Boolean {
        val editor = preferences(section).edit()
        values.forEach { (key, tagged) ->
            when (val value = tagged?.unpack()) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported preference type")
            }
        }
        return editor.commit()
    }

    override fun stocks() = read(SettingsPolicy.STOCKS).keys.mapNotNull {
        Regex("([1-9][0-9]*)\\.symbols").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()
    }.sorted().map { id ->
        requireNotNull(StockWidgetPreferences(preferences(SettingsPolicy.STOCKS)).load(id)) {
            "A stock widget needs configuration before export"
        }
    }

    override fun clocks() = read(SettingsPolicy.CLOCKS).keys.mapNotNull {
        Regex("clock_([1-9][0-9]*)\\.(city|zone)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()
    }.distinct().sorted().map { id ->
        ClockPreset.from(requireNotNull(WorldClockPreferences(preferences(SettingsPolicy.CLOCKS)).load(id)) {
            "A world clock needs configuration before export"
        })
    }

    companion object {
        fun defaults(): Map<String, Map<String, Any>> = mapOf(
            SettingsPolicy.APP to mapOf(
                "theme" to 0xFF0097A7.toInt(), "nightMode" to 3, "black" to false,
                "autoColor" to true, "systemFont" to false, "textSize" to 1, "sendAsGroup" to true,
                "blockingManager" to 0, "drop" to false,
                "notifAction1" to 5, "notifAction2" to 6, "notifAction3" to 0,
                "qkreply" to (Build.VERSION.SDK_INT < 24), "qkreplyTapDismiss" to true,
                "sendDelay" to 0, "swipeRight" to 1, "swipeLeft" to 1, "autoEmoji" to true,
                "delivery" to false, "signature" to "", "unicode" to false, "mobileOnly" to false,
                "longAsMms" to false, "mmsSize" to 300, "notifications" to true,
                "notification_previews" to 0, "wake" to false, "vibration" to true,
                "drive_mode_read_sms" to true, "drive_mode_read_all_notif" to false,
                "drive_mode_tts_engine" to "android", "drive_mode_voice_reply" to false,
                "drive_mode_reply_timeout" to 30, "drive_mode_stt_max_retries" to 2,
                "drive_mode_max_announcements" to 1, "drive_mode_auto_carplay" to true,
                "drivemode_stt_engine" to "android", "drivemode_vosk_language" to "en-us",
                "drivemode_whisper_language" to "", "drivemode_kokoro_speaker_id" to 0,
                "drivemode_kokoro_speed" to "1.0", "voice_ast_wake_word" to "bubblegum"
            ),
            SettingsPolicy.FAMILY to mapOf("tile_cache_to_disk" to true)
        )
    }
}

class PrivateSettingsRollback(context: Context) : SettingsRollbackFile {
    private val file = AtomicFile(File(context.filesDir, "settings-before-import.json"))
    private val adapter = Moshi.Builder().add(StrictSettingsScalars).build()
        .adapter(SettingsRollback::class.java).serializeNulls().failOnUnknown()

    override fun saveAndVerify(snapshot: SettingsRollback) {
        val json = adapter.toJson(snapshot)
        val output = file.startWrite()
        try {
            output.write(json.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        check(read() == snapshot) { "Could not verify the pre-import snapshot" }
    }

    override fun read(): SettingsRollback = file.openRead().use {
        requireNotNull(adapter.fromJson(readSettingsText(it, SettingsCodec.MAX_BYTES * 4)))
    }
}

fun readSettingsText(input: InputStream, maxBytes: Int = SettingsCodec.MAX_BYTES): String {
    val bytes = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(bytes.size() + count <= maxBytes) { "Settings file is too large" }
        bytes.write(buffer, 0, count)
    }
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
}
