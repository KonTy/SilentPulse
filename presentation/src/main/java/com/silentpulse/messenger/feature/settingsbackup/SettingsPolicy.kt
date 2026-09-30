package com.silentpulse.messenger.feature.settingsbackup

/** Explicit allowlist, not a dump of app preferences. New settings need an intentional privacy review. */
object SettingsPolicy {
    const val APP = "app"
    const val FAMILY = "family"
    const val STOCKS = "stocks"
    const val CLOCKS = "clocks"
    const val PRESETS = "presets"
    const val PRESET_KEY = "imported"
    const val REMEMBERED = "remembered_watchlist"

    private val bool: (Any) -> Boolean = { it is Boolean }
    private fun integer(range: IntRange): (Any) -> Boolean = { it is Int && it in range }
    private fun integers(vararg values: Int): (Any) -> Boolean = { it is Int && it in values }
    private fun choice(vararg values: String): (Any) -> Boolean = { it is String && it in values }
    private fun text(max: Int): (Any) -> Boolean = { it is String && it.length <= max && '\u0000' !in it }

    val rules: Map<String, Map<String, (Any) -> Boolean>> = mapOf(
        APP to buildMap {
            listOf("sendAsGroup", "black", "autoColor", "systemFont", "drop", "qkreply", "qkreplyTapDismiss",
                "autoEmoji", "delivery", "unicode", "mobileOnly", "longAsMms", "notifications", "wake", "vibration",
                "drive_mode_read_sms", "drive_mode_read_all_notif", "drive_mode_voice_reply",
                "drive_mode_auto_carplay").forEach { put(it, bool) }
            put("theme", { it is Int })
            put("nightMode", integer(0..3))
            put("textSize", integer(0..3))
            put("blockingManager", integer(0..3))
            listOf("notifAction1", "notifAction2", "notifAction3", "swipeRight", "swipeLeft").forEach {
                put(it, integer(0..6))
            }
            put("notification_previews", integer(0..2))
            put("sendDelay", integer(0..3))
            put("signature", text(4096))
            put("mmsSize", integers(-1, 0, 100, 200, 300, 600, 1000, 2000))
            put("drive_mode_tts_engine", choice("android", "kokoro"))
            put("drivemode_stt_engine", choice("android", "vosk", "whisper"))
            put("drive_mode_reply_timeout", integers(5, 10, 15, 20, 30))
            put("drive_mode_stt_max_retries", integer(0..5))
            put("drive_mode_max_announcements", integers(1, 2, 3, 5, 999))
            put("drivemode_kokoro_speaker_id", integer(0..10))
            put("drivemode_kokoro_speed", {
                it is String && it.toFloatOrNull()?.let { speed -> speed.isFinite() && speed in 0.5f..2f } == true
            })
            listOf("drivemode_vosk_language", "drivemode_whisper_language").forEach {
                put(it, { value -> value is String && value.length <= 35 &&
                    value.matches(Regex("[a-zA-Z0-9-]*")) })
            }
            put("voice_ast_wake_word", {
                it is String && it.isNotBlank() && it.length <= 80 &&
                    it.all { char -> char.isLetter() || char == ' ' || char == '-' || char == '\'' }
            })
        },
        FAMILY to mapOf("tile_cache_to_disk" to bool)
    )
}
