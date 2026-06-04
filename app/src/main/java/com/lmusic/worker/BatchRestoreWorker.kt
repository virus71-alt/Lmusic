package com.lmusic.worker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lmusic.LmusicApp
import com.lmusic.R
import com.lmusic.data.BackupManager
import com.lmusic.data.DownloadDatabase
import com.lmusic.data.DownloadRecord
import com.lmusic.download.DownloadResult
import com.lmusic.download.YouTubeDownloader

/**
 * Downloads every track in the pending restore queue one by one.
 *
 * Tracks that are already in the library (same URL, status = ok) are skipped.
 * Failed tracks are recorded in the DB as STATUS_FAILED so the user can retry them
 * from the history screen.
 *
 * Use [enqueue] to start a restore after writing the queue via [BackupManager.writeRestoreQueue].
 */
class BatchRestoreWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME = "batch_restore"
        private const val NOTIF_PROGRESS_ID = 2001
        private const val NOTIF_DONE_ID     = 2002

        /**
         * Enqueue a single restore run. If one is already pending/running this is a no-op
         * (KEEP policy). Call after [BackupManager.writeRestoreQueue].
         */
        fun enqueue(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BatchRestoreWorker>().build()
            )
        }
    }

    @SuppressLint("InlinedApi")
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notif = buildProgressNotif("Preparing restore…", 0, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIF_PROGRESS_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIF_PROGRESS_ID, notif)
        }
    }

    override suspend fun doWork(): Result {
        val queue = BackupManager.readRestoreQueue(ctx)
        if (queue.isEmpty()) {
            BackupManager.clearRestoreQueue(ctx)
            return Result.success()
        }

        setForeground(getForegroundInfo())

        val dao     = DownloadDatabase.get(ctx).downloadDao()
        val total   = queue.size
        var done    = 0
        var skipped = 0
        var failed  = 0

        for (entry in queue) {
            if (isStopped) break     // WorkManager asked us to stop (e.g. constraints violated)

            // Skip tracks that already downloaded successfully
            if (dao.countByUrl(entry.youtubeUrl) > 0) {
                skipped++
                done++
                postProgressNotif(done, total, entry.title)
                continue
            }

            val downloader = YouTubeDownloader(ctx)
            when (val result = downloader.download(
                input    = entry.youtubeUrl,
                onProgress = { /* progress is shown via the notification counter, not a bar */ },
                onStage  = {}
            )) {
                is DownloadResult.Success -> {
                    dao.insert(
                        DownloadRecord(
                            youtubeUrl      = entry.youtubeUrl,
                            title           = result.displayTitle,
                            channel         = result.channel,
                            durationSeconds = result.durationSeconds,
                            thumbnailPath   = result.thumbnailPath,
                            fileUri         = result.fileUri
                        )
                    )
                }
                is DownloadResult.Failure -> {
                    failed++
                    dao.insert(
                        DownloadRecord(
                            youtubeUrl    = entry.youtubeUrl,
                            title         = entry.title,
                            status        = DownloadRecord.STATUS_FAILED,
                            errorMessage  = result.error.take(300)
                        )
                    )
                }
            }

            done++
            postProgressNotif(done, total, entry.title)
        }

        BackupManager.clearRestoreQueue(ctx)

        // Dismiss progress, show summary
        try { NotificationManagerCompat.from(ctx).cancel(NOTIF_PROGRESS_ID) }
        catch (_: Exception) {}
        postDoneNotif(done - failed - skipped, skipped, failed)

        return Result.success()
    }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

    private fun buildProgressNotif(text: String, progress: Int, max: Int) =
        NotificationCompat.Builder(ctx, LmusicApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Lmusic — Restoring library")
            .setContentText(text)
            .setProgress(max.coerceAtLeast(1), progress, max == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun postProgressNotif(done: Int, total: Int, currentTitle: String) {
        val notif = buildProgressNotif("$done / $total  ·  $currentTitle", done, total)
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_PROGRESS_ID, notif) }
        catch (_: SecurityException) {}
    }

    private fun postDoneNotif(restored: Int, skipped: Int, failed: Int) {
        val msg = buildString {
            append("Restored $restored track${if (restored != 1) "s" else ""}.")
            if (skipped > 0) append("  $skipped already present.")
            if (failed  > 0) append("  $failed failed — check history to retry.")
        }
        val notif = NotificationCompat.Builder(ctx, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Lmusic — Restore complete")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_DONE_ID, notif) }
        catch (_: SecurityException) {}
    }
}
