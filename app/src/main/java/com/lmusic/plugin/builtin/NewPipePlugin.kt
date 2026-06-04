package com.lmusic.plugin.builtin

import android.util.Log
import com.lmusic.download.NewPipeDownloaderImpl
import com.lmusic.plugin.AudioStream
import com.lmusic.plugin.ExtractionResult
import com.lmusic.plugin.SearchResult
import com.lmusic.plugin.SearchablePlugin
import com.lmusic.plugin.StreamPlugin
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Built-in stream-extraction plugin backed by NewPipeExtractor (master-SNAPSHOT via JitPack).
 *
 * Supports YouTube URLs and "ytsearch:…" queries.
 * Returns audio-only streams (M4A / WebM) as the primary candidates; mixed video+audio MP4
 * streams are appended as fallback in case no audio-only stream is available.
 */
class NewPipePlugin : StreamPlugin, SearchablePlugin {

    override val id             = "newpipe"
    override val name           = "NewPipeExtractor"
    override val version        = "master-SNAPSHOT"
    override val isBuiltIn      = true
    override val supportedDomains = listOf("youtube.com", "youtu.be", "ytsearch")

    companion object {
        private const val TAG = "NewPipePlugin"
        private val initialized = AtomicBoolean(false)

        /** Short-timeout OkHttp client used for autocomplete suggestions. */
        private val suggestionsClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()

        /**
         * LRU cache of the last 32 autocomplete queries → suggestions.
         * When the user types "lof" → "lofi" → backspace → "lof", we serve the
         * second "lof" from this cache instead of re-hitting the network.
         */
        private const val SUGGEST_CACHE_SIZE = 32
        private val suggestionsCache: MutableMap<String, List<String>> =
            object : java.util.LinkedHashMap<String, List<String>>(
                SUGGEST_CACHE_SIZE, 0.75f, /* accessOrder = */ true
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<String>>
                ): Boolean = size > SUGGEST_CACHE_SIZE
            }
    }

    init {
        // NewPipe.init() is safe to call once per process; the AtomicBoolean guards it.
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(NewPipeDownloaderImpl)
        }
    }

    override fun supportsInput(input: String): Boolean =
        input.startsWith("ytsearch") ||
        "youtube.com" in input      ||
        "youtu.be"    in input

    override fun extract(input: String): ExtractionResult {
        // 1. Resolve search queries → video URL
        val videoUrl = if (input.startsWith("ytsearch")) {
            val query = input.substringAfter(":").trim()
            searchFirstResult(query)
                ?: throw IllegalArgumentException("No YouTube results found for: $query")
        } else input

        Log.d(TAG, "Extracting stream info for: $videoUrl")

        // 2. Fetch StreamInfo with exponential-backoff retry
        val info = fetchWithRetry(videoUrl)

        Log.d(TAG, "Streams — audio:${info.audioStreams?.size ?: 0} " +
                "video:${info.videoStreams?.size ?: 0}")

        // 3. Build the AudioStream list
        //    Audio-only streams come first; mixed video+audio streams appended as fallback.
        val streams = mutableListOf<AudioStream>()

        for (a in info.audioStreams.orEmpty()) {
            val url = a.content ?: continue
            val ext = a.format?.suffix ?: "m4a"
            val kbps = run {
                val raw = if (a.averageBitrate > 0) a.averageBitrate else a.bitrate
                if (raw > 0) raw / 1000 else 0
            }
            streams += AudioStream(
                url         = url,
                extension   = ext,
                mimeType    = mimeFor(ext),
                bitrateKbps = kbps,
                isAudioOnly = true
            )
        }

        for (v in info.videoStreams.orEmpty()) {
            val url = v.content ?: continue
            val ext = v.format?.suffix ?: "mp4"
            streams += AudioStream(
                url         = url,
                extension   = ext,
                mimeType    = mimeFor(ext),
                bitrateKbps = 0,
                isAudioOnly = false
            )
        }

        if (streams.isEmpty()) {
            throw RuntimeException(
                "No playable streams found — YouTube may have restricted this video."
            )
        }

        // 4. Best thumbnail
        val thumbUrl = runCatching {
            info.thumbnails
                ?.maxByOrNull { it.height.takeIf { h -> h > 0 } ?: 0 }
                ?.url
        }.getOrNull()

        return ExtractionResult(
            streams         = streams,
            title           = info.name.orEmpty().ifBlank { "Unknown" },
            channel         = info.uploaderName?.takeIf { it.isNotBlank() },
            durationSeconds = info.duration,
            thumbnailUrl    = thumbUrl
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SearchablePlugin
    // ─────────────────────────────────────────────────────────────────────────

    override fun search(query: String, limit: Int): List<SearchResult> {
        return try {
            val service   = ServiceList.YouTube
            val handler   = service.searchQHFactory.fromQuery(query, listOf("videos"), "")
            val extractor = service.getSearchExtractor(handler)
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .take(limit)
                .map { item ->
                    val rawId = runCatching {
                        item.url.substringAfter("watch?v=").substringBefore("&")
                    }.getOrDefault("")
                    SearchResult(
                        videoUrl        = item.url,
                        videoId         = rawId,
                        title           = item.name.orEmpty().ifBlank { "Unknown" },
                        channel         = item.uploaderName?.takeIf { it.isNotBlank() },
                        durationSeconds = item.duration,
                        thumbnailUrl    = item.thumbnails
                            ?.maxByOrNull { it.height.takeIf { h -> h > 0 } ?: 0 }?.url
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "search('$query') failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Live autocomplete suggestions.
     *
     * Strategy: hit Google's public autocomplete endpoint directly via OkHttp.
     * The endpoint returns plain JSON of the form `["query",["sug1","sug2",…]]`
     * and requires no auth.  We try NewPipe's SuggestionExtractor as a fallback
     * only if the direct call returns nothing — NewPipe's extractor is sometimes
     * null or returns empty on master-SNAPSHOT builds.
     */
    override fun getSuggestions(query: String): List<String> {
        if (query.isBlank() || query.length < 2) return emptyList()

        val key = query.trim().lowercase()

        // ── 0. LRU cache hit ────────────────────────────────────────────────
        synchronized(suggestionsCache) {
            suggestionsCache[key]?.let { return it }
        }

        // ── 1. Direct HTTP call to Google's public autocomplete endpoint ────
        val fromHttp = runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search" +
                    "?client=firefox&ds=yt&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            suggestionsClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val outer = JSONArray(body)
                if (outer.length() < 2) return@runCatching null
                val list = outer.getJSONArray(1)
                (0 until list.length()).mapNotNull { i ->
                    list.optString(i).takeIf { it.isNotBlank() }
                }
            }
        }.getOrNull()

        if (!fromHttp.isNullOrEmpty()) {
            synchronized(suggestionsCache) { suggestionsCache[key] = fromHttp }
            return fromHttp
        }

        // ── 2. Fallback to NewPipe's extractor ───────────────────────────────
        val fromNewPipe = try {
            ServiceList.YouTube.suggestionExtractor?.suggestionList(query).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "getSuggestions('$query') NewPipe fallback failed: ${e.message}")
            emptyList()
        }
        if (fromNewPipe.isNotEmpty()) {
            synchronized(suggestionsCache) { suggestionsCache[key] = fromNewPipe }
        }
        return fromNewPipe
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchWithRetry(url: String, attempts: Int = 3): StreamInfo {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return StreamInfo.getInfo(ServiceList.YouTube, url)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Attempt ${attempt + 1}/$attempts failed: ${e.message}")
                if (attempt < attempts - 1) Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw lastError ?: RuntimeException("Stream extraction failed after $attempts attempts")
    }

    private fun searchFirstResult(query: String): String? {
        return try {
            val service  = ServiceList.YouTube
            val handler  = service.searchQHFactory.fromQuery(query, listOf("videos"), "")
            val extractor = service.getSearchExtractor(handler)
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .firstOrNull()
                ?.url
        } catch (e: Exception) {
            Log.w(TAG, "Search failed for '$query': ${e.message}")
            null
        }
    }

    private fun mimeFor(ext: String) = when (ext.lowercase()) {
        "mp3"         -> "audio/mpeg"
        "m4a", "mp4"  -> "audio/mp4"
        "webm"        -> "audio/webm"
        "opus"        -> "audio/ogg"
        else          -> "audio/*"
    }
}
