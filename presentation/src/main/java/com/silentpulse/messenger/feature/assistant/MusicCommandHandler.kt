package com.silentpulse.messenger.feature.assistant

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent

/**
 * Handles voice commands for local music playback via VLC, and audiobook
 * playback via the Voice audiobook app (de.ph1b.audiobook).
 *
 * ## Music trigger phrases
 *   - "play music [optional: artist / title]"
 *   - "play song [optional: artist / title]"
 *
 * ## Audiobook trigger phrases
 *   - "listen to book [optional: title]"
 *   - "play book [optional: title]"
 *   - "play audiobook [optional: title]"
 *   - "open audiobook" / "open voice" / "read book"
 *
 * ## Resume phrases (any active media session) 
 *   - "resume" / "resume music" / "resume playing" / "continue playing"
 *   - "unpause" / "resume book" / "continue book"
 *
 * ## How it works
 *   1. Strip trigger keywords to get a free-form search query.
 *   2. Query [MediaStore.Audio.Media.EXTERNAL_CONTENT_URI] using LIKE on TITLE / ARTIST.
 *   3. Music → launch VLC (org.videolan.vlc) with ACTION_VIEW + content URI.
 *   4. Audiobook → launch Voice app (de.ph1b.audiobook). If a title is given,
 *      search MediaStore for .m4b / .mp3 files and pass via ACTION_VIEW.
 *      Voice will open that file and remember position on next launch.
 *   5. "Resume" → KEYCODE_MEDIA_PLAY dispatched to the active media session.
 *
 * ## Permissions needed in the manifest
 *   - android.permission.READ_MEDIA_AUDIO      (API 33+)
 *   - android.permission.READ_EXTERNAL_STORAGE (API <= 32, maxSdkVersion=32)
 */
class MusicCommandHandler(private val context: Context) {

    companion object {
        private const val TAG          = "MusicCmd"
        const val VLC_PACKAGE          = "org.videolan.vlc"
        const val VOICE_PACKAGE        = "de.ph1b.audiobook"
        private val TRIGGER_WORDS      = listOf("play music", "play song", "play a song", "play the song", "play some")
        private val BOOK_TRIGGERS      = listOf(
            "listen to book", "listen to the book",
            "play book", "play the book",
            "play audiobook", "play the audiobook",
            "read book", "read the book",
            "open audiobook", "open voice app", "open voice"
        )
        private val RESUME_PHRASES     = listOf(
            "resume music", "resume playing", "resume the music",
            "continue playing", "continue music",
            "unpause music", "unpause",
            "resume book", "resume the book", "continue book", "continue the book",
            "resume audiobook"
        )
        /** Audio file extensions Voice app handles well. */
        private val AUDIOBOOK_EXTS     = setOf("m4b", "mp3", "ogg", "flac", "opus", "aac")
    }

    data class Track(val title: String, val artist: String, val uri: android.net.Uri)

    // ── Public interface ──────────────────────────────────────────────────────

    /** Returns true if the (lowercased) command is a music play command. */
    fun isMusicCommand(c: String): Boolean =
        TRIGGER_WORDS.any { c.contains(it) } || c.startsWith("play ")

    /** Returns true if the command is an audiobook command. */
    fun isBookCommand(c: String): Boolean =
        BOOK_TRIGGERS.any { c.contains(it) || c == it.trim() }

    /** Returns true if the command means "resume whatever was playing". */
    fun isResumeCommand(c: String): Boolean =
        RESUME_PHRASES.any { c == it || c.contains(it) } ||
        c.trim() == "resume" || c.trim() == "unpause"

