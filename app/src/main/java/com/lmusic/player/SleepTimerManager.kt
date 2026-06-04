package com.lmusic.player

import android.os.Handler
import android.os.Looper

/**
 * App-wide sleep timer. Survives fragment navigation because state lives here as a singleton.
 *
 * Usage:
 *   // Start a 30-minute timer:
 *   SleepTimerManager.schedule(30 * 60 * 1000L)
 *
 *   // Cancel:
 *   SleepTimerManager.cancel()
 *
 * [PlaybackService] registers a callback via [addFireCallback] on creation and removes it on
 * destroy so it can stop the player when the timer fires.
 */
object SleepTimerManager {

    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private val fireCallbacks = mutableListOf<() -> Unit>()

    /** Epoch-ms at which the timer will fire. 0 when no timer is active. */
    var fireAtMs: Long = 0L
        private set

    /** True while a timer is scheduled. */
    val isActive: Boolean get() = fireAtMs > 0L

    /** Approximate ms remaining. 0 when inactive or already elapsed. */
    val remainingMs: Long
        get() = if (fireAtMs == 0L) 0L else (fireAtMs - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * Schedule playback to stop after [delayMs] milliseconds.
     * Cancels any previously running timer first.
     * Pass 0 or a negative value to only cancel.
     */
    fun schedule(delayMs: Long) {
        cancel()
        if (delayMs <= 0L) return
        fireAtMs = System.currentTimeMillis() + delayMs
        timerRunnable = Runnable {
            fireAtMs = 0L
            fireCallbacks.toList().forEach { it.invoke() }   // snapshot to avoid ConcurrentModification
        }
        handler.postDelayed(timerRunnable!!, delayMs)
    }

    /** Cancel the running timer. Safe to call when no timer is active. */
    fun cancel() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
        fireAtMs = 0L
    }

    /** Register a callback that fires when the timer expires. Call from [PlaybackService.onCreate]. */
    fun addFireCallback(cb: () -> Unit) {
        if (cb !in fireCallbacks) fireCallbacks += cb
    }

    /** Unregister a previously registered callback. Call from [PlaybackService.onDestroy]. */
    fun removeFireCallback(cb: () -> Unit) {
        fireCallbacks -= cb
    }
}
