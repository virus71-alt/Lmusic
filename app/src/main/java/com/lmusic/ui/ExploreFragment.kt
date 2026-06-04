package com.lmusic.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.lmusic.R
import com.lmusic.databinding.FragmentExploreBinding
import com.lmusic.databinding.ItemSearchResultBinding
import com.lmusic.download.DownloadRunner
import com.lmusic.plugin.PluginManager
import com.lmusic.plugin.SearchResult
import com.lmusic.plugin.SearchablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explore screen — search YouTube + genre quick-pick chips.
 *
 * Three-state UI so results never flicker or disappear:
 *   IDLE    → empty state graphic
 *   LOADING → spinner (results underneath stay visible)
 *   RESULTS → RecyclerView populated
 */
class ExploreFragment : Fragment() {

    private var _binding: FragmentExploreBinding? = null
    private val binding get() = _binding!!

    /** Shared mini-player owned by [MainNavigationFragment]. */
    private val miniPlayer get() = MainNavigationFragment.find(this)?.globalMiniPlayer

    /**
     * Last resolved results.  Kept in memory so tab-switching or tapping play
     * never wipes the list out of view.
     */
    private var lastResults: List<SearchResult> = emptyList()

    private val adapter = SearchResultAdapter(
        onPlayStream = ::playStream,
        onDownload   = ::downloadTrack
    )

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExploreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvResults.adapter = adapter
        binding.rvResults.setHasFixedSize(true)
        binding.rvResults.setItemViewCacheSize(8)

