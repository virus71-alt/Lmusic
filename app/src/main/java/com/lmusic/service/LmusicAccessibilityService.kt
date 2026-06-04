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
import com.lmusic.data.Settings
import com.lmusic.youtube.YouTubeDetector

class LmusicAccessibilityService : AccessibilityService() {

    companion object {
        private const val NOTIF_STAGE_ERROR = 9003

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
                // Ignore key repeats from long-press.
                // Consume repeats whenever EITHER key is currently held: this prevents
                // volume from drifting while the user is in the process of pressing the
                // second key for the gesture. Trade-off: continuous long-press volume
                // change is limited to one step per initial key-down; a second press is
                // needed for each additional step.
                if (event.repeatCount > 0) {
                    return volUpDownTime > 0L || volDownDownTime > 0L
                }

                if (code == KeyEvent.KEYCODE_VOLUME_UP) volUpDownTime = now
                else volDownDownTime = now

                if (volUpDownTime > 0L && volDownDownTime > 0L) {
                    // Both keys now down — schedule trigger and consume this event
                    val hold = Settings(this).holdDurationMs
                    handler.removeCallbacks(triggerRunnable)
                    handler.postDelayed(triggerRunnable, hold)
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
            try {
                val url = YouTubeDetector(applicationContext).getCurrentVideoUrl()
                if (url != null) {
                    com.lmusic.download.DownloadRunner.start(applicationContext, url)
                } else {
                    postNotif(
                        NOTIF_STAGE_ERROR,
                        "Lmusic — nothing to download",
                        "Open YouTube, play a song, then hold both volume keys."
                    )
                }
            } catch (t: Throwable) {
                com.lmusic.util.CrashLogger.logNonFatal(applicationContext, "trigger", t)
                postNotif(
                    NOTIF_STAGE_ERROR,
                    "Lmusic — couldn't start",
                    "${t.javaClass.simpleName}: ${(t.message ?: "").take(180)}"
                )
            }
        }.start()
    }

    private fun postNotif(id: Int, title: String, text: String) {
        val notif = NotificationCompat.Builder(this, LmusicApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(id, notif)
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
