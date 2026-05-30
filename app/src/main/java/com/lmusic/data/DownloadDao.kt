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

    @Insert
    suspend fun insert(record: DownloadRecord)

    @Delete
    suspend fun delete(record: DownloadRecord)
}
