package com.lmusic.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.snackbar.Snackbar
import com.lmusic.R
import com.lmusic.data.DownloadDatabase
import com.lmusic.data.DownloadRecord
import com.lmusic.data.FavoritesStore
import com.lmusic.data.MusicLibrary
import com.lmusic.data.MusicTrack
import com.lmusic.data.Settings
import com.lmusic.databinding.FragmentDownloadHistoryBinding
import com.lmusic.databinding.ItemDownloadBinding
import com.lmusic.databinding.ItemGroupHeaderBinding
import com.lmusic.databinding.LayoutMiniPlayerBinding
import com.lmusic.download.DownloadRunner
import com.lmusic.player.MiniPlayerController
import com.lmusic.util.StorageUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadHistoryFragment : Fragment() {

    private var _binding: FragmentDownloadHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: DownloadAdapter

    /** Shared mini-player owned by [MainNavigationFragment]. Null if hosted outside of it. */
    private val miniPlayer: MiniPlayerController?
        get() = MainNavigationFragment.find(this)?.globalMiniPlayer

    private val favorites by lazy { FavoritesStore(requireContext()) }

    private var allTracks: List<MusicTrack> = emptyList()
    private var searchQuery: String = ""

    /** Active category filter; null = All. */
    private var categoryFilter: String? = null

    // Selection state (by MusicTrack.id which is stable across rebuilds)
    private val selectedIds = mutableSetOf<String>()
    private val selectionMode get() = selectedIds.isNotEmpty()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DownloadAdapter(
            onPlay = { track ->
                if (selectionMode) toggleSelect(track)
                else if (track.status == DownloadRecord.STATUS_FAILED) retryDownload(track)
                else playTrack(track)
            },
            onLongPress  = { track -> toggleSelect(track) },
            onDelete     = { track -> confirmDelete(track) },
            onRetry      = { track -> retryDownload(track) },
            onToggleFav  = { track ->
                val nowFav = favorites.toggle(track.id)
                // Hearting a weekly-mix track keeps it forever (no 7-day expiry).
                val recordId = track.downloadRecordId
                if (nowFav && track.isAutoDownload && recordId != null) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        DownloadDatabase.get(requireContext())
                            .downloadDao().promoteToPermanent(recordId)
                        Snackbar.make(binding.root,
                            "Saved to your library — won't expire", Snackbar.LENGTH_SHORT).show()
                    }
                }
                // Rebind only this row instead of the whole list.
                val pos = adapter.currentList.indexOfFirst {
                    it is Row.Item && it.track.id == track.id
                }
                if (pos >= 0) adapter.notifyItemChanged(pos)
            },
            isFavorite       = { id -> favorites.isFavorite(id) },
            isSelected       = { id -> selectedIds.contains(id) },
            inSelectionMode  = { selectionMode }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.setItemViewCacheSize(8)

        binding.btnSettings.setOnClickListener {
            // Library is a child of MainNavigationFragment — escape up to the
            // activity's FragmentManager to push the fullscreen Settings host.
            requireActivity().supportFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_up, R.anim.fade_out, R.anim.fade_in, R.anim.slide_down)
                .replace(R.id.fragment_container, SettingsHostFragment())
                .addToBackStack("settings")
                .commit()
        }

        binding.btnSort.setOnClickListener { v -> showSortMenu(v) }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                applyFilterAndSort()
            }
        })

        // Selection toolbar wiring
        binding.btnSelectionClose.setOnClickListener { exitSelectionMode() }
        binding.btnSelectionSelectAll.setOnClickListener {
            selectedIds.clear()
            selectedIds.addAll(currentVisibleRecords().map { it.id })
            updateSelectionUI()
            adapter.notifyDataSetChanged()
        }
        binding.btnSelectionDelete.setOnClickListener { confirmBulkDelete() }

        // Inline sort/group chips — echo's pattern of always-visible filters.
        rebuildLibraryChips()

        // Show the empty-state graphic immediately so we never render a black
        // void while the MusicLibrary flow is still scanning MediaStore.  Real
        // content overwrites this within ms once the first emission arrives.
        binding.recyclerView.isVisible = false
        binding.emptyState.isVisible   = true
        binding.tvEmptyTitle.text      = "Loading your music…"
        binding.tvEmptySubtitle.text   = "Scanning device library"

        viewLifecycleOwner.lifecycleScope.launch {
            MusicLibrary(requireContext()).all().collectLatest { tracks ->
                allTracks = tracks
                selectedIds.retainAll(tracks.map { it.id }.toSet())
                applyFilterAndSort()
                updateSubtitle(tracks)
                rebuildLibraryChips()
            }
        }
    }

    // ----- Selection mode -----

    private fun toggleSelect(track: MusicTrack) {
        if (selectedIds.contains(track.id)) selectedIds.remove(track.id)
        else selectedIds.add(track.id)
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        selectedIds.clear()
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionUI() {
        val active = selectionMode
        binding.selectionToolbar.isVisible = active
        binding.appBar.isVisible = !active
        binding.tvSelectionCount.text = "${selectedIds.size} selected"
    }

    private fun confirmBulkDelete() {
        val n = selectedIds.size
        if (n == 0) return
        AlertDialog.Builder(requireContext(), R.style.LmusicDialog)
            .setTitle(if (n == 1) "Delete 1 track?" else "Delete $n tracks?")
            .setMessage("They'll be permanently removed from your device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> performBulkDelete() }
            .show()
    }

    private fun performBulkDelete() {
        val toDelete = allTracks.filter { selectedIds.contains(it.id) }
        exitSelectionMode()
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = DownloadDatabase.get(requireContext()).downloadDao()
            val appCtx = requireContext().applicationContext
            // Remove Lmusic Room rows. Use the already-captured toDelete snapshot so we
            // are not racing against a concurrent allTracks update from the Flow collector.
            val records = toDelete.mapNotNull { t ->
                t.downloadRecordId?.let { id ->
                    DownloadRecord(id = id, youtubeUrl = t.youtubeUrl.orEmpty(), title = t.title)
                }
            }
            if (records.isNotEmpty()) dao.deleteAll(records)
            // Remove files + thumbnails in background
            launch(Dispatchers.IO) {
                for (t in toDelete) {
                    try {
                        if (t.uri.startsWith("content://"))
                            appCtx.contentResolver.delete(t.uri.toUri(), null, null)
                        else File(t.uri).takeIf { it.exists() }?.delete()
                    } catch (_: Exception) {}
                    t.thumbnailUri?.let { File(it).takeIf { f -> f.exists() }?.delete() }
                }
            }
            Snackbar.make(
                binding.root,
                if (toDelete.size == 1) "1 track deleted" else "${toDelete.size} tracks deleted",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    // ----- List building -----

    private fun currentVisibleRecords(): List<MusicTrack> {
        val settings = Settings(requireContext())
        val q = searchQuery.trim()
        var filtered = if (q.isEmpty()) allTracks else allTracks.filter {
            it.title.contains(q, ignoreCase = true)
                    || it.artist?.contains(q, ignoreCase = true) == true
        }
        categoryFilter?.let { cat ->
            filtered = filtered.filter { trackCategory(it) == cat }
        }
        return when (settings.sortOrder) {
            Settings.SortOrder.NEWEST -> filtered.sortedByDescending { it.downloadedAt }
            Settings.SortOrder.OLDEST -> filtered.sortedBy { it.downloadedAt }
            Settings.SortOrder.TITLE_AZ -> filtered.sortedBy { it.title.lowercase() }
            Settings.SortOrder.ARTIST_AZ -> filtered.sortedBy { (it.artist ?: "").lowercase() }
        }
    }

    private fun applyFilterAndSort() {
        val settings = Settings(requireContext())
        val visible = currentVisibleRecords()
        val rows: List<Row> = if (settings.groupByArtist) buildGroupedRows(visible)
        else visible.map { Row.Item(it) }

        adapter.submitList(rows)

        val empty = visible.isEmpty()
        binding.emptyState.isVisible = empty
        binding.recyclerView.isVisible = !empty
        if (empty && searchQuery.isNotEmpty()) {
            binding.tvEmptyTitle.text = "No matches"
            binding.tvEmptySubtitle.text = "No tracks match \"$searchQuery\"."
        } else if (empty) {
            binding.tvEmptyTitle.text = "No downloads yet"
            binding.tvEmptySubtitle.text =
                "Play a YouTube video, then hold both volume buttons together for a moment to download it."
        }
    }

    private fun buildGroupedRows(tracks: List<MusicTrack>): List<Row> {
        val grouped = tracks.groupBy { it.artist?.takeIf { c -> c.isNotBlank() } ?: "Unknown artist" }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val out = mutableListOf<Row>()
        for ((artist, list) in grouped) {
            out += Row.Header(artist, list.size)
            for (t in list) out += Row.Item(t)
        }
        return out
    }

    private fun updateSubtitle(list: List<MusicTrack>) {
        binding.tvSubtitle.text = when {
            list.isEmpty() -> "Your music library"
            else -> "${list.size} ${if (list.size == 1) "track" else "tracks"}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Library chip row — sort + group toggles surfaced inline (echo pattern)
    // ─────────────────────────────────────────────────────────────────────

    private fun rebuildLibraryChips() {
        val row = binding.libraryChipRow
        row.removeAllViews()
        val s = Settings(requireContext())
        val inflater = LayoutInflater.from(requireContext())

        data class ChipDef(val label: String, val isActive: Boolean, val onTap: () -> Unit)
        val chips = listOf(
            ChipDef("Newest",     s.sortOrder == Settings.SortOrder.NEWEST)
                { s.sortOrder = Settings.SortOrder.NEWEST; applyFilterAndSort(); rebuildLibraryChips() },
            ChipDef("Oldest",     s.sortOrder == Settings.SortOrder.OLDEST)
                { s.sortOrder = Settings.SortOrder.OLDEST; applyFilterAndSort(); rebuildLibraryChips() },
            ChipDef("Title A-Z",  s.sortOrder == Settings.SortOrder.TITLE_AZ)
                { s.sortOrder = Settings.SortOrder.TITLE_AZ; applyFilterAndSort(); rebuildLibraryChips() },
            ChipDef("Artist A-Z", s.sortOrder == Settings.SortOrder.ARTIST_AZ)
                { s.sortOrder = Settings.SortOrder.ARTIST_AZ; applyFilterAndSort(); rebuildLibraryChips() },
            ChipDef(if (s.groupByArtist) "✓ Group by artist" else "Group by artist", s.groupByArtist)
                { s.groupByArtist = !s.groupByArtist; applyFilterAndSort(); rebuildLibraryChips() }
        )

        // ── Category filter chips (All + every category present) ──────────
        val presentCategories = allTracks
            .filter { it.status == DownloadRecord.STATUS_OK }
            .groupingBy { trackCategory(it) }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
        val categoryChips = buildList {
            if (presentCategories.size > 1) {
                add(ChipDef("All", categoryFilter == null)
                    { categoryFilter = null; applyFilterAndSort(); rebuildLibraryChips() })
                for (cat in presentCategories) {
                    add(ChipDef(cat, categoryFilter == cat) {
                        categoryFilter = if (categoryFilter == cat) null else cat
                        applyFilterAndSort(); rebuildLibraryChips()
                    })
                }
            }
        }

        for (c in categoryChips + chips) {
            val v = inflater.inflate(R.layout.view_genre_chip, row, false) as android.widget.TextView
            v.text = c.label
            v.setBackgroundResource(
                if (c.isActive) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive
            )
            v.setTextColor(
                requireContext().getColor(
                    if (c.isActive) R.color.on_doodle_peach else R.color.text_secondary
                )
            )
            v.setOnClickListener { c.onTap() }
            row.addView(v)
        }
    }

    /** Stored category, falling back to a live keyword categorization. */
    private fun trackCategory(t: MusicTrack): String =
        t.category ?: com.lmusic.data.MusicCategorizer.categorize(t.title, t.artist)

    private fun showSortMenu(anchor: View) {
        val s = Settings(requireContext())
        val pop = PopupMenu(requireContext(), anchor)
        pop.menu.add(0, 0, 0, "Newest first")
        pop.menu.add(0, 1, 1, "Oldest first")
        pop.menu.add(0, 2, 2, "Title (A-Z)")
        pop.menu.add(0, 3, 3, "Artist (A-Z)")
        pop.menu.add(0, 10, 4, if (s.groupByArtist) "✓ Group by artist" else "Group by artist")
        pop.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                10 -> { s.groupByArtist = !s.groupByArtist; applyFilterAndSort() }
                else -> {
                    s.sortOrder = when (item.itemId) {
                        1 -> Settings.SortOrder.OLDEST
                        2 -> Settings.SortOrder.TITLE_AZ
                        3 -> Settings.SortOrder.ARTIST_AZ
                        else -> Settings.SortOrder.NEWEST
                    }
                    applyFilterAndSort()
                }
            }
            true
        }
        pop.show()
    }

    // ----- Play + single delete -----

    private fun playTrack(track: MusicTrack) {
        if (track.uri.isBlank()) {
            Snackbar.make(binding.root, "File not available", Snackbar.LENGTH_SHORT).show()
            return
        }
        miniPlayer?.load(track, currentVisibleRecords())
            ?: Snackbar.make(binding.root, "Player not ready", Snackbar.LENGTH_SHORT).show()
    }

    private fun retryDownload(track: MusicTrack) {
        val youtubeUrl = track.youtubeUrl ?: return
        val recordId = track.downloadRecordId
        viewLifecycleOwner.lifecycleScope.launch {
            if (recordId != null) {
                // Construct a thin record purely for deletion lookup
                DownloadDatabase.get(requireContext()).downloadDao().delete(
                    DownloadRecord(id = recordId, youtubeUrl = youtubeUrl, title = track.title)
                )
            }
            DownloadRunner.start(requireContext(), youtubeUrl)
            Snackbar.make(binding.root, "Retrying download…", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(track: MusicTrack) {
        AlertDialog.Builder(requireContext(), R.style.LmusicDialog)
            .setTitle("Delete this track?")
            .setMessage("\"${track.title}\" will be removed from your device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> performDeleteWithUndo(track) }
            .show()
    }

    private fun performDeleteWithUndo(track: MusicTrack) {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // We support undo only for Lmusic-row deletes (the DB row is the source of truth).
            // Device-only tracks are deleted immediately via MediaStore without undo.
            val isLmusic = track.downloadRecordId != null
            val dao = DownloadDatabase.get(appCtx).downloadDao()
            val snapshot: DownloadRecord? = if (isLmusic) {
                DownloadRecord(
                    id = track.downloadRecordId!!,
                    youtubeUrl = track.youtubeUrl.orEmpty(),
                    title = track.title,
                    channel = track.artist,
                    durationSeconds = track.durationSeconds,
                    thumbnailPath = track.thumbnailUri,
                    fileUri = track.uri,
                    status = track.status,
                    errorMessage = track.errorMessage,
                    downloadedAt = track.downloadedAt,
                    category = track.category,
                    isAutoDownload = track.isAutoDownload,
                    expiresAt = track.expiresAt
                ).also { dao.delete(it) }
            } else null

            var undone = false
            Snackbar.make(binding.root, "Track deleted", Snackbar.LENGTH_LONG)
                .setAction("UNDO") {
                    undone = true
                    if (snapshot != null) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            // Re-insert with the original primary key so downloadRecordId
                            // references in other in-flight operations stay valid.
                            dao.insert(snapshot)
                        }
                    }
                }
                .addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (undone) return
                        // Use a scope that is NOT tied to the view lifecycle: the Snackbar
                        // callback fires ~2.75 s after show(), by which time onDestroyView()
                        // may have already cancelled viewLifecycleOwner.lifecycleScope,
                        // causing the file deletion to silently no-op and leak storage.
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            try {
                                if (track.uri.startsWith("content://"))
                                    appCtx.contentResolver.delete(track.uri.toUri(), null, null)
                                else File(track.uri).takeIf { it.exists() }?.delete()
                            } catch (_: Exception) {}
                            track.thumbnailUri?.let { File(it).takeIf { f -> f.exists() }?.delete() }
                        }
                    }
                })
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        applyFilterAndSort()
    }

    override fun onDestroyView() {
        // miniPlayer is owned by MainNavigationFragment — DO NOT release it here.
        super.onDestroyView()
        _binding = null
    }
}

// -----------------------------------------------------------------------------
// Adapter
// -----------------------------------------------------------------------------

sealed class Row {
    data class Header(val title: String, val count: Int) : Row()
    data class Item(val track: MusicTrack) : Row()
}

private class DownloadAdapter(
    private val onPlay: (MusicTrack) -> Unit,
    private val onLongPress: (MusicTrack) -> Unit,
    private val onDelete: (MusicTrack) -> Unit,
    private val onRetry: (MusicTrack) -> Unit,
    private val onToggleFav: (MusicTrack) -> Unit,
    private val isFavorite: (String) -> Boolean,
    private val isSelected: (String) -> Boolean,
    private val inSelectionMode: () -> Boolean
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    private val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

    override fun getItemViewType(position: Int) =
        if (getItem(position) is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemGroupHeaderBinding.inflate(inf, parent, false))
        } else {
            ItemVH(ItemDownloadBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderVH).bind(row)
            is Row.Item -> (holder as ItemVH).bind(row.track)
        }
    }

    private inner class ItemVH(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(track: MusicTrack) = with(binding) {
            tvTitle.text = track.title

            val parts = mutableListOf<String>()
            if (track.durationSeconds > 0) parts += formatDuration(track.durationSeconds)
            parts += dateFmt.format(Date(track.downloadedAt))
            if (track.isAutoDownload) parts += "Weekly Mix"
            tvMeta.text = parts.joinToString("  ·  ")

            // ── Reset the thumbnail slot before every bind ──────────────────
            // Cancels any pending Coil request from the row this view holder
            // was previously bound to, and clears the image source.  Without
            // this, scrolling could leave stale thumbnails on recycled rows.
            ivThumbnail.dispose()
            ivThumbnail.setImageDrawable(null)
            ivThumbnail.setBackgroundResource(R.drawable.bg_thumbnail_placeholder)

            // ── Pick the best thumbnail source ──────────────────────────────
            // 1. Local cached thumbnail file (Lmusic downloads).
            // 2. MediaStore album-art URI (device tracks with an albumId).
            // 3. Derived YouTube thumbnail URL (Lmusic tracks whose local
            //    thumbnail file is missing — e.g. cache cleared after the
            //    download).  Falls back to a stable hqdefault.jpg path so the
            //    user still sees the right cover.
            val thumbFile = track.thumbnailUri?.takeIf { it.isNotBlank() }?.let { File(it) }
            val thumbModel: Any? = when {
                thumbFile != null && thumbFile.exists() -> thumbFile
                track.albumId != null -> com.lmusic.data.MusicLibrary.albumArtUri(track.albumId)
                else -> youtubeThumbUrl(track.youtubeUrl)
            }
            if (thumbModel != null) {
                ivPlaceholderIcon.isVisible = false
                ivThumbnail.load(thumbModel) {
                    crossfade(true)
                    size(192)                                // ~64-72dp slot
                    scale(coil.size.Scale.FILL)
                    transformations(RoundedCornersTransformation(28f))
                    placeholder(R.drawable.bg_thumbnail_placeholder)
                    error(R.drawable.bg_thumbnail_placeholder)
                }
            } else {
                // Reset block at the top of bind() already cleared the
                // ImageView, so we only need to surface the music-note overlay.
                ivPlaceholderIcon.isVisible = true
            }

            val failed = track.status == DownloadRecord.STATUS_FAILED
            val selecting = inSelectionMode()
            val selected = isSelected(track.id)

            root.setBackgroundResource(when {
                selecting && selected -> R.drawable.bg_card_selected
                failed -> R.drawable.bg_card_failed
                else -> R.drawable.bg_card
            })

            btnPlay.isVisible = !selecting && !failed
            btnRetry.isVisible = !selecting && failed
            btnDelete.isVisible = !selecting
            // Heart only makes sense on a playable, non-failed track.
            btnFav.isVisible   = !selecting && !failed
            selectionOverlay.isVisible = selecting && selected

            // Heart icon + tint reflect the current favorite state.
            val fav = isFavorite(track.id)
            btnFav.setImageResource(
                if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            )
            btnFav.setColorFilter(
                root.context.getColor(if (fav) R.color.accent else R.color.text_tertiary)
            )

            tvTitle.alpha = if (failed) 0.5f else 1f
            tvChannel.text = when {
                failed -> track.errorMessage?.take(60) ?: "Download failed"
                else -> track.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
            }
            tvChannel.setTextColor(
                root.context.getColor(if (failed) R.color.error else R.color.text_secondary)
            )

            btnPlay.setOnClickListener   { onPlay(track) }
            btnRetry.setOnClickListener  { onRetry(track) }
            btnDelete.setOnClickListener { onDelete(track) }
            btnFav.setOnClickListener    { onToggleFav(track) }
            root.setOnClickListener      { onPlay(track) }
            root.setOnLongClickListener  { onLongPress(track); true }
        }

        private fun formatDuration(seconds: Long): String {
            val m = seconds / 60
            val s = seconds % 60
            return "%d:%02d".format(m, s)
        }
    }

    private class HeaderVH(val binding: ItemGroupHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: Row.Header) {
            binding.tvGroupTitle.text = header.title
            binding.tvGroupCount.text = if (header.count == 1) "1 track" else "${header.count} tracks"
        }
    }

    /**
     * Cancel any pending Coil request on a recycled row so a stale image from
     * the previous binding can never linger on a new track.
     */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ItemVH) {
            holder.binding.ivThumbnail.dispose()
            holder.binding.ivThumbnail.setImageDrawable(null)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        /**
         * Derives a public YouTube thumbnail URL (hqdefault.jpg) from any
         * full YouTube URL.  Used as a fallback for Lmusic-downloaded tracks
         * whose locally-cached thumbnail file has been deleted.
         */
        fun youtubeThumbUrl(youtubeUrl: String?): String? {
            if (youtubeUrl.isNullOrBlank()) return null
            val id = Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(youtubeUrl)?.groupValues?.get(1)
                ?: Regex("""youtu\.be/([A-Za-z0-9_-]{11})""").find(youtubeUrl)?.groupValues?.get(1)
                ?: Regex("""/shorts/([A-Za-z0-9_-]{11})""").find(youtubeUrl)?.groupValues?.get(1)
                ?: return null
            return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        }

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row): Boolean = when {
                a is Row.Header && b is Row.Header -> a.title == b.title
                a is Row.Item && b is Row.Item -> a.track.id == b.track.id
                else -> false
            }
            override fun areContentsTheSame(a: Row, b: Row): Boolean = a == b
        }
    }
}
