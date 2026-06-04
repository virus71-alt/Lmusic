package com.lmusic.data

import android.content.Context

/**
 * Lightweight favorites store backed by SharedPreferences.
 *
 * Favorites are keyed by [MusicTrack.id] (e.g. "lmusic-42" / "device-1234"),
 * which is stable across library rebuilds.  No Room migration needed.
 */
class FavoritesStore(ctx: Context) {

    private val prefs = ctx.applicationContext
        .getSharedPreferences("lmusic_favorites", Context.MODE_PRIVATE)

    /** All favorited track IDs. */
    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    fun isFavorite(id: String): Boolean = all().contains(id)

    /** Toggles favorite state; returns the new state (true = now favorited). */
    fun toggle(id: String): Boolean {
        val current = all().toMutableSet()
        val nowFav = if (current.contains(id)) { current.remove(id); false }
                     else { current.add(id); true }
        prefs.edit().putStringSet(KEY, current).apply()
        return nowFav
    }

    fun setFavorite(id: String, favorite: Boolean) {
        val current = all().toMutableSet()
        if (favorite) current.add(id) else current.remove(id)
        prefs.edit().putStringSet(KEY, current).apply()
    }

    companion object {
        private const val KEY = "favorite_ids"
    }
}
