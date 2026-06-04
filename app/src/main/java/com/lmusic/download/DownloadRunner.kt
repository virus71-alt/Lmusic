package com.lmusic.download

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lmusic.LmusicApp
import com.lmusic.data.Settings
import com.lmusic.R
import com.lmusic.data.DownloadDatabase
import com.lmusic.data.DownloadRecord
import com.lmusic.util.CrashLogger
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronous downloader that runs in whatever thread you start it from.
 *
 * This sidesteps the Android 14+ foreground-service restrictions that silently
 * block FGS starts from AccessibilityService callbacks. We rely on the calling
 * AccessibilityService process staying alive (it does — it's bound to the system).
 *
 * Use [start] from anywhere; it spins its own thread and returns immediately.
 */
object DownloadRunner {

    private const val TAG = "DownloadRunner"
    private const val NOTIF_PROGRESS = 1001
    private const val NOTIF_DONE = 1002
    private const val NOTIF_ERROR = 1003

    // AtomicBoolean so the check-and-set in start() is a single atomic operation,
    // closing the TOCTOU window where two rapid callers both see false and both
    // launch threads that write to the same cache-dir temp file.
    private val _running = AtomicBoolean(false)
    val isRunning: Boolean get() = _running.get()

    fun start(ctx: Context, url: String) {
        if (!_running.compareAndSet(false, true)) {
            Log.d(TAG, "Already downloading — ignoring: $url")
            return
        }
        val appCtx = ctx.applicationContext
        Thread({
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lmusic:download")
            wake.setReferenceCounted(false)
            wake.acquire(10 * 60 * 1000L /* 10 minutes max */)
            try {
                runOnce(appCtx, url)
            } catch (t: Throwable) {
                Log.e(TAG, "Download crashed", t)
                CrashLogger.logNonFatal(appCtx, "DownloadRunner", t)
                showError(appCtx, "Crashed: ${t.javaClass.simpleName}: ${(t.message ?: "").take(200)}", "")
            } finally {
                try { if (wake.isHeld) wake.release() } catch (_: Throwable) {}
                _running.set(false)
            }
        }, "Lmusic-Download").start()
    }

    private fun runOnce(ctx: Context, url: String) {
        // Wi-Fi only check
        if (Settings(ctx).wifiOnly && !isOnWifi(ctx)) {
            showError(ctx, "Wi-Fi only mode is on. Connect to Wi-Fi or change in Settings.", "wifi check")
            return
        }

        showProgress(ctx, "Starting…", 0)

        val downloader = YouTubeDownloader(ctx)
        var stage = "starting"
        val result = downloader.download(
            input = url,
            onProgress = { p -> showProgress(ctx, "$stage  (${p.toInt()}%)", p.toInt()) },
            onStage = { s -> stage = s; showProgress(ctx, "$s  (0%)", 0) }
        )

        // Persist to history
        runBlocking {
            val dao = DownloadDatabase.get(ctx).downloadDao()
            when (result) {
                is DownloadResult.Success -> {
                    dao.insert(
                        DownloadRecord(
                            youtubeUrl = url,
                            title = result.displayTitle,
                            channel = result.channel,
                            durationSeconds = result.durationSeconds,
                            thumbnailPath = result.thumbnailPath,
                            fileUri = result.fileUri
                        )
                    )
                    showDone(ctx, result.displayTitle)
                    Log.d(TAG, "Done: ${result.filename}")
                }
                is DownloadResult.Failure -> {
                    dao.insert(
                        DownloadRecord(
                            youtubeUrl = url,
                            title = extractTitle(url),
                            status = DownloadRecord.STATUS_FAILED,
                            errorMessage = result.error.take(300)
                        )
                    )
                    showError(ctx, result.error, stage)
                    Log.e(TAG, "Failed at '$stage': ${result.error}")
                }
            }
        }

        // Clear the persistent progress notification
        NotificationManagerCompat.from(ctx).cancel(NOTIF_PROGRESS)
    }

    // -----------------------------------------------------------------------
    // Notifications
    // -----------------------------------------------------------------------

    /** True iff the user has not disabled download notifications in Profile. */
    private fun notifsAllowed(ctx: Context): Boolean =
        Settings(ctx).downloadNotifications

    private fun showProgress(ctx: Context, text: String, progress: Int) {
        if (!notifsAllowed(ctx)) return
        val notif: Notification = NotificationCompat.Builder(ctx, LmusicApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Lmusic — Downloading")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_PROGRESS, notif) }
        catch (_: SecurityException) {}
    }

    private fun showDone(ctx: Context, filename: String) {
        if (!notifsAllowed(ctx)) return
        val notif = NotificationCompat.Builder(ctx, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloaded!")
            .setContentText(filename)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_DONE, notif) }
        catch (_: SecurityException) {}
    }

    private fun showError(ctx: Context, error: String, stage: String) {
        if (!notifsAllowed(ctx)) return
        val notif = NotificationCompat.Builder(ctx, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Lmusic — download failed")
            .setContentText(error.lineSequence().firstOrNull() ?: error)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Stage: ${stage.ifBlank { "?" }}\n\n$error")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_ERROR, notif) }
        catch (_: SecurityException) {}
    }

    private fun isOnWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager? ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun extractTitle(url: String): String {
        if (url.startsWith("ytsearch")) return url.substringAfter(":").take(80)
        val v = Regex("""[?&]v=([\w-]{11})""").find(url)?.groupValues?.get(1)
        return if (v != null) "YouTube video ($v)" else "Unknown track"
    }
}
