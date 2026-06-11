package com.lmusic.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lmusic.data.DownloadDatabase
import com.lmusic.data.DownloadRecord
import com.lmusic.data.Settings
import com.lmusic.plugin.PluginManager
import com.lmusic.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodically scans Lmusic download records and fetches missing thumbnails.
 *
 * A thumbnail is considered "missing" when:
 *   - [DownloadRecord.thumbnailPath] is null, or
 *   - the file at that path no longer exists on disk.
 *
 * For each such record that has a [DownloadRecord.youtubeUrl], the worker
 * re-extracts the thumbnail URL via the appropriate plugin and downloads it.
 */
class ThumbnailSyncWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "ThumbnailSync"
        const val WORK_NAME = "thumbnail_sync"

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<ThumbnailSyncWorker>(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!Settings(ctx).autoSyncThumbnails) return@withContext Result.success()

        val dao = DownloadDatabase.get(ctx).downloadDao()
        val records = dao.getAll()

        var synced = 0
        for (record in records) {
            if (isStopped) break
            if (record.status != DownloadRecord.STATUS_OK) continue
            if (record.youtubeUrl.isBlank()) continue

            val existing = record.thumbnailPath
            if (existing != null && File(existing).exists()) continue

            try {
                val plugin = PluginManager.findFor(record.youtubeUrl) ?: continue
                val extraction = plugin.extract(record.youtubeUrl)
                val thumbUrl = extraction.thumbnailUrl ?: continue
                val path = downloadThumbnail(thumbUrl) ?: continue
                dao.updateThumbnailPath(record.id, path)
                synced++
                Log.d(TAG, "Synced thumbnail for '${record.title}'")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync thumbnail for '${record.title}': ${e.message}")
                CrashLogger.logNonFatal(ctx, TAG, e)
            }
        }

        Log.i(TAG, "Thumbnail sync complete — $synced updated")
        Result.success()
    }

    private fun downloadThumbnail(url: String): String? {
        val thumbDir = File(ctx.filesDir, "thumbnails").apply { mkdirs() }
        val outFile = File(thumbDir, "${url.hashCode()}.jpg")
        if (outFile.exists()) return outFile.absolutePath

        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            outFile.outputStream().use { body.byteStream().copyTo(it) }
        }
        return outFile.absolutePath
    }
}
