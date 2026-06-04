package com.lmusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lmusic.MainActivity
import com.lmusic.R
import com.lmusic.player.PlaybackService

/**
 * Home-screen widget — track info + play/pause/skip controls.
 *
 * Widget click flow:
 *   • Tapping any of the 3 transport buttons fires a [PendingIntent.getService]
 *     into [PlaybackService] with one of:
 *       [PlaybackService.ACTION_PLAY_PAUSE]
 *       [PlaybackService.ACTION_NEXT]
 *       [PlaybackService.ACTION_PREV]
 *     The service handles them in [PlaybackService.onStartCommand].
 *   • Tapping the rest of the card opens [MainActivity] (bringing the app
 *     to the foreground, same behaviour as the media notification tap).
 *
 * Update flow:
 *   PlaybackService listens to its [androidx.media3.common.Player] and calls
 *   [pushState] on every play/pause + media-item transition.  System-driven
 *   refreshes also call [onUpdate], which delegates to [refresh].
 */
class LmusicWidget : AppWidgetProvider() {

    /**
     * Called when the system wants a refresh (e.g. user just placed the widget
     * on the home screen, or after a config change).  If [PlaybackService] is
     * alive we ask it to re-push its current state; otherwise paint the idle
     * layout.
     */
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val svc = PlaybackService.instance
        if (svc != null) {
            svc.requestWidgetPush()
        } else {
            manager.updateAppWidget(
                appWidgetIds,
                buildViews(context, title = null, artist = null, isPlaying = false)
            )
        }
    }

    companion object {

        /** PlaybackService → here: build a fresh RemoteViews + push it to AppWidgetManager. */
        fun pushState(
            ctx: Context,
            title: String?,
            artist: String?,
            isPlaying: Boolean
        ) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, LmusicWidget::class.java))
            if (ids.isEmpty()) return  // no widget on the home screen — nothing to do
            mgr.updateAppWidget(ids, buildViews(ctx, title, artist, isPlaying))
        }

        /** Force an idle re-paint (used when the service shuts down). */
        fun refresh(ctx: Context) {
            pushState(ctx, title = null, artist = null, isPlaying = false)
        }

        // ─────────────────────────────────────────────────────────────────
        // Internal — RemoteViews construction
        // ─────────────────────────────────────────────────────────────────

        private fun buildViews(
            ctx: Context,
            title: String?,
            artist: String?,
            isPlaying: Boolean
        ): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_player)

            views.setTextViewText(
                R.id.widget_title,
                title?.takeIf { it.isNotBlank() } ?: "Nothing playing"
            )
            views.setTextViewText(
                R.id.widget_artist,
                artist?.takeIf { it.isNotBlank() } ?: "Tap a track in Lmusic to start"
            )
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            // ── Click intents ──
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                serviceIntent(ctx, PlaybackService.ACTION_PLAY_PAUSE)
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                serviceIntent(ctx, PlaybackService.ACTION_NEXT)
            )
            views.setOnClickPendingIntent(
                R.id.widget_prev,
                serviceIntent(ctx, PlaybackService.ACTION_PREV)
            )

            // Tap the card body / title row → bring the app to the foreground.
            val openApp = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_text_area, openApp)

            return views
        }

        private fun serviceIntent(ctx: Context, action: String): PendingIntent {
            val intent = Intent(ctx, PlaybackService::class.java).setAction(action)
            return PendingIntent.getService(
                ctx,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
