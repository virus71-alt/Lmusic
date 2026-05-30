package com.lmusic.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class LmusicNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "LmusicNotifListener"
        private const val CACHE_TTL_MS = 30_000L

        private val YOUTUBE_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music"
        )

        // URL regex — matches https://www.youtube.com/watch?v=VIDEO_ID in intent string
        private val URL_REGEX = Regex("""https://(?:www\.)?youtube\.com/watch\?v=([\w-]{11})""")

        @Volatile var latestYouTubeUrl: String? = null
        @Volatile var latestTitle: String? = null
        @Volatile var latestArtist: String? = null
        @Volatile private var cacheTimestamp: Long = 0L

        fun isCacheValid() = System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS

        fun clearCache() {
            latestYouTubeUrl = null
            latestTitle = null
            latestArtist = null
            cacheTimestamp = 0L
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in YOUTUBE_PACKAGES) return
        val extras = sbn.notification?.extras ?: return

        val title = extras.getString("android.title")?.takeIf { it.isNotBlank() }
        val text = extras.getString("android.text")?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence("android.text")?.toString()?.takeIf { it.isNotBlank() }

        // Try to extract the video URL from the notification's content intent
        val url = tryExtractUrl(sbn)

        if (title != null || url != null) {
            latestTitle = title
            latestArtist = text
            latestYouTubeUrl = url
            cacheTimestamp = System.currentTimeMillis()
            Log.d(TAG, "Cached: title=$title artist=$text url=$url")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in YOUTUBE_PACKAGES) clearCache()
    }

    private fun tryExtractUrl(sbn: StatusBarNotification): String? {
        return try {
            // PendingIntent.toString() on AOSP includes the wrapped Intent URI,
            // which YouTube populates with the video deep-link.
            val intentString = sbn.notification?.contentIntent?.toString() ?: return null
            URL_REGEX.find(intentString)?.value
        } catch (e: Exception) {
            Log.w(TAG, "URL extraction failed: ${e.message}")
            null
        }
    }
}
