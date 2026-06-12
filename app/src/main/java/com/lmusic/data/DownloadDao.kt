package com.lmusic.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun getAllFlow(): Flow<List<DownloadRecord>>

    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun count(): Int

    @Insert
    suspend fun insert(record: DownloadRecord)

    @Delete
    suspend fun delete(record: DownloadRecord)

    @Delete
    suspend fun deleteAll(records: List<DownloadRecord>)

    /** Returns the number of successfully downloaded records with this URL. Used during restore
     *  to skip tracks that are already present in the library. */
    @Query("SELECT COUNT(*) FROM downloads WHERE youtubeUrl = :url AND status = 'ok'")
    suspend fun countByUrl(url: String): Int

    /** All records as a plain list (used by backup export). */
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    suspend fun getAll(): List<DownloadRecord>

    @Query("UPDATE downloads SET thumbnailPath = :path WHERE id = :id")
    suspend fun updateThumbnailPath(id: Int, path: String)

    // ── Categorization ──────────────────────────────────────────────────────

    @Query("UPDATE downloads SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: Int, category: String?)

    /** Records still missing a category (used by a one-time backfill pass). */
    @Query("SELECT * FROM downloads WHERE category IS NULL AND status = 'ok'")
    suspend fun getUncategorized(): List<DownloadRecord>

    // ── Weekly auto-mix ─────────────────────────────────────────────────────

    /** All auto-downloaded ("weekly mix") tracks that completed successfully. */
    @Query("SELECT * FROM downloads WHERE isAutoDownload = 1 AND status = 'ok' ORDER BY downloadedAt DESC")
    suspend fun getAutoDownloads(): List<DownloadRecord>

    /** Auto-downloads whose keep-window has elapsed — ready for cleanup. */
    @Query("SELECT * FROM downloads WHERE isAutoDownload = 1 AND expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun getExpiredAutoDownloads(now: Long): List<DownloadRecord>

    /** How many auto-downloads were added since [since] (dedup guard for the worker). */
    @Query("SELECT COUNT(*) FROM downloads WHERE isAutoDownload = 1 AND status = 'ok' AND downloadedAt > :since")
    suspend fun countRecentAutoDownloads(since: Long): Int

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Int)

    /** Converts an auto-download into a permanent track (e.g. user favorited it). */
    @Query("UPDATE downloads SET isAutoDownload = 0, expiresAt = NULL WHERE id = :id")
    suspend fun promoteToPermanent(id: Int)
}
