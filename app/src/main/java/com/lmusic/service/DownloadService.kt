package com.lmusic.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lmusic.LmusicApp
import com.lmusic.R
import com.lmusic.data.DownloadDatabase
import com.lmusic.data.DownloadRecord
import com.lmusic.download.DownloadResult
import com.lmusic.download.YouTubeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val EXTRA_URL = "url"
        private const val NOTIF_ID = 1001

        @Volatile
        var isDownloading = false

        fun startDownload(ctx: Context, url: String) {
            if (isDownloading) {
                Log.d(TAG, "Already downloading — ignoring: $url")
                return
            }
            val intent = Intent(ctx, DownloadService::class.java).putExtra(EXTRA_URL, url)
            ctx.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground MUST be called within 5 seconds — do it first, before any other work
        startForeground(NOTIF_ID, buildProgressNotification("Starting download…", 0))

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null || isDownloading) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        isDownloading = true
        scope.launch { runDownload(url) }

        return START_NOT_STICKY
    }

    private suspend fun runDownload(url: String) {
        try {
            val db = DownloadDatabase.get(applicationContext).downloadDao()
            val downloader = YouTubeDownloader(applicationContext)

            val result = downloader.download(url) { progress ->
                updateProgressNotification(progress)
            }

            when (result) {
                is DownloadResult.Success -> {
                    showDoneNotification(result.filename)
                    db.insert(DownloadRecord(youtubeUrl = url, title = result.filename))
                    Log.d(TAG, "Download complete: ${result.filename}")
                }
                is DownloadResult.Failure -> {
                    showErrorNotification(result.error)
                    Log.e(TAG, "Download failed: ${result.error}")
                }
            }
        } finally {
            isDownloading = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildProgressNotification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, LmusicApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Lmusic — Downloading")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateProgressNotification(progress: Float) {
        val notif = buildProgressNotification("${progress.toInt()}%", progress.toInt())
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notif)
        } catch (_: SecurityException) {}
    }

    private fun showDoneNotification(filename: String) {
        val notif = NotificationCompat.Builder(this, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloaded!")
            .setContentText(filename)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID + 1, notif)
        } catch (_: SecurityException) {}
    }

    private fun showErrorNotification(error: String) {
        val notif = NotificationCompat.Builder(this, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Download failed")
            .setContentText(error.lineSequence().firstOrNull() ?: error)
            // Expandable full text so diagnostic info is visible
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID + 2, notif)
        } catch (_: SecurityException) {}
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
