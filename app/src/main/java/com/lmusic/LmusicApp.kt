package com.lmusic

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.appcompat.app.AppCompatDelegate
import com.lmusic.data.BackupManager
import com.lmusic.data.Settings
import com.lmusic.plugin.PluginManager
import com.lmusic.util.CrashLogger
import com.lmusic.worker.BatchRestoreWorker
import com.lmusic.worker.ThumbnailSyncWorker
import com.lmusic.worker.WeeklyMixWorker

class LmusicApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        createNotificationChannels()
        AppCompatDelegate.setDefaultNightMode(Settings(this).themeMode)
        PluginManager.init(this)   // discovers built-in + any sideloaded plugin APKs
        // Re-enqueue any restore that was interrupted by process death.
        if (BackupManager.hasPendingRestore(this)) {
            BatchRestoreWorker.enqueue(this)
        }

        if (Settings(this).autoSyncThumbnails) {
            ThumbnailSyncWorker.schedule(this)
        }

        if (Settings(this).weeklyMix) {
            WeeklyMixWorker.schedule(this)
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOAD,
                "Downloads",
                NotificationManager.IMPORTANCE_DEFAULT  // default so it shows in the shade without being missed
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
