package com.lmusic.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lmusic.MainActivity
import com.lmusic.widget.LmusicWidget

/**
 * Long-lived service that owns the [ExoPlayer] and a [MediaSession].
 *
 * Responsibilities:
 *   • Music keeps playing across UI destruction / app swipe-away.
 *   • Posts the system media notification (with `setSessionActivity` so the
 *     user can tap the notification to bring the app back to the foreground).
 *   • Accepts widget commands via [onStartCommand] (`ACTION_*` intents from
 *     [LmusicWidget]).
 *   • Pushes RemoteViews updates to the home-screen widget on every playback
 *     state change.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        /** Nullable reference — valid only while the service is running. */
        @Volatile var instance: PlaybackService? = null

        // ── Widget action intents ──────────────────────────────────────────
        const val ACTION_PLAY_PAUSE = "com.lmusic.action.WIDGET_PLAY_PAUSE"
        const val ACTION_NEXT       = "com.lmusic.action.WIDGET_NEXT"
        const val ACTION_PREV       = "com.lmusic.action.WIDGET_PREV"
    }

    private var mediaSession: MediaSession? = null

    /** Stops playback when the sleep timer fires. */
    private val sleepTimerCallback: () -> Unit = {
        mediaSession?.player?.run {
            pause()
            seekTo(0L)
            stop()
        }
    }

    /** Player.Listener that mirrors state into the home-screen widget. */
    private val widgetListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean)     = pushWidget()
        override fun onMediaItemTransition(item: MediaItem?, r: Int) = pushWidget()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pause on headphone unplug
            .build()
        player.addListener(widgetListener)

        // ── PendingIntent → bring MainActivity to the front ──
        // Without this, tapping the system media notification does nothing
        // visible — the OS doesn't know which activity owns the session.
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        SleepTimerManager.addFireCallback(sleepTimerCallback)
        pushWidget()  // initial paint with no track
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Widget button intents land here.  We dispatch the action directly to the
     * player so the widget feels instant.  Other [Intent]s fall through to
     * the [MediaSessionService] base, which handles media-button events etc.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = mediaSession?.player
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            ACTION_NEXT       -> player?.seekToNextMediaItem()
            ACTION_PREV       -> player?.seekToPreviousMediaItem()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Stop playback (and the service) if the user removes the app while paused. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        SleepTimerManager.removeFireCallback(sleepTimerCallback)
        instance = null
        mediaSession?.run {
            player.removeListener(widgetListener)
            player.release()
            release()
        }
        mediaSession = null
        // Clear the widget back to its idle state.
        LmusicWidget.refresh(applicationContext)
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Widget bridge
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Public entry point — called by [com.lmusic.widget.LmusicWidget.onUpdate]
     * when the system triggers a widget refresh (e.g. user just placed the
     * widget on the home screen).
     */
    fun requestWidgetPush() = pushWidget()

    private fun pushWidget() {
        val player = mediaSession?.player
        val item   = player?.currentMediaItem
        LmusicWidget.pushState(
            ctx       = applicationContext,
            title     = item?.mediaMetadata?.title?.toString(),
            artist    = item?.mediaMetadata?.artist?.toString(),
            isPlaying = player?.isPlaying == true
        )
    }
}
