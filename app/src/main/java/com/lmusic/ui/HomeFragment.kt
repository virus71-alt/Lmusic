package com.lmusic.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.RoundedCornersTransformation
import com.lmusic.R
import com.lmusic.data.DownloadRecord
import com.lmusic.data.MusicLibrary
import com.lmusic.data.MusicTrack
import com.lmusic.data.RecommendationEngine
import com.lmusic.databinding.FragmentHomeBinding
import com.lmusic.download.DownloadRunner
import com.lmusic.plugin.PluginManager
import com.lmusic.plugin.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Home — YT Music-style landing page.
 *
 *   • "Your Weekly Mix"  — the auto-downloaded recommendation tracks
 *   • "Listen again"     — most recent library tracks
 *   • For-You shelves    — built by [RecommendationEngine] from the user's
 *                          favorite artists and categories (never random)
 *
 * Shelves are cached process-wide for 30 minutes so switching tabs doesn't
 * re-run YouTube searches every time.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val miniPlayer get() = MainNavigationFragment.find(this)?.globalMiniPlayer

    private var shelvesRequested = false

    companion object {
        private const val SHELF_CACHE_MS = 30L * 60 * 1000
        private var cachedShelves: List<RecommendationEngine.Shelf> = emptyList()
        private var cachedAt = 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvGreeting.text = greetingForNow()

        viewLifecycleOwner.lifecycleScope.launch {
            MusicLibrary(requireContext()).all().collectLatest { tracks ->
                renderWeeklyMix(tracks)
                renderListenAgain(tracks)
                maybeLoadShelves(tracks)
                binding.tvHomeHint.isVisible =
                    tracks.isEmpty() && cachedShelves.isEmpty()
            }
        }
    }

    private fun greetingForNow(): String =
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11  -> "Good morning"
            in 12..16 -> "Good afternoon"
            else      -> "Good evening"
        }

    // ─────────────────────────────────────────────────────────────────────
    // Weekly Mix
    // ─────────────────────────────────────────────────────────────────────

    private fun renderWeeklyMix(tracks: List<MusicTrack>) {
        val b = _binding ?: return
        val mix = tracks.filter {
            it.isAutoDownload && it.status == DownloadRecord.STATUS_OK && it.uri.isNotBlank()
        }.sortedByDescending { it.downloadedAt }

        b.sectionMix.isVisible = mix.isNotEmpty()
        if (mix.isEmpty()) return

        // Days until the soonest expiry, for the subtitle.
        val soonest = mix.mapNotNull { it.expiresAt }.minOrNull()
        if (soonest != null) {
            val days = TimeUnit.MILLISECONDS.toDays(
                (soonest - System.currentTimeMillis()).coerceAtLeast(0)
            ) + 1
            b.tvMixSub.text = "${mix.size} songs · refreshes in $days day${if (days == 1L) "" else "s"}"
        }

        b.btnMixPlayAll.setOnClickListener { miniPlayer?.playQueue(mix, 0) }

        b.rowMix.removeAllViews()
        for ((index, track) in mix.withIndex()) {
            b.rowMix.addView(buildTrackCard(b.rowMix, track, badge = "AUTO") {
                miniPlayer?.playQueue(mix, index)
            })
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Listen again
    // ─────────────────────────────────────────────────────────────────────

    private fun renderListenAgain(tracks: List<MusicTrack>) {
        val b = _binding ?: return
        val recent = tracks.filter {
            !it.isAutoDownload && it.status == DownloadRecord.STATUS_OK && it.uri.isNotBlank()
        }.sortedByDescending { it.downloadedAt }.take(10)

        b.sectionRecent.isVisible = recent.isNotEmpty()
        if (recent.isEmpty()) return

        b.rowRecent.removeAllViews()
        for ((index, track) in recent.withIndex()) {
            b.rowRecent.addView(buildTrackCard(b.rowRecent, track, badge = null) {
                miniPlayer?.playQueue(recent, index)
            })
        }
    }

    /** Card for a local library track (weekly mix / listen again). */
    private fun buildTrackCard(
        parent: ViewGroup, track: MusicTrack, badge: String?, onTap: () -> Unit
    ): View {
        val card = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_shelf_card, parent, false)

        card.findViewById<TextView>(R.id.tv_card_title).text = track.title
        card.findViewById<TextView>(R.id.tv_card_subtitle).text =
            track.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"

        val badgeView = card.findViewById<TextView>(R.id.tv_card_badge)
        if (badge != null) { badgeView.text = badge; badgeView.isVisible = true }

        val art = card.findViewById<ImageView>(R.id.iv_card_art)
        val model: Any? = when {
            !track.thumbnailUri.isNullOrBlank() && File(track.thumbnailUri).exists() ->
                File(track.thumbnailUri)
            track.albumId != null -> MusicLibrary.albumArtUri(track.albumId)
            else -> null
        }
        if (model != null) {
            art.load(model) {
                crossfade(true)
                size(280)
                scale(coil.size.Scale.FILL)
                transformations(RoundedCornersTransformation(20f))
                placeholder(R.drawable.bg_thumbnail_placeholder)
                error(R.drawable.bg_thumbnail_placeholder)
            }
        }

        card.setOnClickListener { onTap() }
        return card
    }

    // ─────────────────────────────────────────────────────────────────────
    // For-You shelves (RecommendationEngine)
    // ─────────────────────────────────────────────────────────────────────

    private fun maybeLoadShelves(tracks: List<MusicTrack>) {
        val fresh = System.currentTimeMillis() - cachedAt < SHELF_CACHE_MS
        if (cachedShelves.isNotEmpty() && fresh) {
            renderShelves(cachedShelves)
            return
        }
        if (shelvesRequested || tracks.isEmpty()) return
        shelvesRequested = true

        binding.shelvesLoading.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val shelves = withContext(Dispatchers.IO) {
                runCatching {
                    RecommendationEngine(requireContext().applicationContext)
                        .buildShelves(tracks)
                }.getOrDefault(emptyList())
            }
            if (shelves.isNotEmpty()) {
                cachedShelves = shelves
                cachedAt = System.currentTimeMillis()
            }
            _binding?.shelvesLoading?.isVisible = false
            renderShelves(shelves)
        }
    }

    private fun renderShelves(shelves: List<RecommendationEngine.Shelf>) {
        val b = _binding ?: return
        val container = b.shelvesContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        for (shelf in shelves) {
            // Header
            val header = TextView(requireContext()).apply {
                text = shelf.title
                textSize = 19f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(requireContext().getColor(R.color.text_primary))
                setPadding(dp(20), dp(28), dp(20), 0)
            }
            container.addView(header)

            // Horizontal row of cards
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(20), 0, dp(8), 0)
            }
            val scroll = HorizontalScrollView(requireContext()).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
                setPadding(0, dp(12), 0, 0)
            }
            container.addView(scroll)

            for (item in shelf.items) {
                val card = inflater.inflate(R.layout.item_shelf_card, row, false)
                card.findViewById<TextView>(R.id.tv_card_title).text = item.title
                card.findViewById<TextView>(R.id.tv_card_subtitle).text =
                    item.channel ?: "Unknown"
                card.findViewById<ImageView>(R.id.iv_card_art).load(item.thumbnailUrl) {
                    crossfade(true)
                    size(280)
                    scale(coil.size.Scale.FILL)
                    transformations(RoundedCornersTransformation(20f))
                    placeholder(R.drawable.bg_thumbnail_placeholder)
                    error(R.drawable.bg_thumbnail_placeholder)
                }
                val dl = card.findViewById<ImageButton>(R.id.btn_card_download)
                dl.isVisible = true
                dl.setOnClickListener {
                    DownloadRunner.start(requireContext(), item.videoUrl)
                    Toast.makeText(requireContext(),
                        "Downloading \"${item.title.take(40)}\"", Toast.LENGTH_SHORT).show()
                }
                card.setOnClickListener { streamRecommendation(item) }
                row.addView(card)
            }
        }
    }

    /** Resolves a recommendation to a CDN stream and plays it instantly. */
    private fun streamRecommendation(item: SearchResult) {
        val mp = miniPlayer ?: return
        Toast.makeText(requireContext(),
            "Loading \"${item.title.take(40)}\"…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    val plugin = PluginManager.findFor(item.videoUrl) ?: return@runCatching null
                    val ex = plugin.extract(item.videoUrl)
                    val best = ex.streams.filter { it.isAudioOnly }.ifEmpty { ex.streams }
                        .maxByOrNull { it.bitrateKbps } ?: return@runCatching null
                    Triple(best.url, ex.title to ex.channel, ex.thumbnailUrl)
                }.getOrNull()
            }
            if (resolved == null) {
                Toast.makeText(requireContext(),
                    "Couldn't load that song — try again", Toast.LENGTH_SHORT).show()
                return@launch
            }
            mp.playFromStream(
                resolved.first,
                resolved.second.first,
                resolved.second.second,
                resolved.third
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
