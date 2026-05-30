package com.lmusic

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class LmusicApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // yt-dlp lives in nativeLibraryDir (auto-extracted by Android) — no install step needed
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOAD,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows download progress" }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                "Status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Quick status messages" }
        )
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "lmusic_download"
        const val CHANNEL_STATUS = "lmusic_status"
    }
}
