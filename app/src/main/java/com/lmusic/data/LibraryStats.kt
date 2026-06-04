package com.lmusic.data

import java.util.Calendar

/**
 * Pure derived metrics over a list of [MusicTrack].
 *
 * Used by the Profile screen to show library insights — listening hours, top
 * artists, day-of-week activity — all computed from data the app already has
 * (download history + MediaStore scan).  No persistent stats table required.
 */
object LibraryStats {

    /** A bar in the weekly-rhythm chart. */
    data class WeekdayBucket(val label: String, val count: Int)

    /** One row in the top-artists breakdown. */
    data class ArtistBucket(val name: String, val trackCount: Int, val percent: Int)

    /** Everything Profile needs in a single computation. */
    data class Snapshot(
        val totalHours:      Int,                  // total library duration / 3600
        val distinctArtists: Int,                  // unique artist names
        val weekdays:        List<WeekdayBucket>,  // Mon → Sun (7 entries)
        val topArtists:      List<ArtistBucket>    // top 3 by track count
    ) {
        companion object {
            val EMPTY = Snapshot(0, 0, defaultWeek(), emptyList())
            private fun defaultWeek(): List<WeekdayBucket> =
                listOf("M","T","W","T","F","S","S").map { WeekdayBucket(it, 0) }
        }
    }

    fun compute(tracks: List<MusicTrack>): Snapshot {
        if (tracks.isEmpty()) return Snapshot.EMPTY

        // ── Hours of music ────────────────────────────────────────────────
        val totalSeconds = tracks.sumOf { it.durationSeconds }
        val totalHours   = (totalSeconds / 3600L).toInt()

        // ── Weekday counts (Mon=0 .. Sun=6) ───────────────────────────────
        val perDay = IntArray(7)
        val cal = Calendar.getInstance()
        for (t in tracks) {
            cal.timeInMillis = t.downloadedAt
            // Calendar.MONDAY=2 .. SUNDAY=1 — map so Mon=0, Sun=6
            val idx = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7)
            perDay[idx]++
        }
        val weekdayLabels = listOf("M","T","W","T","F","S","S")
        val weekdays = weekdayLabels.mapIndexed { i, lbl -> WeekdayBucket(lbl, perDay[i]) }

        // ── Top artists ────────────────────────────────────────────────────
        val byArtist = tracks
            .mapNotNull { it.artist?.trim()?.takeIf { a -> a.isNotBlank() } }
            .groupingBy { it }
            .eachCount()

        val top = byArtist.entries
            .sortedByDescending { it.value }
            .take(3)

        val topTotal = top.sumOf { it.value }.coerceAtLeast(1)
        val topArtists = top.map { (name, count) ->
            ArtistBucket(
                name       = name,
                trackCount = count,
                percent    = (count * 100 / topTotal)
            )
        }

        return Snapshot(
            totalHours      = totalHours,
            distinctArtists = byArtist.size,
            weekdays        = weekdays,
            topArtists      = topArtists
        )
    }
}
