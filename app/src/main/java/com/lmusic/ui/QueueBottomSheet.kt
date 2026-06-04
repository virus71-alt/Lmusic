package com.lmusic.ui

import android.content.ComponentName
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.common.util.concurrent.MoreExecutors
import com.lmusic.R
import com.lmusic.databinding.BottomSheetQueueBinding
import com.lmusic.databinding.ItemQueueBinding
import com.lmusic.player.PlaybackService

/**
 * "Up next" sheet shown when the user taps the queue icon on Now Playing.
 *
 * Binds its OWN [MediaController] to the running [PlaybackService] so it works
 * regardless of which screen opened it (Now Playing destroys the nav host's
 * view, so we can't rely on the mini-player's controller being alive).
 */
class QueueBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetQueueBinding? = null
    private val binding get() = _binding!!

    private var controller: MediaController? = null
    private lateinit var adapter: QueueAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = QueueAdapter { index ->
            controller?.seekToDefaultPosition(index)
            controller?.play()
            refresh()
        }
        binding.rvQueue.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQueue.adapter = adapter

        // Bind directly to the running MediaSession — same approach as NowPlayingFragment.
        val ctx = requireContext().applicationContext
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            // The dialog may have been dismissed by the time the controller is ready.
            if (_binding == null) {
                runCatching { future.get().release() }
                return@addListener
            }
            controller = runCatching { future.get() }.getOrNull()?.also {
                it.addListener(playerListener)
                refresh()
            }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) = refresh()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = refresh()
    }

    private fun refresh() {
        val b = _binding ?: return
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            b.rvQueue.isVisible = false
            b.tvQueueEmpty.isVisible = true
            b.tvQueueCount.text = ""
            return
        }
        b.tvQueueEmpty.isVisible = false
        b.rvQueue.isVisible = true

        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        b.tvQueueCount.text = "${items.size} TRACKS"
        adapter.submit(items, c.currentMediaItemIndex)
    }

    override fun onDestroyView() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onDestroyView()
        _binding = null
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────

private class QueueAdapter(
    private val onTap: (index: Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private var items: List<MediaItem> = emptyList()
    private var playingIndex: Int = -1

    fun submit(list: List<MediaItem>, currentIndex: Int) {
        items = list
        playingIndex = currentIndex
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], position == playingIndex, position)

    inner class VH(private val b: ItemQueueBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: MediaItem, isPlaying: Boolean, position: Int) = with(b) {
            val md = item.mediaMetadata
            tvTitle.text  = md.title ?: "Unknown track"
            tvArtist.text = md.artist ?: "Unknown artist"

            ivPlayingDot.visibility = if (isPlaying) View.VISIBLE else View.INVISIBLE
            tvTitle.setTextColor(
                root.context.getColor(
                    if (isPlaying) R.color.accent else R.color.text_primary
                )
            )

            val art = md.artworkUri
            if (art != null) {
                ivThumbnail.load(art) {
                    crossfade(true)
                    size(120)
                    scale(coil.size.Scale.FILL)
                    transformations(RoundedCornersTransformation(8f))
                    placeholder(R.drawable.bg_thumbnail_placeholder)
                    error(R.drawable.bg_thumbnail_placeholder)
                }
            } else {
                ivThumbnail.setImageDrawable(null)
                ivThumbnail.setBackgroundResource(R.drawable.bg_thumbnail_placeholder)
            }

            root.setOnClickListener { onTap(position) }
        }
    }
}
