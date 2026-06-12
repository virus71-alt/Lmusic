package com.lmusic.data

import android.content.Context
import android.util.Log
import com.lmusic.plugin.PluginManager
import com.lmusic.plugin.SearchResult
import com.lmusic.plugin.SearchablePlugin

/**
 * Builds personalised listening recommendations from the user's own library —
 * never random songs.
 *
 * Signal used (in priority order):
 *   1. Favorited tracks' artists  (strongest taste signal)
 *   2. Most-downloaded artists
 *   3. Most-common categories     ([MusicCategorizer])
 *
 * From those seeds it issues YouTube searches via the built-in [SearchablePlugin]
 * and returns de-duplicated [SearchResult]s that are NOT already in the library.
 *
 * Both the Home "For You" shelves and the weekly auto-download worker consume
 * this, so the two stay consistent.
 */
class RecommendationEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "RecommendationEngine"
        /** Don't treat an artist as a taste signal unless it appears at least this often. */
        private const val MIN_ARTIST_PLAYS = 1
    }

    /** A titled group of recommendations, e.g. "More like Arijit Singh". */
    data class Shelf(val title: String, val seed: String, val items: List<SearchResult>)

    /** Snapshot of the user's taste profile derived from their library. */
    data class Taste(
        val topArtists: List<String>,
        val topCategories: List<String>,
        val hasEnoughData: Boolean
    )

    private val favorites by lazy { FavoritesStore(ctx) }

    /**
     * Computes the taste profile from the merged library snapshot.
     * [tracks] is the current [MusicLibrary] list; pass it in so callers that
     * already hold it don't trigger a second scan.
     */
    fun analyzeTaste(tracks: List<MusicTrack>): Taste {
        val favIds = favorites.all()

        // Weighted artist tally: a favorited track counts triple.
        val artistScore = HashMap<String, Int>()
        val categoryScore = HashMap<String, Int>()

        for (t in tracks) {
            val weight = if (t.id in favIds) 3 else 1
            t.artist?.takeIf { it.isNotBlank() && it != "Unknown artist" }?.let {
                artistScore[it] = (artistScore[it] ?: 0) + weight
            }
            val cat = t.category ?: MusicCategorizer.categorize(t.title, t.artist)
            categoryScore[cat] = (categoryScore[cat] ?: 0) + weight
        }

        val topArtists = artistScore.entries
            .filter { it.value >= MIN_ARTIST_PLAYS }
            .sortedByDescending { it.value }
            .map { it.key }

        val topCategories = categoryScore.entries
            .filter { it.key != MusicCategorizer.UNCATEGORIZED }
            .sortedByDescending { it.value }
            .map { it.key }
            .ifEmpty { categoryScore.entries.sortedByDescending { it.value }.map { it.key } }

        return Taste(
            topArtists = topArtists,
            topCategories = topCategories,
            hasEnoughData = tracks.isNotEmpty()
        )
    }

    /**
     * Builds up to [maxShelves] recommendation shelves for the Home screen.
     * Blocking — call from a worker thread / IO dispatcher.
     */
    fun buildShelves(
        tracks: List<MusicTrack>,
        maxShelves: Int = 4,
        perShelf: Int = 10
    ): List<Shelf> {
        val taste = analyzeTaste(tracks)
        if (!taste.hasEnoughData) return emptyList()

        val plugin = PluginManager.all.filterIsInstance<SearchablePlugin>().firstOrNull()
            ?: return emptyList()

        val existing = existingKeys(tracks)
        val shelves = mutableListOf<Shelf>()
        val usedVideoIds = HashSet<String>()

        // 1. Artist-based shelves (strongest signal)
        for (artist in taste.topArtists.take(maxShelves)) {
            if (shelves.size >= maxShelves) break
            val seed = "$artist songs"
            val items = safeSearch(plugin, seed, perShelf * 2)
                .filter { it.dedupKey() !in existing && usedVideoIds.add(it.videoId) }
                .take(perShelf)
            if (items.size >= 3) shelves += Shelf("More like $artist", seed, items)
        }

        // 2. Category-based shelves to round out the page
        for (category in taste.topCategories) {
            if (shelves.size >= maxShelves) break
            val seed = MusicCategorizer.seedQueryFor(category)
            val items = safeSearch(plugin, seed, perShelf * 2)
                .filter { it.dedupKey() !in existing && usedVideoIds.add(it.videoId) }
                .take(perShelf)
            if (items.size >= 3) shelves += Shelf("$category picks", seed, items)
        }

        return shelves
    }

    /**
     * Flat, ranked list of recommended tracks for the weekly auto-download.
     * Pulls from the same shelves but interleaves them so the mix spans the
     * user's tastes rather than dumping 10 songs from one artist.
     */
    fun buildWeeklyPicks(tracks: List<MusicTrack>, count: Int = 10): List<SearchResult> {
        val shelves = buildShelves(tracks, maxShelves = 6, perShelf = count)
        if (shelves.isEmpty()) return emptyList()

        val picks = mutableListOf<SearchResult>()
        val seen = HashSet<String>()
        var round = 0
        // Round-robin across shelves for variety.
        while (picks.size < count) {
            var addedThisRound = false
            for (shelf in shelves) {
                val item = shelf.items.getOrNull(round) ?: continue
                if (seen.add(item.videoId)) {
                    picks += item
                    addedThisRound = true
                    if (picks.size >= count) break
                }
            }
            if (!addedThisRound) break
            round++
        }
        return picks
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun safeSearch(plugin: SearchablePlugin, query: String, limit: Int): List<SearchResult> =
        try {
            plugin.search(query, limit)
                // skip very long items (likely mixes/podcasts) and live (0s)
                .filter { it.durationSeconds in 60..900 }
        } catch (e: Exception) {
            Log.w(TAG, "search('$query') failed: ${e.message}")
            emptyList()
        }

    /** Keys identifying tracks already present, to avoid recommending duplicates. */
    private fun existingKeys(tracks: List<MusicTrack>): Set<String> {
        val keys = HashSet<String>()
        for (t in tracks) {
            keys += normalize(t.title, t.artist)
            t.youtubeUrl?.let { videoIdOf(it)?.let { id -> keys += "vid:$id" } }
        }
        return keys
    }

    private fun SearchResult.dedupKey(): String = normalize(title, channel)

    private fun normalize(title: String, artist: String?): String =
        (title + "|" + (artist ?: ""))
            .lowercase()
            .replace(Regex("""\(.*?\)|\[.*?]"""), "")        // drop "(official video)" etc.
            .replace(Regex("""[^a-z0-9]"""), "")
            .take(60)

    private fun videoIdOf(url: String): String? =
        Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)
            ?: Regex("""youtu\.be/([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)
}
