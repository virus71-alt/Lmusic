package com.lmusic.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val youtubeUrl: String,
    val title: String,
    val downloadedAt: Long = System.currentTimeMillis()
)
