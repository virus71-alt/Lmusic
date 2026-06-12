package com.lmusic.ui

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.common.util.concurrent.MoreExecutors
import com.lmusic.R
import com.lmusic.data.LyricsRepository
import com.lmusic.databinding.BottomSheetLyricsBinding
import com.lmusic.player.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lyrics sheet for the currently playing track.
 *
 * Fetches lyrics from [LyricsRepository] (LRCLIB).  When time-synced lyrics are
 * available, the current line is highlighted in the accent color and the sheet
 * auto-scrolls to keep it centered; tapping a line seeks the player there.
 * Plain lyrics render as a simple scrollable block.
 */
class LyricsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLyricsBinding? = null
    private val binding get() = _binding!!

    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())

    private var syncedLines: List<LyricsRepository.SyncedLine> = emptyList()
    private var lineViews: List<TextView> = emptyList()
    private var highlightedIndex = -1

    private val syncTicker = object : Runnable {
        override fun run() {
            highlightCurrentLine()
            handler.postDelayed(this, 400L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext().applicationContext
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            if (_binding == null) { runCatching { future.get().release() }; return@addListener }
            controller = future.get()
            loadLyricsForCurrentTrack()
        }, MoreExecutors.directExecutor())
    }

    private fun loadLyricsForCurrentTrack() {
        val item = controller?.currentMediaItem
        val md = item?.mediaMetadata
        val title = md?.title?.toString()
        if (title.isNullOrBlank()) {
            showEmpty("Nothing is playing right now")
            return
        }
        binding.tvLyricsTrack.text = title
        val artist = md.artist?.toString()
        val durationSec = (controller?.duration ?: 0L).coerceAtLeast(0L) / 1000L

        viewLifecycleOwner.lifecycleScope.launch {
            val lyrics = withContext(Dispatchers.IO) {
                runCatching { LyricsRepository.fetch(title, artist, durationSec) }
                    .getOrDefault(LyricsRepository.Lyrics.NONE)
            }
            if (_binding == null) return@launch
            binding.lyricsLoading.isVisible = false
            when {
                lyrics.hasSynced -> renderSynced(lyrics.synced)
                lyrics.plain != null -> renderPlain(lyrics.plain)
                else -> showEmpty("No lyrics found for this track")
            }
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun renderSynced(lines: List<LyricsRepository.SyncedLine>) {
        syncedLines = lines
        val container = binding.lyricsLines
        container.removeAllViews()
        val views = ArrayList<TextView>(lines.size)
        for ((index, line) in lines.withIndex()) {
            val tv = makeLineView(line.text.ifBlank { "♪" })
            tv.setOnClickListener {
                controller?.seekTo(syncedLines[index].timeMs)
            }
            container.addView(tv)
            views += tv
        }
        lineViews = views
        binding.lyricsScroll.isVisible = true
        handler.post(syncTicker)
    }

    private fun renderPlain(plain: String) {
        val container = binding.lyricsLines
        container.removeAllViews()
        for (line in plain.lines()) {
            container.addView(makeLineView(line.ifBlank { " " }))
        }
        binding.lyricsScroll.isVisible = true
    }

    private fun makeLineView(text: String): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 17f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 10, 0, 10)
            setLineSpacing(0f, 1.15f)
        }

    private fun showEmpty(message: String) {
        binding.lyricsLoading.isVisible = false
        binding.tvLyricsEmpty.text = message
        binding.tvLyricsEmpty.isVisible = true
    }

    // ── Sync highlight ───────────────────────────────────────────────────────

    private fun highlightCurrentLine() {
        val b = _binding ?: return
        val pos = controller?.currentPosition ?: return
        if (syncedLines.isEmpty()) return

        // Last line whose timestamp has passed.
        var idx = syncedLines.indexOfLast { it.timeMs <= pos }
        if (idx == highlightedIndex) return

        val accent = ContextCompat.getColor(requireContext(), R.color.accent)
        val normal = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        lineViews.getOrNull(highlightedIndex)?.apply {
            setTextColor(normal)
            setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        lineViews.getOrNull(idx)?.apply {
            setTextColor(accent)
            setTypeface(null, android.graphics.Typeface.BOLD)
            // Keep the active line vertically centered in the sheet.
            val targetY = (top + height / 2 - b.lyricsScroll.height / 2).coerceAtLeast(0)
            b.lyricsScroll.smoothScrollTo(0, targetY)
        }
        highlightedIndex = idx
    }

    override fun onDestroyView() {
        handler.removeCallbacks(syncTicker)
        controller?.release()
        controller = null
        super.onDestroyView()
        _binding = null
    }
}
