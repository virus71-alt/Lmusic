package com.lmusic.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lmusic.LmusicApp
import com.lmusic.R
import com.lmusic.data.DownloadDatabase
import com.lmusic.data.DownloadRecord
import com.lmusic.data.Settings
import com.lmusic.download.DownloadResult
import com.lmusic.download.YouTubeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val EXTRA_URL = "url"
        private const val ACTION_CANCEL = "com.lmusic.action.CANCEL"
        private const val NOTIF_ID = 1001

        @Volatile var isDownloading = false
        @Volatile var cancelRequested = false

        fun startDownload(ctx: Context, url: String) {
            if (isDownloading) {
                Log.d(TAG, "Already downloading — ignoring: $url")
                return
            }
            val intent = Intent(ctx, DownloadService::class.java).putExtra(EXTRA_URL, url)
            try {
                ctx.startForegroundService(intent)
            } catch (t: Throwable) {
                // Surface failure to start the service so we can see it instead of silent death
                com.lmusic.util.CrashLogger.logNonFatal(ctx.applicationContext, "startForegroundService", t)
                postBootstrapErrorNotification(ctx, t)
            }
        }

        /** Visible alarm when startForegroundService itself throws (Android 12+ ForegroundServiceStartNotAllowedException etc.) */
        private fun postBootstrapErrorNotification(ctx: Context, t: Throwable) {
            val notif = NotificationCompat.Builder(ctx, LmusicApp.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Lmusic — service blocked")
                .setContentText(t.javaClass.simpleName + ": " + (t.message ?: "").take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "startForegroundService threw:\n${t.javaClass.name}\n${t.message ?: ""}"
                ))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(9099, notif) }
            catch (_: SecurityException) {}
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Cancel action
        if (intent?.action == ACTION_CANCEL) {
            Log.d(TAG, "Cancel requested")
            cancelRequested = true
            currentJob?.cancel()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIF_ID, buildProgressNotification("Starting download…", 0))
        } catch (t: Throwable) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException if context isn't permitted
            com.lmusic.util.CrashLogger.logNonFatal(applicationContext, "startForeground", t)
            showErrorNotification(
                "Can't start service: ${t.message?.take(120) ?: t.javaClass.simpleName}"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            // No URL (e.g. system restarted the service with a null intent) — nothing to do.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (isDownloading) {
            // A download is already in progress — ignore this duplicate start without
            // calling stopSelf(), which would kill the running download.
            Log.d(TAG, "Already downloading — ignoring duplicate start for: $url")
            return START_NOT_STICKY
        }

        // Wi-Fi only check
        if (Settings(this).wifiOnly && !isOnWifi()) {
            showStatusNotification(
                title = "Wi-Fi only mode",
                text = "Connect to Wi-Fi to download (change in Settings)"
            )
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }

        isDownloading = true
        cancelRequested = false
        currentJob = scope.launch { runDownload(url) }

        return START_NOT_STICKY
    }

    @Volatile private var currentStage: String = "Starting…"
    @Volatile private var currentProgress: Float = 0f

    private suspend fun runDownload(url: String) {
        try {
            val db = DownloadDatabase.get(applicationContext).downloadDao()
            val downloader = YouTubeDownloader(applicationContext)

            val result = downloader.download(
                input = url,
                onProgress = { progress ->
                    if (cancelRequested) throw InterruptedException("Cancelled")
                    currentProgress = progress
                    updateStageNotification()
                },
                onStage = { stage ->
                    Log.d(TAG, "Stage: $stage")
                    currentStage = stage
                    updateStageNotification()
                }
            )

            if (cancelRequested) {
                Log.d(TAG, "Download cancelled by user")
                return
            }

            when (result) {
                is DownloadResult.Success -> {
                    showDoneNotification(result.displayTitle)
                    db.insert(
                        DownloadRecord(
                            youtubeUrl = url,
                            title = result.displayTitle,
                            channel = result.channel,
                            durationSeconds = result.durationSeconds,
                            thumbnailPath = result.thumbnailPath,
                            fileUri = result.fileUri
                        )
                    )
                    Log.d(TAG, "Download complete: ${result.filename}")
                }
                is DownloadResult.Failure -> {
                    showErrorNotification(result.error)
                    Log.e(TAG, "Download failed: ${result.error}")
                    // Save a failed record so the user can retry from history
                    db.insert(
                        DownloadRecord(
                            youtubeUrl = url,
                            title = extractTitleFromUrl(url),
                            status = DownloadRecord.STATUS_FAILED,
                            errorMessage = result.error.take(300)
                        )
                    )
                }
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "Interrupted (cancel)")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected crash in runDownload", e)
            showErrorNotification("Unexpected error: ${e.message?.take(120)}")
        } finally {
            isDownloading = false
            cancelRequested = false
            // Use NonCancellable so stopForeground/stopSelf always run even when the
            // scope has been cancelled (e.g. onDestroy calling scope.cancel()).
            // Without NonCancellable, withContext on a cancelled scope throws
            // CancellationException immediately, leaving the foreground notification stuck.
            withContext(NonCancellable + Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun buildProgressNotification(text: String, progress: Int): Notification {
        val cancelIntent = Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL }
        val cancelPI = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, LmusicApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Lmusic — Downloading")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_delete, "Cancel", cancelPI)
            .build()
    }

    private fun updateStageNotification() {
        val label = "$currentStage  (${currentProgress.toInt()}%)"
        val notif = buildProgressNotification(label, currentProgress.toInt())
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, notif) }
        catch (_: SecurityException) {}
    }

    private fun showDoneNotification(filename: String) {
        val notif = NotificationCompat.Builder(this, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloaded!")
            .setContentText(filename)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID + 1, notif) }
        catch (_: SecurityException) {}
    }

    private fun showErrorNotification(error: String) {
        val notif = NotificationCompat.Builder(this, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Lmusic — download failed")
            .setContentText(error.lineSequence().firstOrNull() ?: error)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Stage at failure: $currentStage\n\n$error"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID + 2, notif) }
        catch (_: SecurityException) {}
    }

    private fun showStatusNotification(title: String, text: String) {
        val notif = NotificationCompat.Builder(this, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID + 3, notif) }
        catch (_: SecurityException) {}
    }

    /** Best-effort readable title from a URL or search query string. */
    private fun extractTitleFromUrl(url: String): String {
        if (url.startsWith("ytsearch")) return url.substringAfter(":").take(80)
        val vParam = Regex("""[?&]v=([\w-]{11})""").find(url)?.groupValues?.get(1)
        return if (vParam != null) "YouTube video ($vParam)" else "Unknown track"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
