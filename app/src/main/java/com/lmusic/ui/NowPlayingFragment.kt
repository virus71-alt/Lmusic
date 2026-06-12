package com.lmusic.ui

import android.animation.ObjectAnimator
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.view.animation.LinearInterpolator
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.common.util.concurrent.MoreExecutors
import com.lmusic.R
import com.lmusic.databinding.FragmentNowPlayingBinding
import com.lmusic.player.PlaybackService
import com.lmusic.player.SleepTimerManager
import java.util.Locale

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!

    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isUserScrubbing = false

    /** Continuous vinyl rotation; paused while playback is paused. */
    private var vinylSpin: ObjectAnimator? = null

    /** Selectable playback speeds, cycled by tapping the speed chip. */
    private val speedSteps = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** Backs the heart button in the top bar. */
    private val favorites by lazy {
        com.lmusic.data.FavoritesStore(requireContext())
    }

    /**
     * Self-throttling progress refresher.  500ms while playing, 2s while paused
     * so we don't fight the system for main-thread time during long pauses.
     */
    private val progressUpdater = object : Runnable {
        override fun run() {
            val playing = controller?.isPlaying == true
            if (playing && !isUserScrubbing) updateSeekUI()
            updateSleepTimerButton()
            handler.postDelayed(this, if (playing) 500L else 2000L)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon(isPlaying)
            updateCoverPulse(isPlaying)
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            updateNowPlayingUI(item)
            updateLikeIcon(item)
        }
        override fun onShuffleModeEnabledChanged(enabled: Boolean) = updateShuffleIcon(enabled)
        override fun onRepeatModeChanged(mode: Int) = updateRepeatIcon(mode)
        override fun onPlaybackParametersChanged(params: androidx.media3.common.PlaybackParameters) =
            updateSpeedChip(params.speed)
    }

    private fun updateCoverPulse(isPlaying: Boolean) {
        val v = _binding?.npCover ?: return
        v.animate().cancel()
        if (isPlaying) {
            v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(450).start()
        } else {
            v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(380).start()
        }
        updateVinylSpin(isPlaying)
    }

    /** Spins the circular cover like a record while playing; pauses with playback. */
    private fun updateVinylSpin(isPlaying: Boolean) {
        val v = _binding?.npCover ?: return
        val anim = vinylSpin ?: ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 360f).apply {
            duration = 14_000L
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
        }.also { vinylSpin = it }

        if (isPlaying) {
            if (anim.isPaused) anim.resume() else if (!anim.isStarted) anim.start()
        } else if (anim.isRunning) {
            anim.pause()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCollapse.setOnClickListener { parentFragmentManager.popBackStack() }

        // Queue sheet — lists the live MediaController items + tap to jump.
        binding.btnQueue.setOnClickListener {
            QueueBottomSheet().show(parentFragmentManager, "queue")
        }

        // Like / favorite toggle.  Only meaningful for library tracks (those
        // have a stable mediaId).  Streams from search have an empty mediaId
        // and the button shows up disabled / dimmed.
        binding.btnLike.setOnClickListener {
            val id = controller?.currentMediaItem?.mediaId.orEmpty()
            if (id.isBlank()) return@setOnClickListener
            favorites.toggle(id)
            updateLikeIcon(controller?.currentMediaItem)
        }

        binding.btnLyrics.setOnClickListener {
            LyricsBottomSheet().show(parentFragmentManager, "lyrics")
        }

        binding.btnSleepTimer.setOnClickListener { showSleepTimerDialog() }
        updateSleepTimerButton()

        binding.npPlayPause.setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        binding.npNext.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.npPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }

        binding.npShuffle.setOnClickListener {
            controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        binding.npRepeat.setOnClickListener {
            controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }

        // Cycle playback speed: 0.75x → 1x → 1.25x → 1.5x → 2x → …
        binding.npSpeed.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            val current = c.playbackParameters.speed
            val idx = speedSteps.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
            val next = speedSteps[(idx + 1).mod(speedSteps.size)]
            c.setPlaybackSpeed(next)
        }

        binding.npSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.npTimeElapsed.text = formatTime(progress.toLong() * 1000L)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { isUserScrubbing = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isUserScrubbing = false
                controller?.seekTo(sb.progress.toLong() * 1000L)
            }
        })

        // Bind to the running MediaSession
        val ctx = requireContext().applicationContext
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            controller = future.get().also {
                it.addListener(listener)
                updateNowPlayingUI(it.currentMediaItem)
                updateLikeIcon(it.currentMediaItem)
                updatePlayPauseIcon(it.isPlaying)
                updateCoverPulse(it.isPlaying)
                updateShuffleIcon(it.shuffleModeEnabled)
                updateRepeatIcon(it.repeatMode)
                updateSpeedChip(it.playbackParameters.speed)
                updateSeekUI()
            }
        }, MoreExecutors.directExecutor())

        handler.post(progressUpdater)
    }

    private fun updateNowPlayingUI(item: MediaItem?) {
        if (item == null) return
        val md = item.mediaMetadata
        binding.npTitle.text = md.title ?: "Unknown track"
        binding.npArtist.text = md.artist ?: "Unknown artist"
        val art: Uri? = md.artworkUri
        if (art != null) {
            binding.npCover.load(art) {
                crossfade(true)
                size(640)                                    // 280dp circular vinyl
                scale(coil.size.Scale.FILL)
                transformations(RoundedCornersTransformation(24f))
                error(R.drawable.bg_thumbnail_placeholder)
                placeholder(R.drawable.bg_thumbnail_placeholder)
            }
        } else {
            binding.npCover.setImageDrawable(null)
            binding.npCover.setBackgroundResource(R.drawable.bg_thumbnail_placeholder)
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        binding.npPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    /**
     * Reflects the current track's favorite state on the heart button.
     * Streams without a mediaId (those played from search) get a dimmed icon
     * because we can't reliably key them in [com.lmusic.data.FavoritesStore].
     */
    private fun updateLikeIcon(item: MediaItem?) {
        val b = _binding ?: return
        val id = item?.mediaId.orEmpty()
        if (id.isBlank()) {
            b.btnLike.setImageResource(R.drawable.ic_favorite_border)
            b.btnLike.imageTintList = null
            b.btnLike.alpha = 0.35f
            return
        }
        b.btnLike.alpha = 1f
        val fav = favorites.isFavorite(id)
        b.btnLike.setImageResource(
            if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        b.btnLike.imageTintList =
            if (fav) ContextCompat.getColorStateList(requireContext(), R.color.accent)
            else null
    }

    private fun updateShuffleIcon(enabled: Boolean) {
        val b = _binding ?: return
        if (enabled) {
            b.npShuffle.alpha = 1f
            b.npShuffle.imageTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.accent)
        } else {
            b.npShuffle.alpha = 0.45f
            b.npShuffle.imageTintList = null
        }
    }

    private fun updateRepeatIcon(mode: Int) {
        val b = _binding ?: return
        when (mode) {
            Player.REPEAT_MODE_ONE -> {
                b.npRepeat.setImageResource(R.drawable.ic_repeat_one)
                b.npRepeat.alpha = 1f
                b.npRepeat.imageTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.accent)
            }
            Player.REPEAT_MODE_ALL -> {
                b.npRepeat.setImageResource(R.drawable.ic_repeat)
                b.npRepeat.alpha = 1f
                b.npRepeat.imageTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.accent)
            }
            else -> {
                b.npRepeat.setImageResource(R.drawable.ic_repeat)
                b.npRepeat.alpha = 0.45f
                b.npRepeat.imageTintList = null
            }
        }
    }

    /** Shows the current playback speed; accent-tinted when not 1x. */
    private fun updateSpeedChip(speed: Float) {
        val b = _binding ?: return
        b.npSpeed.text = "${speed}x"   // floats print as 0.75 / 1.0 / 1.25 / 1.5 / 2.0
        val normal = kotlin.math.abs(speed - 1.0f) < 0.01f
        b.npSpeed.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (normal) R.color.text_secondary else R.color.accent
            )
        )
    }

    private fun updateSeekUI() {
        val c = controller ?: return
        val dur = c.duration.coerceAtLeast(0L)
        val pos = c.currentPosition.coerceAtLeast(0L).coerceAtMost(if (dur > 0) dur else Long.MAX_VALUE)
        if (dur > 0) {
            binding.npSeek.max = (dur / 1000L).toInt()
            binding.npSeek.progress = (pos / 1000L).toInt()
        }
        binding.npTimeElapsed.text = formatTime(pos)
        binding.npTimeRemaining.text = "-" + formatTime((dur - pos).coerceAtLeast(0L))
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000L
        val m = total / 60
        val s = total % 60
        return String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    // ------------------------------------------------------------------
    // Sleep timer
    // ------------------------------------------------------------------

    private fun showSleepTimerDialog() {
        // Open the doodle-styled bottom sheet.  When the user picks or cancels,
        // the callback updates the button tint so the active state reflects
        // immediately without waiting for any tick.
        SleepTimerBottomSheet().apply {
            onTimerChanged = { updateSleepTimerButton() }
        }.show(parentFragmentManager, "sleep_timer")
    }

    /** Tints the button accent when a timer is active, shows remaining time as tooltip. */
    private fun updateSleepTimerButton() {
        val b = _binding ?: return
        if (SleepTimerManager.isActive) {
            val remaining = formatTime(SleepTimerManager.remainingMs)
            b.btnSleepTimer.contentDescription = "Sleep timer: $remaining remaining"
            b.btnSleepTimer.imageTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.accent)
            b.btnSleepTimer.alpha = 1f
        } else {
            b.btnSleepTimer.contentDescription = "Sleep timer"
            b.btnSleepTimer.imageTintList = null
            b.btnSleepTimer.alpha = 0.5f
        }
    }

    // ------------------------------------------------------------------

    override fun onDestroyView() {
        handler.removeCallbacks(progressUpdater)
        vinylSpin?.cancel()
        vinylSpin = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onDestroyView()
        _binding = null
    }
}
