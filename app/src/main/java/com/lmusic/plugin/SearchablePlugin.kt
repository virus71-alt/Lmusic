package com.lmusic.plugin

import androidx.annotation.WorkerThread

/**
 * Optional extension of [StreamPlugin] for plugins that support full-text search.
 *
 * Cast a [StreamPlugin] to [SearchablePlugin] before calling [search]:
 *
 *   val searchable = PluginManager.findFor(input) as? SearchablePlugin
 *   searchable?.search("lofi beats") ?: emptyList()
 */
interface SearchablePlugin : StreamPlugin {

    /**
     * Returns up to [limit] results for [query].
     * Called on a worker thread. Returns an empty list (never throws) on error.
     */
    @WorkerThread
    fun search(query: String, limit: Int = 25): List<SearchResult>

    /**
     * Returns autocomplete suggestion strings for [query] (e.g. "lofi", "lofi
     * hip hop", "lofi study music").  Called on a worker thread.
     * Returns an empty list on error or when not supported.
     *
     * Default implementation: no suggestions.  Plugins that can provide them
     * (like NewPipe's SuggestionsExtractor) should override.
     */
    @WorkerThread
    fun getSuggestions(query: String): List<String> = emptyList()
}

// ─────────────────────────────────────────────────────────────────────────────
// Model
// ─────────────────────────────────────────────────────────────────────────────

data class SearchResult(
    /** Full YouTube URL — pass directly to [StreamPlugin.extract]. */
    val videoUrl: String,
    val videoId: String,
    val title: String,
    val channel: String?,
    /** Duration in seconds; 0 = unknown (e.g. live streams). */
    val durationSeconds: Long,
    val thumbnailUrl: String?
)
