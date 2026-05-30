package com.lmusic.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lmusic.LmusicApp
import com.lmusic.R
import com.lmusic.youtube.YouTubeDetector

class LmusicAccessibilityService : AccessibilityService() {

    companion object {
        // How long both keys must be held simultaneously to trigger
        private const val HOLD_DURATION_MS = 250L
        private const val STATUS_NOTIF_ID = 9001

        @Volatile
        var instance: LmusicAccessibilityService? = null
    }

    private var volUpDownTime: Long = 0L   // when VOLUME_UP was pressed (0 if not held)
    private var volDownDownTime: Long = 0L // when VOLUME_DOWN was pressed
    private var gestureFired: Boolean = false  // armed once both held + delay elapsed

    private val handler = Handler(Looper.getMainLooper())
    private val triggerRunnable = Runnable {
        // Fires if both keys are STILL held when the delay elapses
        if (volUpDownTime > 0L && volDownDownTime > 0L && !gestureFired) {
            gestureFired = true
            onCombinationHeld()
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        val now = System.currentTimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Ignore key repeats from long-press
                if (event.repeatCount > 0) {
                    // If both already held, keep consuming so volume doesn't drift
                    return volUpDownTime > 0L && volDownDownTime > 0L
                }

                if (code == KeyEvent.KEYCODE_VOLUME_UP) volUpDownTime = now
                else volDownDownTime = now

                if (volUpDownTime > 0L && volDownDownTime > 0L) {
                    // Both keys now down — schedule trigger and consume this event
                    // (consuming the 2nd key's down prevents its volume action)
                    handler.removeCallbacks(triggerRunnable)
                    handler.postDelayed(triggerRunnable, HOLD_DURATION_MS)
                    return true
                }
                // Only one key held so far — let system process volume change as normal
                return false
            }

            KeyEvent.ACTION_UP -> {
                val wasComboHeld = volUpDownTime > 0L && volDownDownTime > 0L
                if (code == KeyEvent.KEYCODE_VOLUME_UP) volUpDownTime = 0L
                else volDownDownTime = 0L

                handler.removeCallbacks(triggerRunnable)

                if (gestureFired) {
                    // Gesture already triggered — eat the UP so no volume action runs
                    gestureFired = false
                    return true
                }

                // Combo was held but released before threshold — consume UP to keep
                // volume change clean (the 2nd DOWN was consumed too)
                if (wasComboHeld) return true

                return false
            }
        }
        return false
    }

    private fun onCombinationHeld() {
        Thread {
            val url = YouTubeDetector(applicationContext).getCurrentVideoUrl()
            if (url != null) {
                DownloadService.startDownload(applicationContext, url)
            } else {
                postStatusNotification(
                    title = "Lmusic — nothing to download",
                    text = "Open YouTube, play a song, then hold both volume keys"
                )
            }
        }.start()
    }

    private fun postStatusNotification(title: String, text: String) {
        val notif = NotificationCompat.Builder(this, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(STATUS_NOTIF_ID, notif)
        } catch (_: SecurityException) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {
        volUpDownTime = 0L
        volDownDownTime = 0L
        gestureFired = false
        handler.removeCallbacks(triggerRunnable)
    }
}
