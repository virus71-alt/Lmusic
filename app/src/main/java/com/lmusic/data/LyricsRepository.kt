package com.lmusic.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fetches song lyrics from LRCLIB (https://lrclib.net) — a free, key-less,
 * community lyrics database that returns both plain and time-synced lyrics.
 *
 * No auth, no rate-limit headaches.  We try an exact `/api/get` match first
 * (artist + title + duration), then fall back to `/api/search`.
 */
object LyricsRepository {

    private const val TAG = "LyricsRepository"
    private const val BASE = "https://lrclib.net/api"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** A single time-synced lyric line. */
    data class SyncedLine(val timeMs: Long, val text: String)

    /**
     * Result of a lyrics lookup.
     * [synced] is non-empty when LRCLIB provided an LRC track; otherwise only
     * [plain] is populated.  [found] is false when nothing matched at all.
     */
    data class Lyrics(
        val plain: String?,
        val synced: List<SyncedLine>,
        val found: Boolean
    ) {
        val hasSynced get() = synced.isNotEmpty()
        companion object {
            val NONE = Lyrics(null, emptyList(), false)
        }
    }

    /**
     * Blocking lookup — call from an IO dispatcher.
     * [durationSeconds] improves exact-match accuracy; pass 0 if unknown.
     */
    fun fetch(title: String, artist: String?, durationSeconds: Long): Lyrics {
        val cleanTitle = stripNoise(title)
        val cleanArtist = artist?.let { stripArtistNoise(it) }.orEmpty()

        // 1. Exact get (best — returns the canonical synced LRC)
        if (cleanArtist.isNotBlank()) {
            getExact(cleanTitle, cleanArtist, durationSeconds)?.let { return it }
        }
        // 2. Fuzzy search fallback
        return search(cleanTitle, cleanArtist)
    }

    // ── LRCLIB calls ──────────────────────────────────────────────────────────

    private fun getExact(title: String, artist: String, durationSeconds: Long): Lyrics? {
        val url = buildString {
            append("$BASE/get?")
            append("track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
            if (durationSeconds > 0) append("&duration=").append(durationSeconds)
        }
        return runCatching {
            request(url)?.let { parseObject(JSONObject(it)) }
        }.getOrNull()
    }

    private fun search(title: String, artist: String): Lyrics {
        val url = buildString {
            append("$BASE/search?track_name=").append(enc(title))
            if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
        }
        return runCatching {
            val body = request(url) ?: return Lyrics.NONE
            val arr = JSONArray(body)
            if (arr.length() == 0) return Lyrics.NONE
            // Prefer the first entry that actually carries synced lyrics.
            var best: JSONObject? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optString("syncedLyrics").isNullOrBlank()) { best = o; break }
                if (best == null) best = o
            }
            best?.let { parseObject(it) } ?: Lyrics.NONE
        }.getOrElse {
            Log.w(TAG, "search failed: ${it.message}")
            Lyrics.NONE
        }
    }

    private fun parseObject(o: JSONObject): Lyrics {
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() }
        val syncedRaw = o.optString("syncedLyrics").takeIf { it.isNotBlank() }
        val synced = syncedRaw?.let { parseLrc(it) } ?: emptyList()
        val found = plain != null || synced.isNotEmpty()
        return Lyrics(plain, synced, found)
    }

    /** Parses an LRC body ("[mm:ss.xx] line") into ordered [SyncedLine]s. */
    private fun parseLrc(lrc: String): List<SyncedLine> {
        val tag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val out = ArrayList<SyncedLine>()
        for (line in lrc.lines()) {
            val matches = tag.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out += SyncedLine(min * 60_000 + sec * 1_000 + ms, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun request(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", "Lmusic/1.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Strips "(Official Video)", "[Lyrics]", "| HD", feat. tails etc. from a title. */
    private fun stripNoise(title: String): String =
        title
            .replace(Regex("""(?i)\(.*?(official|video|audio|lyric|hd|4k|visualizer|mv).*?\)"""), "")
            .replace(Regex("""(?i)\[.*?(official|video|audio|lyric|hd|4k).*?]"""), "")
            .replace(Regex("""(?i)\s*[|/-]\s*(official.*|full video.*|lyrics.*)$"""), "")
            .replace(Regex("""(?i)\s*ft\.?\s.*$"""), "")
            .replace(Regex("""(?i)\s*feat\.?\s.*$"""), "")
            .trim()

    private fun stripArtistNoise(artist: String): String =
        artist
            .replace(Regex("""(?i)\s*-?\s*topic$"""), "")
            .replace(Regex("""(?i)vevo$"""), "")
            .replace(Regex("""(?i)official$"""), "")
            .trim()
}
