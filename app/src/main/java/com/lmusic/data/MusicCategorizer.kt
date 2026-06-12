package com.lmusic.data

/**
 * Heuristic music categorizer.
 *
 * Maps a track (title + artist/channel) onto a single human-friendly genre/mood
 * bucket using keyword matching.  No network, no ML — fast and deterministic so
 * it can run inline on the download path and during library grouping.
 *
 * The categories double as the seed space for the recommendation engine, so the
 * list is intentionally compact and listening-oriented (mood + genre), not an
 * exhaustive musicology taxonomy.
 */
object MusicCategorizer {

    /** Canonical category labels, in display order. */
    val CATEGORIES = listOf(
        "Lofi & Chill", "Pop", "Hip Hop", "Rock", "EDM & Dance",
        "Bollywood", "Punjabi", "Classical", "Workout", "Acoustic", "Other"
    )

    const val UNCATEGORIZED = "Other"

    // Order matters: the first bucket whose keywords match wins, so put the more
    // specific buckets before the broad ones.
    private val RULES: List<Pair<String, List<String>>> = listOf(
        "Lofi & Chill" to listOf(
            "lofi", "lo-fi", "lo fi", "chillhop", "chill beats", "study beats",
            "relax", "ambient", "sleep", "calm", "rain sounds", "meditation"
        ),
        "Workout" to listOf(
            "workout", "gym", "running", "cardio", "motivation", "beast mode",
            "pump up", "training", "hiit"
        ),
        "EDM & Dance" to listOf(
            "edm", "electronic", "house music", "techno", "trance", "dubstep",
            "dance", "remix", "festival", "drop", "deep house", "future bass",
            "dj ", "club mix"
        ),
        "Hip Hop" to listOf(
            "hip hop", "hip-hop", "rap", "trap", "drill", "freestyle", "cypher",
            "boom bap", "g-funk"
        ),
        "Rock" to listOf(
            "rock", "metal", "punk", "grunge", "alt rock", "hard rock",
            "guitar solo", "headbang", "indie rock"
        ),
        "Bollywood" to listOf(
            "bollywood", "hindi song", "hindi songs", "filmi", "arijit",
            "shreya ghoshal", "t-series", "sony music india", "zee music",
            "bhojpuri", "qawwali", "hindi"
        ),
        "Punjabi" to listOf(
            "punjabi", "bhangra", "diljit", "sidhu", "ap dhillon", "karan aujla",
            "shubh", "desi"
        ),
        "Classical" to listOf(
            "classical", "orchestra", "symphony", "piano sonata", "violin",
            "concerto", "philharmonic", "opera", "baroque", "instrumental piano"
        ),
        "Acoustic" to listOf(
            "acoustic", "unplugged", "cover song", "live session", "stripped",
            "singer songwriter", "folk"
        ),
        "Pop" to listOf(
            "pop", "official video", "official music video", "lyrics", "vevo",
            "top hits", "billboard", "single"
        )
    )

    /**
     * Returns a category label for the given [title] / [artist], never null.
     * Falls back to [UNCATEGORIZED] when nothing matches.
     */
    fun categorize(title: String?, artist: String?): String {
        val haystack = buildString {
            append(title?.lowercase().orEmpty())
            append(' ')
            append(artist?.lowercase().orEmpty())
        }
        if (haystack.isBlank()) return UNCATEGORIZED
        for ((label, keywords) in RULES) {
            if (keywords.any { haystack.contains(it) }) return label
        }
        return UNCATEGORIZED
    }

    /** A search query string that surfaces more tracks for the given category. */
    fun seedQueryFor(category: String): String = when (category) {
        "Lofi & Chill" -> "lofi chill beats to relax"
        "Pop"          -> "top pop hits"
        "Hip Hop"      -> "best hip hop songs"
        "Rock"         -> "popular rock songs"
        "EDM & Dance"  -> "edm dance hits"
        "Bollywood"    -> "latest bollywood songs"
        "Punjabi"      -> "latest punjabi songs"
        "Classical"    -> "relaxing classical music"
        "Workout"      -> "workout motivation music"
        "Acoustic"     -> "acoustic covers"
        else           -> "popular music"
    }
}
