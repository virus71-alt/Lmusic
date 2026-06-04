package com.lmusic.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.SeekBar
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.common.util.concurrent.MoreExecutors
import com.lmusic.R
import com.lmusic.data.MusicLibrary
import com.lmusic.data.MusicTrack
import com.lmusic.databinding.LayoutMiniPlayerBinding
import java.io.File

/**
 * UI controller for the bottom-bar mini player.
 *
 * Owns a [MediaController] that talks to the long-lived [PlaybackService].
 * All actual playback state lives in the service — this class just renders it.
 */
class MiniPlayerController(private val ctx: Context) {

    private var controller: MediaController? = null
    private var binding: LayoutMiniPlayerBinding? = null
    private var onCardClick: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isUserScrubbing = false

    /**
     * Periodically refreshes the seek bar.  Self-throttling — when nothing is
     * playing we slow the loop to 2s so we stop burning CPU/main-thread time
     * while idle.  Resumes 500 ms cadence the moment playback starts.
     */
    private val progressUpdater = object : Runnable {
        override fun run() {
            val playing = controller?.isPlaying == true
            if (playing && !isUserScrubbing) updateSeekBar()
            handler.postDelayed(this, if (playing) 500L else 2000L)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding?.mpPlayPause?.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            // Kick the loop awake immediately when playback resumes.
            if (isPlaying) {
                handler.removeCallbacks(progressUpdater)
                handler.post(progressUpdater)
            }
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            updateNowPlayingUI(item)
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) updateSeekBar()
        }
    }

    fun bind(b: LayoutMiniPlayerBinding) {
        binding = b

        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            try {
                controller = future.get().also {
                    it.addListener(listener)
                    syncFromController(it)
                }
            } catch (t: Throwable) {
                Log.e("MiniPlayer", "Failed to bind MediaController", t)
            }
        }, MoreExecutors.directExecutor())

        b.mpPlayPause.setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        b.mpNext.setOnClickListener { controller?.seekToNextMediaItem() }
        b.mpPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }
        b.mpClose.setOnClickListener { stop() }

        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { isUserScrubbing = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isUserScrubbing = false
                controller?.seekTo(sb.progress.toLong() * 1000L)
            }
        })

        // Tap the card → open full-screen now-playing
        b.root.setOnClickListener { onCardClick?.invoke() }
        b.mpTitle.setOnClickListener { onCardClick?.invoke() }
        b.mpArtist.setOnClickListener { onCardClick?.invoke() }
        b.mpThumbnail.setOnClickListener { onCardClick?.invoke() }

        handler.post(progressUpdater)
    }

    fun setOnCardClick(callback: () -> Unit) { onCardClick = callback }

    /**
     * Replace the playback queue with [tracks] and start at [startIndex].
     * If the controller isn't ready yet, this is a no-op (call again later).
     */
    fun playQueue(tracks: List<MusicTrack>, startIndex: Int) {
        val c = controller ?: return
        val items = tracks
            .filter { it.uri.isNotBlank() && it.status == com.lmusic.data.DownloadRecord.STATUS_OK }
            .map { toMediaItem(it) }
        if (items.isEmpty()) return
        val clampedStart = startIndex.coerceIn(0, items.size - 1)
        c.setMediaItems(items, clampedStart, 0L)
        c.prepare()
        c.play()
        showPlayer()
    }

    /**
     * Plays a single HTTP stream URL (e.g. a resolved YouTube CDN URL) directly.
     * Use this for Explore / search results where no local file exists yet.
     * Replaces the current queue with a single-item queue.
     *
     * @param httpUrl    Direct audio CDN URL returned by a [StreamPlugin].
     * @param title      Track title for the media notification + Now Playing screen.
     * @param artist     Artist / channel name.
     * @param artworkUrl Optional HTTP(S) URL to the track's thumbnail / cover art.
     */
    fun playFromStream(
        httpUrl: String,
        title: String,
        artist: String?,
        artworkUrl: String?
    ) {
        val c = controller ?: return
        val item = buildStreamItem(httpUrl, title, artist, artworkUrl)
        c.setMediaItem(item)
        c.prepare()
        c.play()
        showPlayer()
    }

    /**
     * Appends a resolved HTTP stream to the END of the current queue without
     * interrupting playback.  Used by [com.lmusic.ui.ExploreFragment] to grow
     * the queue from background-resolved search results so Shuffle / Next have
     * real items to operate on.
     */
    fun appendStreamToQueue(
        httpUrl: String,
        title: String,
        artist: String?,
        artworkUrl: String?
    ) {
        val c = controller ?: return
        c.addMediaItem(buildStreamItem(httpUrl, title, artist, artworkUrl))
        // No play()/prepare() — the controller is already running and will
        // pick up the new item when the current one finishes or user taps Next.
    }

    private fun buildStreamItem(
        httpUrl: String, title: String, artist: String?, artworkUrl: String?
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist ?: "Unknown artist")
            .apply { artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        return MediaItem.Builder()
            .setUri(Uri.parse(httpUrl))
            .setMediaMetadata(metadata)
            .build()
    }

    /** Plays a single track, jumping to it in the existing queue or starting fresh. */
    fun load(track: MusicTrack, queueContext: List<MusicTrack>) {
        val visible = queueContext.filter {
            it.uri.isNotBlank() && it.status == com.lmusic.data.DownloadRecord.STATUS_OK
        }
        val idx = visible.indexOfFirst { it.id == track.id }
        if (idx >= 0) {
            playQueue(visible, idx)
        } else {
            playQueue(listOf(track) + visible, 0)
        }
    }

    private fun toMediaItem(t: MusicTrack): MediaItem {
        val parsed: Uri = if (t.uri.startsWith("content://")) t.uri.toUri()
        else Uri.fromFile(File(t.uri))

        val artworkUri: Uri? = when {
            !t.thumbnailUri.isNullOrBlank() && File(t.thumbnailUri).exists() -> Uri.fromFile(File(t.thumbnailUri))
            t.albumId != null -> MusicLibrary.albumArtUri(t.albumId)
            else -> null
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist ?: "Unknown artist")
            .setArtworkUri(artworkUri)
            .setExtras(Bundle().apply { putString("trackId", t.id) })
            .build()

        return MediaItem.Builder()
            .setUri(parsed)
            .setMediaId(t.id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun syncFromController(c: MediaController) {
        if (c.currentMediaItem != null) {
            updateNowPlayingUI(c.currentMediaItem)
            binding?.mpPlayPause?.setImageResource(
                if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            showPlayer()
        }
    }

    private fun updateNowPlayingUI(item: MediaItem?) {
        val b = binding ?: return
        if (item == null) { b.root.isVisible = false; return }

        val md = item.mediaMetadata
        b.mpTitle.text = md.title ?: "Unknown track"
        b.mpArtist.text = md.artist ?: "Unknown artist"

        val artUri = md.artworkUri
        if (artUri != null) {
            b.mpThumbnail.load(artUri) {
                crossfade(true)
                size(144)                                    // ~48dp mini-player thumb
                scale(coil.size.Scale.FILL)
                transformations(RoundedCornersTransformation(16f))
                error(R.drawable.bg_thumbnail_placeholder)
                placeholder(R.drawable.bg_thumbnail_placeholder)
            }
        } else {
            b.mpThumbnail.setImageDrawable(null)
            b.mpThumbnail.setBackgroundResource(R.drawable.bg_thumbnail_placeholder)
        }
    }

    private fun updateSeekBar() {
        val c = controller ?: return
        val b = binding ?: return
        val dur = c.duration
        if (dur > 0) {
            b.seekBar.max = (dur / 1000L).toInt()
            b.seekBar.progress = (c.currentPosition / 1000L).toInt()
        }
    }

    private fun showPlayer() {
        val v = binding?.root ?: return
        if (v.isVisible) return
        v.alpha = 0f
        v.translationY = v.height.toFloat().takeIf { it > 0 } ?: 100f
        v.isVisible = true
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240)
            .start()
    }

    fun stop() {
        controller?.stop()
        controller?.clearMediaItems()
        val v = binding?.root ?: return
        v.animate()
            .alpha(0f)
            .translationY(v.height.toFloat())
            .setDuration(200)
            .withEndAction { v.isVisible = false; v.alpha = 1f; v.translationY = 0f }
            .start()
    }

    fun release() {
        handler.removeCallbacks(progressUpdater)
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        binding = null
    }

    val isActive get() = controller != null && (binding?.root?.isVisible == true)

    /** Expose the controller so the full-screen Now Playing screen can attach to the same session. */
    fun currentController(): MediaController? = controller
}
