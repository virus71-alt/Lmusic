package com.lmusic.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lmusic.LmusicApp
import com.lmusic.MainActivity
import com.lmusic.R
import com.lmusic.data.DownloadDao
import com.lmusic.data.DownloadDatabase
import com.lmusic.data.DownloadRecord
import com.lmusic.data.FavoritesStore
import com.lmusic.data.MusicCategorizer
import com.lmusic.data.MusicTrack
import com.lmusic.data.RecommendationEngine
import com.lmusic.data.Settings
import com.lmusic.download.DownloadResult
import com.lmusic.download.YouTubeDownloader
import com.lmusic.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Weekly Mix — the "YT Music Premium offline mixtape" logic:
 *
 *   1. Reviews the user's library + favorites via [RecommendationEngine]
 *      (same singers, same categories — never random songs).
 *   2. Automatically downloads up to [MIX_SIZE] recommended tracks.
 *   3. Marks them `isAutoDownload` with a 7-day [DownloadRecord.expiresAt].
 *   4. Posts a notification inviting the user to listen.
 *   5. On the next run, expired mix tracks are cleaned up — unless the user
 *      favorited them, in which case they are promoted to permanent.
 *
 * Skips entirely when the library is empty (no taste data — downloading
 * anything then would be random, which is exactly what we avoid).
 */
class WeeklyMixWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "WeeklyMix"
        const val WORK_NAME = "weekly_mix"
        private const val WORK_NAME_NOW = "weekly_mix_now"

        const val MIX_SIZE = 10
        val KEEP_MS = TimeUnit.DAYS.toMillis(7)
        private const val NOTIF_ID = 4001

        /** Enqueue the repeating weekly job (runs soon after first enqueue, then weekly). */
        fun schedule(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<WeeklyMixWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }

        /** One-shot refresh, e.g. from a "Refresh mix" button. */
        fun runNow(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<WeeklyMixWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME_NOW, ExistingWorkPolicy.KEEP, request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = Settings(ctx)
        if (!settings.weeklyMix) return@withContext Result.success()

        val dao = DownloadDatabase.get(ctx).downloadDao()
        val now = System.currentTimeMillis()

        // ── 1. Clean up last week's mix (promote favorited tracks) ──────────
        cleanupExpired(dao, now)

        // ── 2. Skip if this week's mix is already (mostly) in place ─────────
        val freshCount = dao.countRecentAutoDownloads(now - KEEP_MS + TimeUnit.DAYS.toMillis(1))
        if (freshCount >= MIX_SIZE) {
            Log.i(TAG, "Mix already fresh ($freshCount tracks) — skipping")
            return@withContext Result.success()
        }

        // ── 3. Build recommendations from the user's own library ────────────
        val libraryTracks = dao.getAll()
            .filter { it.status == DownloadRecord.STATUS_OK }
            .map { it.toLiteTrack() }
        if (libraryTracks.isEmpty()) {
            Log.i(TAG, "Library empty — no taste data, not downloading random songs")
            return@withContext Result.success()
        }

        val wanted = MIX_SIZE - freshCount
        val picks = RecommendationEngine(ctx).buildWeeklyPicks(libraryTracks, wanted)
        if (picks.isEmpty()) {
            Log.i(TAG, "No recommendations resolved this week")
            return@withContext Result.success()
        }

        // ── 4. Download the picks ────────────────────────────────────────────
        var done = 0
        val downloader = YouTubeDownloader(ctx)
        for (pick in picks) {
            if (isStopped) break
            // Never re-download something already in the library.
            if (dao.countByUrl(pick.videoUrl) > 0) continue
            try {
                when (val result = downloader.download(pick.videoUrl, onProgress = {})) {
                    is DownloadResult.Success -> {
                        dao.insert(
                            DownloadRecord(
                                youtubeUrl      = pick.videoUrl,
                                title           = result.displayTitle,
                                channel         = result.channel,
                                durationSeconds = result.durationSeconds,
                                thumbnailPath   = result.thumbnailPath,
                                fileUri         = result.fileUri,
                                category        = MusicCategorizer.categorize(
                                    result.displayTitle, result.channel
                                ),
                                isAutoDownload  = true,
                                expiresAt       = now + KEEP_MS
                            )
                        )
                        done++
                        Log.d(TAG, "Mix track ${done}: ${result.displayTitle}")
                    }
                    is DownloadResult.Failure ->
                        Log.w(TAG, "Mix download failed: ${pick.title} — ${result.error}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mix download crashed for ${pick.title}", e)
                CrashLogger.logNonFatal(ctx, TAG, e)
            }
        }

        // ── 5. Tell the user their mix is ready ─────────────────────────────
        if (done > 0) notifyMixReady(done)
        Log.i(TAG, "Weekly mix complete — $done new tracks")
        Result.success()
    }

    /**
     * Removes auto-downloads whose 7-day window elapsed.
     * Favorited tracks are promoted to permanent instead of deleted.
     */
    private suspend fun cleanupExpired(dao: DownloadDao, now: Long) {
        val favorites = FavoritesStore(ctx)
        for (record in dao.getExpiredAutoDownloads(now)) {
            if (favorites.isFavorite("lmusic-${record.id}")) {
                dao.promoteToPermanent(record.id)
                Log.d(TAG, "Promoted favorited mix track: ${record.title}")
                continue
            }
            try {
                record.fileUri?.let { uri ->
                    if (uri.startsWith("content://"))
                        ctx.contentResolver.delete(uri.toUri(), null, null)
                    else File(uri).takeIf { it.exists() }?.delete()
                }
                record.thumbnailPath?.let { File(it).takeIf { f -> f.exists() }?.delete() }
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup failed for ${record.title}: ${e.message}")
            }
            dao.deleteById(record.id)
            Log.d(TAG, "Expired mix track removed: ${record.title}")
        }
    }

    private fun notifyMixReady(count: Int) {
        val openApp = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(ctx, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("Your Weekly Mix is ready 🎧")
            .setContentText("$count new songs picked from your favorite artists — saved offline for 7 days")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$count new songs picked from your favorite artists and genres.\n" +
                "They're saved offline for 7 days — heart the ones you want to keep!"
            ))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif) }
        catch (_: SecurityException) {}
    }

    /** Thin record→track mapping for the recommendation engine (no MediaStore scan). */
    private fun DownloadRecord.toLiteTrack() = MusicTrack(
        id              = "lmusic-$id",
        title           = title,
        artist          = channel,
        durationSeconds = durationSeconds,
        uri             = fileUri.orEmpty(),
        thumbnailUri    = thumbnailPath,
        albumId         = null,
        downloadedAt    = downloadedAt,
        source          = MusicTrack.Source.LMUSIC,
        downloadRecordId = id,
        status          = status,
        youtubeUrl      = youtubeUrl,
        category        = category,
        isAutoDownload  = isAutoDownload,
        expiresAt       = expiresAt
    )
}
