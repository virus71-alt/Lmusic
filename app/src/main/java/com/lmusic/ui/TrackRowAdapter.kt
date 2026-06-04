package com.lmusic.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import com.lmusic.R
import com.lmusic.data.MusicLibrary
import com.lmusic.data.MusicTrack
import com.lmusic.databinding.ItemTrackRowBinding
import java.io.File

/**
 * Compact track-row adapter used by [FavoritesFragment].
 *
 * Uses [ListAdapter] + [DiffUtil] so updates only rebind the rows that actually
 * changed (e.g. a single favorite toggle doesn't rebind the entire list).
 *
 * Image loads explicitly request a 128×128 px size so Coil decodes the
 * thumbnail at the correct scale instead of the full source resolution.
 */
class TrackRowAdapter(
    private val onPlay: (track: MusicTrack, queue: List<MusicTrack>) -> Unit,
    private val onToggleFav: (MusicTrack) -> Unit,
    private val isFav: (String) -> Boolean
) : ListAdapter<MusicTrack, TrackRowAdapter.VH>(DIFF) {

    /** Snapshot of the current list, used to build a queue for playback. */
    private val current: List<MusicTrack> get() = currentList

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTrackRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemTrackRowBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(t: MusicTrack) = with(b) {
            tvTitle.text  = t.title
            tvArtist.text = t.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"

            // ── Thumbnail (explicit 104dp ~ 104px size to avoid full-res decode) ──
            val thumbFile = t.thumbnailUri?.takeIf { it.isNotBlank() }?.let { File(it) }
            val model: Any? = when {
                thumbFile != null && thumbFile.exists() -> thumbFile
                t.albumId != null -> MusicLibrary.albumArtUri(t.albumId)
                else -> null
            }
            if (model != null) {
                ivThumbnail.load(model) {
                    crossfade(true)
                    size(THUMB_PX)
                    scale(Scale.FILL)
                    transformations(RoundedCornersTransformation(10f))
                    placeholder(R.drawable.bg_thumbnail_placeholder)
                    error(R.drawable.bg_thumbnail_placeholder)
                }
            } else {
                ivThumbnail.setImageDrawable(null)
                ivThumbnail.setBackgroundResource(R.drawable.bg_thumbnail_placeholder)
            }

            // ── Favorite heart ──
            val fav = isFav(t.id)
            btnFav.setImageResource(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            btnFav.setColorFilter(
                ContextCompat.getColor(
                    root.context,
                    if (fav) R.color.accent else R.color.text_tertiary
                )
            )
            btnFav.setOnClickListener { onToggleFav(t) }

            val play = { onPlay(t, current) }
            btnPlay.setOnClickListener { play() }
            root.setOnClickListener  { play() }
        }
    }

    companion object {
        /** Thumbnail target size in px — comfortably above the 52dp ImageView slot. */
        private const val THUMB_PX = 128

        val DIFF = object : DiffUtil.ItemCallback<MusicTrack>() {
            override fun areItemsTheSame(a: MusicTrack, b: MusicTrack): Boolean =
                a.id == b.id
            override fun areContentsTheSame(a: MusicTrack, b: MusicTrack): Boolean =
                a == b
        }
    }
}
