package com.lmusic.download

import android.app.Notification
import android.content.Context
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synchronous downloader that runs in whatever thread you start it from.
 *
 * This sidesteps the Android 14+ foreground-service restrictions that silently
 * block FGS starts from AccessibilityService callbacks. We rely on the calling
 * AccessibilityService process staying alive (it does — it's bound to the system).
 *
 * Supports multiple simultaneous downloads: up to [MAX_CONCURRENT] run in
 * parallel, additional requests are queued and started as slots free up.
 * The same URL is de-duplicated while it is queued or in flight.
 *
 * Use [start] from anywhere; it returns immediately.
 */
object DownloadRunner {

    private const val TAG = "DownloadRunner"
    private const val MAX_CONCURRENT = 3

    /** Base for per-download notification IDs; each download claims 3 slots. */
    private const val NOTIF_BASE = 1100

    private val activeCount = AtomicInteger(0)
    private val queue = ConcurrentLinkedQueue<String>()
    private val urlsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val notifSeq = AtomicInteger(0)

    val isRunning: Boolean get() = activeCount.get() > 0

    fun start(ctx: Context, url: String) {
        if (!urlsInFlight.add(url)) {
            Log.d(TAG, "Already queued/downloading — ignoring duplicate: $url")
            return
        }
        queue.add(url)
        fillSlots(ctx.applicationContext)
    }

    /** Launch worker threads until all slots are busy or the queue is drained. */
    private fun fillSlots(appCtx: Context) {
        while (true) {
            val cur = activeCount.get()
            if (cur >= MAX_CONCURRENT) return
            if (!activeCount.compareAndSet(cur, cur + 1)) continue
            val url = queue.poll()
            if (url == null) {
                activeCount.decrementAndGet()
                return
            }
            launchWorker(appCtx, url)
        }
    }

    private fun launchWorker(appCtx: Context, url: String) {
        // Cycle through 100 ID groups so concurrent + recent notifications never clash.
        val notifId = NOTIF_BASE + (notifSeq.getAndIncrement() % 100) * 3
        Thread({
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lmusic:download")
            wake.setReferenceCounted(false)
            wake.acquire(10 * 60 * 1000L /* 10 minutes max */)
            try {
                runOnce(appCtx, url, notifId)
            } catch (t: Throwable) {
                Log.e(TAG, "Download crashed", t)
                CrashLogger.logNonFatal(appCtx, "DownloadRunner", t)
                showError(appCtx, notifId, "Crashed: ${t.javaClass.simpleName}: ${(t.message ?: "").take(200)}", "")
            } finally {
                try { if (wake.isHeld) wake.release() } catch (_: Throwable) {}
                urlsInFlight.remove(url)
                activeCount.decrementAndGet()
                // A slot just freed up — pull the next queued URL if any.
                fillSlots(appCtx)
            }
        }, "Lmusic-Download").start()
    }

    private fun runOnce(ctx: Context, url: String, notifId: Int) {
        // Wi-Fi only check
        if (Settings(ctx).wifiOnly && !isOnWifi(ctx)) {
            showError(ctx, notifId, "Wi-Fi only mode is on. Connect to Wi-Fi or change in Settings.", "wifi check")
            return
        }

        showProgress(ctx, notifId, "Starting…", 0)

        val downloader = YouTubeDownloader(ctx)
        var stage = "starting"
        val result = downloader.download(
            input = url,
            onProgress = { p -> showProgress(ctx, notifId, "$stage  (${p.toInt()}%)", p.toInt()) },
            onStage = { s -> stage = s; showProgress(ctx, notifId, "$s  (0%)", 0) }
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
                    showDone(ctx, notifId, result.displayTitle)
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
                    showError(ctx, notifId, result.error, stage)
                    Log.e(TAG, "Failed at '$stage': ${result.error}")
                }
            }
        }

        // Clear this download's persistent progress notification
        NotificationManagerCompat.from(ctx).cancel(notifId)
    }

    // -----------------------------------------------------------------------
    // Notifications — each download owns 3 IDs: notifId (progress),
    // notifId+1 (done), notifId+2 (error), so parallel downloads don't clash.
    // -----------------------------------------------------------------------

    /** True iff the user has not disabled download notifications in Profile. */
    private fun notifsAllowed(ctx: Context): Boolean =
        Settings(ctx).downloadNotifications

    private fun showProgress(ctx: Context, notifId: Int, text: String, progress: Int) {
        if (!notifsAllowed(ctx)) return
        val active = activeCount.get()
        val title = if (active > 1) "Lmusic — Downloading ($active active)" else "Lmusic — Downloading"
        val notif: Notification = NotificationCompat.Builder(ctx, LmusicApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(notifId, notif) }
        catch (_: SecurityException) {}
    }

    private fun showDone(ctx: Context, notifId: Int, filename: String) {
        if (!notifsAllowed(ctx)) return
        val notif = NotificationCompat.Builder(ctx, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloaded!")
            .setContentText(filename)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(notifId + 1, notif) }
        catch (_: SecurityException) {}
    }

    private fun showError(ctx: Context, notifId: Int, error: String, stage: String) {
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
        try { NotificationManagerCompat.from(ctx).notify(notifId + 2, notif) }
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
