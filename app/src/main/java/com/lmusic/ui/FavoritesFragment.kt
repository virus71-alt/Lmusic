package com.lmusic.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.lmusic.data.FavoritesStore
import com.lmusic.data.MusicLibrary
import com.lmusic.data.MusicTrack
import com.lmusic.databinding.FragmentFavoritesBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Favorites tab — shows every library track whose id is in [FavoritesStore].
 * Un-hearting a row removes it from the list immediately.
 */
class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private val favorites by lazy { FavoritesStore(requireContext()) }
    private val miniPlayer get() = MainNavigationFragment.find(this)?.globalMiniPlayer

    private lateinit var adapter: TrackRowAdapter
    private var allTracks: List<MusicTrack> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TrackRowAdapter(
            onPlay = { track, queue ->
                miniPlayer?.load(track, queue)
                    ?: Snackbar.make(binding.root, "Player not ready", Snackbar.LENGTH_SHORT).show()
            },
            onToggleFav = { track ->
                favorites.toggle(track.id)
                refresh()                 // drops it out of the favorites list
            },
            isFav = { id -> favorites.isFavorite(id) }
        )
        binding.rvFavorites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFavorites.adapter = adapter
        binding.rvFavorites.setHasFixedSize(true)
        binding.rvFavorites.setItemViewCacheSize(8)

        viewLifecycleOwner.lifecycleScope.launch {
            MusicLibrary(requireContext()).all().collectLatest { tracks ->
                allTracks = tracks
                refresh()
            }
        }
    }

    private fun refresh() {
        val favIds = favorites.all()
        val favTracks = allTracks.filter { it.id in favIds }
        adapter.submitList(favTracks)
        binding.tvFavSubtitle.text =
            if (favTracks.isEmpty()) "Tracks you love"
            else "${favTracks.size} ${if (favTracks.size == 1) "track" else "tracks"}"
        binding.emptyState.isVisible = favTracks.isEmpty()
        binding.rvFavorites.isVisible = favTracks.isNotEmpty()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