        // Submit on Enter / IME Search action — closes suggestions too.
        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                hideSuggestions()
                doSearch(binding.etSearch.text?.toString().orEmpty()); true
            } else false
        }

        // As the user types: clear button toggle + debounced suggestion fetch
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnSearch.isVisible = !s.isNullOrEmpty()
                scheduleSuggestionFetch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // X button clears search and resets to idle
        binding.btnSearch.setOnClickListener {
            binding.etSearch.text?.clear()
            lastResults = emptyList()
            adapter.submitList(emptyList())
            hideSuggestions()
            renderState()
        }

        buildGenreChips()
        renderState()
    }

    override fun onDestroyView() {
        // miniPlayer is owned by MainNavigationFragment — do NOT release here.
        suggestionRunnable?.let { suggestionHandler.removeCallbacks(it) }
        suggestionRunnable = null
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Live search suggestions (300ms debounce → plugin.getSuggestions)
    // ─────────────────────────────────────────────────────────────────────

    private val suggestionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var suggestionRunnable: Runnable? = null

    private fun scheduleSuggestionFetch(query: String) {
        // Cancel any pending fetch
        suggestionRunnable?.let { suggestionHandler.removeCallbacks(it) }
        if (query.isBlank() || query.length < 2) {
            hideSuggestions()
            return
        }
        val r = Runnable { loadSuggestions(query) }
        suggestionRunnable = r
        suggestionHandler.postDelayed(r, 300)   // 300ms debounce
    }

    private fun loadSuggestions(query: String) {
        lifecycleScope.launch {
            val suggestions = withContext(Dispatchers.IO) {
                PluginManager.all
                    .filterIsInstance<SearchablePlugin>()
                    .firstOrNull()
                    ?.getSuggestions(query)
                    ?: emptyList()
            }
            // The user may have typed more / cleared since the fetch started.
            // Only render if the current text still starts with what we queried.
            val currentText = _binding?.etSearch?.text?.toString().orEmpty()
            if (currentText.startsWith(query) && currentText.isNotEmpty()) {
                renderSuggestions(suggestions)
            }
        }
    }

    private fun renderSuggestions(suggestions: List<String>) {
        val b = _binding ?: return
        val container = b.suggestionsContainer
        container.removeAllViews()
        if (suggestions.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        for (s in suggestions.take(6)) {
            val row = inflater.inflate(R.layout.view_suggestion_row, container, false)
            row.findViewById<android.widget.TextView>(R.id.tv_suggestion).text = s
            row.setOnClickListener {
                b.etSearch.setText(s)
                b.etSearch.setSelection(s.length)
                hideSuggestions()
                doSearch(s)
            }
            container.addView(row)
        }
        container.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        _binding?.suggestionsContainer?.removeAllViews()
        _binding?.suggestionsContainer?.visibility = View.GONE
    }

    /** Called by [MainNavigationFragment] when the Search tab is tapped. */
    fun focusSearch() {
        val b = _binding ?: return
        b.etSearch.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(b.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }


    // ─────────────────────────────────────────────────────────────────────
    // Genre chips
    // ─────────────────────────────────────────────────────────────────────

    private val genres = listOf(
        "Top hits"  to "top music hits 2024",
        "Lofi"      to "lofi chill beats",
        "Pop"       to "pop music",
        "Hip Hop"   to "hip hop music",
        "Rock"      to "rock music",
        "EDM"       to "edm electronic dance",
        "Chill"     to "chill music",
        "Workout"   to "workout music",
        "Bollywood" to "bollywood hits",
        "Indie"     to "indie music"
    )

    private fun buildGenreChips() {
        val row = binding.chipRow
        row.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for ((label, query) in genres) {
            val chip = inflater.inflate(R.layout.view_genre_chip, row, false) as TextView
            chip.text = label
            chip.setOnClickListener {
                binding.etSearch.setText(query)
                binding.etSearch.setSelection(query.length)
                doSearch(query)
            }
            row.addView(chip)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // State rendering
    // ─────────────────────────────────────────────────────────────────────

    private fun renderState(loading: Boolean = false) {
        val b = _binding ?: return
        val hasResults = lastResults.isNotEmpty()
        b.progressBar.isVisible = loading
        b.rvResults.isVisible   = hasResults
        b.emptyState.isVisible  = !hasResults && !loading
    }

    // ─────────────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────────────

    private fun doSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        renderState(loading = true)
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                PluginManager.all
                    .filterIsInstance<SearchablePlugin>()
                    .firstOrNull()
                    ?.search(q)
                    ?: emptyList()
            }
            lastResults = results
            adapter.submitList(results)
            if (results.isEmpty()) {
                showEmptyHint("No results", "No tracks found for \"$q\"", R.drawable.ic_search)
            }
            renderState(loading = false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Playback — resolves stream + builds a queue from surrounding results
    // ─────────────────────────────────────────────────────────────────────

    private fun playStream(result: SearchResult) {
        val mp = miniPlayer ?: run {
            Toast.makeText(requireContext(), "Player not ready", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            requireContext(),
            "Loading \"${result.title.take(40)}\"…",
            Toast.LENGTH_SHORT
        ).show()

        lifecycleScope.launch {
            // 1. Resolve the tapped track first
            val resolved = withContext(Dispatchers.IO) { resolveStream(result) }
            if (resolved == null) {
                Toast.makeText(requireContext(),
                    "Couldn't resolve stream — try again", Toast.LENGTH_SHORT).show()
                return@launch
            }
            mp.playFromStream(resolved.url, resolved.title, resolved.channel, resolved.thumbUrl)

            // 2. Queue the next few results in the background (enables Shuffle / Next)
            val tappedIdx = lastResults.indexOfFirst { it.videoId == result.videoId }
            val window = if (tappedIdx >= 0)
                lastResults.drop(tappedIdx + 1).take(QUEUE_PRELOAD)
            else emptyList()
            if (window.isNotEmpty()) {
                val nextStreams = withContext(Dispatchers.IO) {
                    window.map { r -> async { resolveStream(r) } }
                        .awaitAll()
                        .filterNotNull()
                }
                for (s in nextStreams) mp.appendStreamToQueue(s.url, s.title, s.channel, s.thumbUrl)
            }
        }
    }

    private data class ResolvedStream(
        val url: String, val title: String, val channel: String?, val thumbUrl: String?
    )

    private fun resolveStream(r: SearchResult): ResolvedStream? = runCatching {
        val plugin = PluginManager.findFor(r.videoUrl) ?: return null
        val ex = plugin.extract(r.videoUrl)
        val best = ex.streams.filter { it.isAudioOnly }.ifEmpty { ex.streams }
            .maxByOrNull { it.bitrateKbps } ?: return null
        ResolvedStream(best.url, ex.title, ex.channel, ex.thumbnailUrl)
    }.getOrNull()

    private fun downloadTrack(result: SearchResult) {
        DownloadRunner.start(requireContext(), result.videoUrl)
        Toast.makeText(requireContext(),
            "Download started for \"${result.title.take(40)}\"", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun showEmptyHint(title: String, sub: String, iconRes: Int) {
        val b = _binding ?: return
        b.tvEmptyTitle.text = title
        b.tvEmptySub.text   = sub
        b.ivEmptyIcon.setImageResource(iconRes)
    }

    companion object {
        private const val QUEUE_PRELOAD = 4
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────

private class SearchResultAdapter(
    private val onPlayStream: (SearchResult) -> Unit,
    private val onDownload:   (SearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private var items: List<SearchResult> = emptyList()

    fun submitList(list: List<SearchResult>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(r: SearchResult) = with(b) {
            tvTitle.text   = r.title
            tvChannel.text = r.channel ?: "Unknown"
            tvDuration.visibility = View.GONE

            ivThumbnail.load(r.thumbnailUrl) {
                crossfade(true)
                size(160)                                    // ~64dp target
                scale(coil.size.Scale.FILL)
                transformations(RoundedCornersTransformation(8f))
                placeholder(R.drawable.bg_thumbnail_placeholder)
                error(R.drawable.bg_thumbnail_placeholder)
            }

            btnPlayStream.setImageResource(R.drawable.ic_play)
            btnPlayStream.setOnClickListener { onPlayStream(r) }
            btnDownload.visibility = View.VISIBLE
            btnDownload.setOnClickListener { onDownload(r) }
            root.setOnClickListener { onPlayStream(r) }
        }
    }
}
