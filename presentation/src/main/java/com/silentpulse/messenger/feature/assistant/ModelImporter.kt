package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.net.Uri
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Imports offline AI model archives (zip files) from the user's Downloads or
 * any content URI into the app's private external storage directory so they
 * can be read without any storage permission on Android 10+.
 *
 * Usage:
 *   val path = ModelImporter.importZip(context, zipUri, "vosk")
 *   // → /sdcard/Android/data/com.silentpulse.messenger/files/vosk/vosk-model-small-en-us/
 */
object ModelImporter {

    /**
     * Extract a zip archive from [zipUri] into [context].getExternalFilesDir(null)/[subDir].
     * Each zip is extracted into its own sub-directory named after the zip filename
     * (minus the .zip extension).
     *
     * @return Absolute path to the extracted directory, or null on failure.
     */
    fun importZip(context: Context, zipUri: Uri, subDir: String): String? {
        val resolver = context.contentResolver

        // Determine destination name from the URI display name
        val displayName = queryDisplayName(context, zipUri)
            ?: zipUri.lastPathSegment?.substringAfterLast('/')
            ?: "model"
        val modelName = displayName.removeSuffix(".zip")

        val baseDir = context.getExternalFilesDir(null)
            ?: context.filesDir
        val destDir = File(baseDir, "$subDir/$modelName")

        return try {
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            resolver.openInputStream(zipUri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    var entry = zip.nextEntry
                    var fileCount = 0
                    while (entry != null) {
                        val name = entry.name
                        // Strip the top-level folder if the zip has one
                        val relative = if (name.contains('/') && name.indexOf('/') == name.lastIndexOf('/') - 1 && !entry.isDirectory) {
                            name.substringAfter('/')
                        } else {
                            name
                        }
                        if (relative.isBlank()) {
                            entry = zip.nextEntry
                            continue
                        }
                        val outFile = File(destDir, relative)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                zip.copyTo(out, bufferSize = 65_536)
                            }
                            fileCount++
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    Timber.d("ModelImporter: extracted $fileCount files to ${destDir.absolutePath}")
                }
            }
            destDir.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "ModelImporter: failed to import zip from $zipUri")
            destDir.deleteRecursively()
            null
        }
    }

    /**
     * List all previously imported models in [subDir] (each is a subdirectory).
     * Returns list of (displayName, absolutePath) pairs sorted by name.
     */
    fun listModels(context: Context, subDir: String): List<Pair<String, String>> {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, subDir)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.map { Pair(it.name, it.absolutePath) }
            ?: emptyList()
    }

    /**
     * Delete an imported model directory.
     */
    fun deleteModel(path: String): Boolean =
        File(path).takeIf { it.isDirectory }?.deleteRecursively() ?: false

    // ── Content resolver helpers ─────────────────────────────────────────────

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) { null }
}
