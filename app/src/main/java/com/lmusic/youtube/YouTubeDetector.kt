package com.lmusic.youtube

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.util.Log
import com.lmusic.service.LmusicNotificationListener

class YouTubeDetector(private val ctx: Context) {

    companion object {
        private const val TAG = "YouTubeDetector"
        private val YOUTUBE_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music"
        )
    }

    /**
     * Returns:
     *  - A full YouTube URL (https://youtube.com/watch?v=...) when we have the exact video ID
     *  - A ytsearch: query string when we only have the title
     *  - null when nothing is playing / detectable
     */
    fun getCurrentVideoUrl(): String? {
        // Path A: NotificationListener cache has a direct URL
        if (LmusicNotificationListener.isCacheValid()) {
            val url = LmusicNotificationListener.latestYouTubeUrl
            if (url != null) {
                Log.d(TAG, "Path A: exact URL from notification cache → $url")
                return url
            }

            // Cache is valid but no URL — build a search query from title/artist
            val title = LmusicNotificationListener.latestTitle
            val artist = LmusicNotificationListener.latestArtist
            if (title != null) {
                val query = if (artist != null) "$title $artist" else title
                Log.d(TAG, "Path A fallback: ytsearch from notification title → $query")
                return "ytsearch1:$query"
            }
        }

        // Path B: Query MediaSession for an active YouTube session
        return tryMediaSession()
    }

    private fun tryMediaSession(): String? {
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComponent = ComponentName(ctx, LmusicNotificationListener::class.java)
            val sessions = msm.getActiveSessions(listenerComponent)

            val ytSession = sessions.firstOrNull { it.packageName in YOUTUBE_PACKAGES }
                ?: return null

            val meta = ytSession.metadata ?: return null
            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)

            if (title == null) return null

            val query = if (artist != null) "$title $artist" else title
            Log.d(TAG, "Path B: ytsearch from MediaSession → $query")
            "ytsearch1:$query"
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaSession access denied (notification listener permission missing): ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession query failed: ${e.message}")
            null
        }
    }
}
