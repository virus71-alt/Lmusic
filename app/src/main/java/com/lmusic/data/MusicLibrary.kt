package com.lmusic.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Merges the user's Lmusic downloads with all audio files MediaStore knows
 * about, producing a single deduplicated stream of [MusicTrack]s.
 *
 * ── Optimization note ────────────────────────────────────────────────────────
 * The merged track list is exposed as a **single process-wide [StateFlow]** so
 * that all consumers (Home, Library, Favs) share one MediaStore scan and one
 * Room Flow subscription instead of each fragment running its own.
 *
 * `SharingStarted.WhileSubscribed(5_000)` keeps the cached value alive for 5s
 * after the last subscriber disappears, so tab switches re-use the cached list
 * instantly without re-scanning the device.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class MusicLibrary(private val ctx: Context) {

    /** Shared [StateFlow] of merged Lmusic + device tracks. Cheap to subscribe to. */
    fun all(): StateFlow<List<MusicTrack>> = getCached(ctx.applicationContext)

    companion object {

        // ── Process-wide cache ───────────────────────────────────────────────
        @Volatile
        private var cachedFlow: StateFlow<List<MusicTrack>>? = null

        /** Long-lived scope that owns the shared StateFlow. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun getCached(appCtx: Context): StateFlow<List<MusicTrack>> {
            return cachedFlow ?: synchronized(this) {
                cachedFlow ?: build(appCtx).also { cachedFlow = it }
            }
        }

        private fun build(appCtx: Context): StateFlow<List<MusicTrack>> {

            // Lmusic side: Room observes its own table; cheap.
            val lmusic = DownloadDatabase.get(appCtx).downloadDao().getAllFlow()
                .map { records -> records.map { it.toMusicTrack() } }

            // Device side: one-shot MediaStore scan on Dispatchers.IO.
            val device = flow { emit(scanDevice(appCtx)) }.flowOn(Dispatchers.IO)

            return combine(lmusic, device) { l, d ->
                // Dedupe: prefer the Lmusic row when same URI exists in both lists
                // (Lmusic rows have richer metadata + cached thumbnail).
                val lmusicByUri = l.filter { it.uri.isNotBlank() }.associateBy { it.uri }
                val deviceOnly  = d.filter { it.uri !in lmusicByUri }
                l + deviceOnly
            }.stateIn(
                scope        = scope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )
        }

        // ── Device MediaStore scan ───────────────────────────────────────────

        private fun scanDevice(appCtx: Context): List<MusicTrack> {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                @Suppress("DEPRECATION") MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATE_ADDED
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            val results = ArrayList<MusicTrack>(128)
            try {
                appCtx.contentResolver.query(collection, projection, selection, null, sort)
                    ?.use { c ->
                        val idCol     = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleCol  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val durCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val albumCol  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                        val dateCol   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                        while (c.moveToNext()) {
                            val mid       = c.getLong(idCol)
                            val title     = c.getString(titleCol) ?: "Unknown"
                            val artist    = c.getString(artistCol)
                                ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                            val dur       = c.getLong(durCol) / 1000L
                            val albumId   = c.getLong(albumCol).takeIf { it > 0 }
                            val dateAdded = c.getLong(dateCol) * 1000L
                            val uri       = ContentUris.withAppendedId(collection, mid).toString()

                            results += MusicTrack(
                                id              = "device-$mid",
                                title           = title,
                                artist          = artist,
                                durationSeconds = dur,
                                uri             = uri,
                                thumbnailUri    = null,
                                albumId         = albumId,
                                downloadedAt    = dateAdded,
                                source          = MusicTrack.Source.DEVICE
                            )
                        }
                    }
            } catch (_: SecurityException) {
                // No READ_MEDIA_AUDIO yet — empty device list is fine, Lmusic-only mode
            }
            return results
        }

        // ── Mapper ───────────────────────────────────────────────────────────

        private fun DownloadRecord.toMusicTrack(): MusicTrack = MusicTrack(
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
            errorMessage    = errorMessage,
            youtubeUrl      = youtubeUrl,
            category        = category,
            isAutoDownload  = isAutoDownload,
            expiresAt       = expiresAt
        )

        /** Builds the deprecated-but-still-functional album-art URI for a MediaStore album id. */
        fun albumArtUri(albumId: Long): android.net.Uri =
            android.net.Uri.parse("content://media/external/audio/albumart/$albumId")

        /**
         * Force a rescan of the device library.  Used when the user grants the
         * READ_MEDIA_AUDIO permission after first launch.
         */
        fun invalidate() {
            synchronized(this) { cachedFlow = null }
        }
    }
}
