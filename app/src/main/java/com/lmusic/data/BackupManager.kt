package com.lmusic.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles exporting the Lmusic download history to a .lmusicbackup file and reading it back.
 *
 * Backup format (JSON):
 * {
 *   "version": 1,
 *   "exportedAt": <epoch-ms>,
 *   "trackCount": N,
 *   "tracks": [
 *     { "youtubeUrl": "...", "title": "...", "channel": "...",
 *       "durationSeconds": N, "downloadedAt": N }
 *   ]
 * }
 *
 * The "tracks" array is all we need to re-download the library on a new device.
 */
object BackupManager {

    const val BACKUP_VERSION = 1
    const val FILE_EXT = "lmusicbackup"
    const val RESTORE_QUEUE_FILE = "pending_restore_queue.json"

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    data class BackupEntry(
        val youtubeUrl: String,
        val title: String,
        val channel: String?,
        val durationSeconds: Long,
        val downloadedAt: Long
    )

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    /**
     * Serialises [records] to a JSON file inside `filesDir/backups/`.
     * Returns a FileProvider content:// URI suitable for sharing via an Intent.
     */
    fun export(ctx: Context, records: List<DownloadRecord>): Uri {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(ctx.filesDir, "backups/lmusic_backup_$ts.$FILE_EXT")
        outFile.parentFile?.mkdirs()

        val tracksArray = JSONArray()
        for (r in records) {
            if (r.youtubeUrl.isBlank()) continue
            tracksArray.put(
                JSONObject()
                    .put("youtubeUrl", r.youtubeUrl)
                    .put("title", r.title)
                    .put("channel", r.channel ?: "")
                    .put("durationSeconds", r.durationSeconds)
                    .put("downloadedAt", r.downloadedAt)
            )
        }

        val root = JSONObject()
            .put("version", BACKUP_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("trackCount", tracksArray.length())
            .put("tracks", tracksArray)

        outFile.writeText(root.toString(2))   // pretty-printed for readability

        return FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            outFile
        )
    }

    // -------------------------------------------------------------------------
    // Parse a backup file
    // -------------------------------------------------------------------------

    /**
     * Reads the backup JSON from [uri] (content:// or file://).
     * Returns null on IO error or malformed JSON; returns an empty list if the
     * file is valid but contains no tracks.
     */
    fun parseBackup(ctx: Context, uri: Uri): List<BackupEntry>? {
        return try {
            val text = ctx.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return null

            val root = JSONObject(text)
            val array = root.getJSONArray("tracks")
            (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val o = array.getJSONObject(i)
                    val url = o.getString("youtubeUrl")
                    if (url.isBlank()) return@mapNotNull null
                    BackupEntry(
                        youtubeUrl = url,
                        title = o.optString("title", "Unknown"),
                        channel = o.optString("channel").takeIf { it.isNotBlank() },
                        durationSeconds = o.optLong("durationSeconds", 0L),
                        downloadedAt = o.optLong("downloadedAt", 0L)
                    )
                }.getOrNull()
            }
        } catch (e: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // Restore queue — persisted as a JSON file so it survives process death
    // -------------------------------------------------------------------------

    /**
     * Saves [entries] to the restore queue file so [BatchRestoreWorker] can pick them up.
     * Replaces any existing queue.
     */
    fun writeRestoreQueue(ctx: Context, entries: List<BackupEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("youtubeUrl", e.youtubeUrl)
                    .put("title", e.title)
                    .put("channel", e.channel ?: "")
                    .put("durationSeconds", e.durationSeconds)
                    .put("downloadedAt", e.downloadedAt)
            )
        }
        restoreQueueFile(ctx).writeText(arr.toString())
    }

    fun readRestoreQueue(ctx: Context): List<BackupEntry> {
        val f = restoreQueueFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    val url = o.getString("youtubeUrl")
                    if (url.isBlank()) return@mapNotNull null
                    BackupEntry(
                        youtubeUrl = url,
                        title = o.optString("title", "Unknown"),
                        channel = o.optString("channel").takeIf { it.isNotBlank() },
                        durationSeconds = o.optLong("durationSeconds", 0L),
                        downloadedAt = o.optLong("downloadedAt", 0L)
                    )
                }.getOrNull()
            }
        } catch (e: Exception) { emptyList() }
    }

    fun clearRestoreQueue(ctx: Context) {
        restoreQueueFile(ctx).delete()
    }

    fun hasPendingRestore(ctx: Context): Boolean = restoreQueueFile(ctx).exists()

    private fun restoreQueueFile(ctx: Context): File =
        File(ctx.filesDir, RESTORE_QUEUE_FILE)
}