    /**
     * Sends a global PLAY key event so any active media session resumes.
     * Works for VLC, Voice, Audible, etc. — no special permissions.
     */
    fun resumeMedia() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
        val up   = KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_MEDIA_PLAY)
        am.dispatchMediaKeyEvent(down)
        am.dispatchMediaKeyEvent(up)
        Log.d(TAG, "Dispatched KEYCODE_MEDIA_PLAY")
    }

    /**
     * Handle "play music ..." — searches MediaStore and opens in VLC.
     */
    fun handlePlay(command: String, onResult: (String) -> Unit) {
        var query = command
        for (trigger in TRIGGER_WORDS.sortedByDescending { it.length }) {
            query = query.replace(trigger, "").trim()
        }
        query = query.replace(Regex("^[,\\s]+"), "").trim()

        if (query.isEmpty()) {
            resumeMedia()
            onResult("Resuming media.")
            return
        }
        Log.d(TAG, "Music search query: \"$query\"")

        val track = searchMediaStore(query, audiobookOnly = false)
        if (track == null) {
            onResult("I couldn't find \"$query\" in your music library.")
            return
        }

        Log.d(TAG, "Found track: \"${track.title}\" by \"${track.artist}\" → ${track.uri}")
        launchWithPendingIntent(track.uri, VLC_PACKAGE)
        val artistPart = if (track.artist.isNotEmpty()) " by ${track.artist}" else ""
        onResult("Playing ${track.title}$artistPart.")
    }

    /**
     * Handle "listen to book ..." — searches MediaStore for audio files and
     * opens them in the Voice audiobook app. If no title is given, just opens
     * Voice so it resumes the last book.
     */
    fun handleBook(command: String, onResult: (String) -> Unit) {
        var query = command
        for (trigger in BOOK_TRIGGERS.sortedByDescending { it.length }) {
            query = query.replace(trigger, "").trim()
        }
        query = query.replace(Regex("^[,\\s]+"), "").trim()

        if (!isVoiceInstalled()) {
            // Fall back to VLC for audiobooks if Voice isn't installed
            if (query.isEmpty()) {
                resumeMedia()
                onResult("Resuming audio.")
            } else {
                val track = searchMediaStore(query, audiobookOnly = false)
                if (track != null) {
                    launchWithPendingIntent(track.uri, VLC_PACKAGE)
                    onResult("Opening ${track.title} in VLC. Install the Voice audiobook app for a better audiobook experience.")
                } else {
                    onResult("Voice audiobook app is not installed and I couldn't find \"$query\".")
                }
            }
            return
        }

        if (query.isEmpty()) {
            // No title — just open Voice; it will resume the last book
            launchApp(VOICE_PACKAGE)
            onResult("Opening Voice. It will resume your last book.")
            return
        }

        Log.d(TAG, "Book search query: \"$query\"")
        val track = searchMediaStore(query, audiobookOnly = true)
            ?: searchMediaStore(query, audiobookOnly = false)  // broader fallback

        if (track != null) {
            Log.d(TAG, "Found book: \"${track.title}\" → ${track.uri}")
            launchWithPendingIntent(track.uri, VOICE_PACKAGE)
            onResult("Opening ${track.title} in Voice.")
        } else {
            // No match — open Voice and let the user pick
            launchApp(VOICE_PACKAGE)
            onResult("I couldn't find \"$query\" in your library. Opening Voice so you can choose.")
        }
    }

    fun isVlcInstalled(): Boolean  = isAppInstalled(VLC_PACKAGE)
    fun isVoiceInstalled(): Boolean = isAppInstalled(VOICE_PACKAGE)

    private fun isAppInstalled(pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    // ── MediaStore search ─────────────────────────────────────────────────────

    private fun searchMediaStore(query: String, audiobookOnly: Boolean): Track? {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA   // file path — to filter by extension
        )
        val clean = query.lowercase().trim()

        // Helper that optionally filters by audiobook extension
        fun tryQuery(selection: String, args: Array<String>): Track? {
            val cursor = try {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, args,
                    "${MediaStore.Audio.Media.TITLE} ASC"
                )
            } catch (e: Exception) { Log.e(TAG, "MediaStore query error", e); null }
                ?: return null
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id     = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    val title  = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)) ?: continue
                    val artist = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                        ?.replace("<unknown>", "")?.trim() ?: ""
                    val path   = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)) ?: ""
                    if (audiobookOnly) {
                        val ext = path.substringAfterLast('.').lowercase()
                        if (ext !in AUDIOBOOK_EXTS) continue
                    }
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    return Track(title, artist, uri)
                }
            }
            return null
        }

        // 1. Full query as title substring
        tryQuery("${MediaStore.Audio.Media.TITLE} LIKE ?", arrayOf("%$clean%"))?.let { return it }
        // 2. Full query as artist substring
        tryQuery("${MediaStore.Audio.Media.ARTIST} LIKE ?", arrayOf("%$clean%"))?.let { return it }
        // 3. Word-by-word against title
        val words = clean.split(Regex("[,\\s]+")).filter { it.length >= 3 }
        for (word in words) {
            tryQuery("${MediaStore.Audio.Media.TITLE} LIKE ?", arrayOf("%$word%"))?.let { return it }
        }
        // 4. Word-by-word against artist
        for (word in words) {
            tryQuery("${MediaStore.Audio.Media.ARTIST} LIKE ?", arrayOf("%$word%"))?.let { return it }
        }
        return null
    }

    // ── App launch helpers ────────────────────────────────────────────────────

    /** Launch a specific file URI in [targetPkg] using a BAL-safe PendingIntent. */
    private fun launchWithPendingIntent(uri: android.net.Uri, targetPkg: String) {
        val installed = isAppInstalled(targetPkg)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (installed) setPackage(targetPkg)
        }
        try {
            val pi = PendingIntent.getActivity(
                context, 55, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
            val opts = ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            pi.send(context, 0, null, null, null, null, opts.toBundle())
        } catch (e: Exception) {
            Log.e(TAG, "launchWithPendingIntent failed for $targetPkg: ${e.message}", e)
        }
    }

    /** Launch an app's main launcher activity. */
    private fun launchApp(pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        try {
            val pi = PendingIntent.getActivity(
                context, 56, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
            val opts = ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            pi.send(context, 0, null, null, null, null, opts.toBundle())
        } catch (e: Exception) {
            Log.e(TAG, "launchApp failed for $pkg: ${e.message}", e)
        }
    }
}
