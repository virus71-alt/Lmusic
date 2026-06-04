package com.lmusic.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val youtubeUrl: String,
    val title: String,
    val channel: String? = null,
    val durationSeconds: Long = 0L,
    val thumbnailPath: String? = null,
    val fileUri: String? = null,
    val status: String = STATUS_OK,  // "ok" | "failed"
    val errorMessage: String? = null,
    val downloadedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_FAILED = "failed"
    }
}
