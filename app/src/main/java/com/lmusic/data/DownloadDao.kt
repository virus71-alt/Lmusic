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
}
