package com.lmusic.data

/**
 * Unified track model used by the library UI and the player.
 * Backed by either a [DownloadRecord] (Lmusic-downloaded) or a MediaStore row (device music).
 */
data class MusicTrack(
    val id: String,                  // stable key: "lmusic-<id>" or "device-<mediaStoreId>"
    val title: String,
    val artist: String?,
    val durationSeconds: Long,
    val uri: String,                 // content://media/... or file:// path
    val thumbnailUri: String?,       // explicit thumbnail file (Lmusic) — null means use MediaStore lookup
    val albumId: Long?,              // for MediaStore album-art lookup on device tracks
    val downloadedAt: Long,
    val source: Source,
    val downloadRecordId: Int? = null,   // present iff this came from Lmusic Room DB (used for delete)
    val status: String = DownloadRecord.STATUS_OK,
    val errorMessage: String? = null,
    val youtubeUrl: String? = null,
    val category: String? = null,
    /** True for weekly-mix auto-downloads (shown with an "auto" badge). */
    val isAutoDownload: Boolean = false,
    /** Epoch ms when an auto-download will be removed; null for permanent tracks. */
    val expiresAt: Long? = null
) {
    enum class Source { LMUSIC, DEVICE }
}
