package com.lmusic.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import com.lmusic.R
import com.lmusic.data.FavoritesStore
import com.lmusic.data.LibraryStats
import com.lmusic.data.MusicLibrary
import com.lmusic.data.MusicTrack
import com.lmusic.data.Settings
import com.lmusic.databinding.FragmentProfileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Profile tab — library insights + small preferences row.
 *
 * Reactive: collects [MusicLibrary.all] so every section refreshes whenever
 * the library changes (download completes, file removed, etc.).
 * Every stat is derived — no extra database needed.
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val favorites by lazy { FavoritesStore(requireContext()) }
    private val settings  by lazy { Settings(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Notifications toggle ─────────────────────────────────────────
        binding.swNotifications.isChecked = settings.downloadNotifications
        binding.swNotifications.setOnCheckedChangeListener { _, on ->
            settings.downloadNotifications = on
            binding.tvNotifSub.text =
                if (on) "Show progress in the shade while downloading"
                else    "Silent downloads — no notifications shown"
        }
        binding.rowNotifications.setOnClickListener {
            binding.swNotifications.isChecked = !binding.swNotifications.isChecked
        }

        // ── Tap on featured fav row → jump to Favs tab ──────────────────
        binding.cardFavFeatured.setOnClickListener {
            // The MainNavigationFragment is our parent; switch to FAVS tab.
            val nav = MainNavigationFragment.find(this) ?: return@setOnClickListener
            // Best effort — find the bottom nav item for Favs and click it.
            // (No public selectDest, so we trigger via the bottom-nav child layout.)
            switchToFavsTab(nav)
        }

        // ── Live library stream → recompute everything on each emission ──
        viewLifecycleOwner.lifecycleScope.launch {
            MusicLibrary(requireContext()).all().collectLatest { tracks ->
                val stats = withContext(Dispatchers.Default) { LibraryStats.compute(tracks) }
                renderFavSection(tracks)
                renderArtistMiniCards(stats.topArtists)
                renderListenerStats(stats)
                renderWeekChart(stats.weekdays)
                renderTopArtists(stats.topArtists)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Renderers
    // ─────────────────────────────────────────────────────────────────────

    private fun renderFavSection(tracks: List<MusicTrack>) {
        val favIds  = favorites.all()
        val favList = tracks.filter { it.id in favIds }.sortedByDescending { it.downloadedAt }
        val featured = favList.firstOrNull()

        binding.tvFavCountChip.text = "${favList.size} fav${if (favList.size == 1) "" else "s"}"

        if (featured == null) {
            binding.tvFavTitle.text  = "No favorites yet"
            binding.tvFavArtist.text = "Heart any track to see it here"
            binding.ivFavThumb.setImageDrawable(null)
            binding.ivFavThumb.setBackgroundResource(R.drawable.bg_thumbnail_placeholder)
            return
        }

        binding.tvFavTitle.text  = featured.title
        binding.tvFavArtist.text = featured.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"

        val thumbModel: Any? = when {
            !featured.thumbnailUri.isNullOrBlank() && File(featured.thumbnailUri).exists() ->
                File(featured.thumbnailUri)
            featured.albumId != null -> MusicLibrary.albumArtUri(featured.albumId)
            else -> null
        }
        if (thumbModel != null) {
            binding.ivFavThumb.load(thumbModel) {
                crossfade(true)
                size(240)
                scale(Scale.FILL)
                transformations(RoundedCornersTransformation(14f))
                placeholder(R.drawable.bg_thumbnail_placeholder)
                error(R.drawable.bg_thumbnail_placeholder)
            }
        } else {
            binding.ivFavThumb.setImageDrawable(null)
            binding.ivFavThumb.setBackgroundResource(R.drawable.bg_thumbnail_placeholder)
        }
    }

    private fun renderArtistMiniCards(top: List<LibraryStats.ArtistBucket>) {
        val a1 = top.getOrNull(0)
        val a2 = top.getOrNull(1)
        binding.tvArtist1Name.text = a1?.name ?: "—"
        binding.tvArtist1Sub.text  = a1?.let { "${it.trackCount} TRACKS" } ?: "NO PLAYS"
        binding.tvArtist2Name.text = a2?.name ?: "—"
        binding.tvArtist2Sub.text  = a2?.let { "${it.trackCount} TRACKS" } ?: "NO PLAYS"
    }

    private fun renderListenerStats(stats: LibraryStats.Snapshot) {
        binding.tvStatHours.text   = stats.totalHours.toString()
        binding.tvStatArtists.text = stats.distinctArtists.toString()
    }

    /**
     * Builds 7 vertical bars proportional to the largest day's count.
     * Each bar is a peach rectangle with a rounded top.
     */
    private fun renderWeekChart(days: List<LibraryStats.WeekdayBucket>) {
        val row = binding.rowWeekBars
        row.removeAllViews()

        val max = days.maxOf { it.count }.coerceAtLeast(1)
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val maxBarPx = (72 * density).toInt()     // 80dp container - 8dp padding
        val minBarPx = (4  * density).toInt()
        val gap      = (4  * density).toInt()

        for ((idx, bucket) in days.withIndex()) {
            val barHeightPx =
                if (bucket.count == 0) minBarPx
                else (minBarPx + (maxBarPx - minBarPx) * bucket.count / max)

            val bar = View(ctx).apply {
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_week_bar)
            }
            val lp = LinearLayout.LayoutParams(0, barHeightPx, 1f).apply {
                marginStart = if (idx == 0) 0 else gap
            }
            bar.layoutParams = lp
            row.addView(bar)
        }
    }

    private fun renderTopArtists(top: List<LibraryStats.ArtistBucket>) {
        val list = binding.listTopArtists
        list.removeAllViews()
        if (top.isEmpty()) {
            binding.tvTopEmpty.isVisible = true
            return
        }
        binding.tvTopEmpty.isVisible = false

        val inflater = LayoutInflater.from(requireContext())
        for (bucket in top) {
            val row = inflater.inflate(R.layout.row_top_artist, list, false)
            row.findViewById<TextView>(R.id.tv_percent).text = "${bucket.percent}%"
            row.findViewById<TextView>(R.id.tv_artist).text  = bucket.name
            row.findViewById<TextView>(R.id.tv_count).text   = "${bucket.trackCount} tracks"

            // Width-weighted fill bar
            val fill = row.findViewById<View>(R.id.bar_fill)
            (fill.layoutParams as LinearLayout.LayoutParams).weight =
                bucket.percent.toFloat().coerceAtLeast(1f)
            val empty = row.findViewById<View>(R.id.bar_empty)
            (empty.layoutParams as LinearLayout.LayoutParams).weight =
                (100 - bucket.percent).toFloat().coerceAtLeast(0f)

            list.addView(row)
        }
    }

    /** Find the Favs nav item in the bottom-nav bar and click it. */
    private fun switchToFavsTab(nav: MainNavigationFragment) {
        val bar = nav.view?.findViewById<LinearLayout>(R.id.bottom_nav) ?: return
        // Search/Library/Favs/Profile — Favs is index 2 in the current 4-tab layout.
        bar.getChildAt(2)?.performClick()
    }

    override fun onResume() {
        super.onResume()
        // Live Flow already refreshes — nothing to do.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
