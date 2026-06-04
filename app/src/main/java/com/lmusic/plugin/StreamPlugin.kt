package com.lmusic.plugin

import androidx.annotation.WorkerThread

/**
 * Contract for a stream-extraction plugin.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * BUILT-IN
 *   [com.lmusic.plugin.builtin.NewPipePlugin] — ships inside the app, uses
 *   NewPipeExtractor. Always present and cannot be removed.
 *
 * EXTERNAL (sideloaded)
 *   Any APK that contains the following in its <application> block:
 *
 *     <meta-data
 *         android:name="lmusic.plugin.class"
 *         android:value="com.example.myplugin.YourPluginClass" />
 *
 *   The declared class must implement [StreamPlugin] and expose either
 *     • a no-arg constructor, or
 *     • a single-arg constructor that accepts [android.content.Context].
 *
 *   The plugin APK must also carry copies of [StreamPlugin], [ExtractionResult],
 *   and [AudioStream] at the identical package path (com.lmusic.plugin.*).
 *   The host's classloader is used as the parent DexClassLoader so the cast
 *   is always resolved against the host's definition — making it type-safe.
 * ─────────────────────────────────────────────────────────────────────────────
 */
interface StreamPlugin {
    /** Stable, unique ID.  e.g. "newpipe" or a reverse-domain like "com.example.innertube". */
    val id: String

    /** Human-readable name shown in Settings → Plugins. */
    val name: String

    /** Display version string.  e.g. "master-SNAPSHOT" or "1.3.0". */
    val version: String

    /** True when this plugin ships inside the app and cannot be uninstalled. */
    val isBuiltIn: Boolean get() = false

    /** Domain/scheme labels shown in the Plugins screen (display only). */
    val supportedDomains: List<String> get() = listOf("youtube.com", "youtu.be")

    /**
     * Return true if this plugin can handle [input].
     * [input] is either a full URL (https://youtube.com/watch?v=…)
     * or a "ytsearch:query string".
     */
    fun supportsInput(input: String): Boolean

    /**
     * Resolve [input] to a list of playable streams plus track metadata.
     *
     * This method is called on a worker thread ([WorkerThread]).
     * Throw any [Exception] on unrecoverable failure — the caller logs it and
     * surfaces the message to the user.
     */
    @WorkerThread
    @Throws(Exception::class)
    fun extract(input: String): ExtractionResult
}

// ─────────────────────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Everything a plugin returns from a single [StreamPlugin.extract] call.
 * [streams] must never be empty on success.
 */
data class ExtractionResult(
    /** All resolved streams.  Audio-only streams should come before mixed streams. */
    val streams: List<AudioStream>,
    val title: String,
    val channel: String?,
    val durationSeconds: Long,
    /** HTTP(S) URL for the highest-quality thumbnail, or null. */
    val thumbnailUrl: String?
)

/**
 * A single audio (or audio+video fallback) stream returned by a plugin.
 */
data class AudioStream(
    /** Direct CDN URL — ready to pass to OkHttp. */
    val url: String,
    /** File extension matching the container: "m4a", "webm", "mp4". */
    val extension: String,
    /** MIME type: "audio/mp4", "audio/webm", "video/mp4". */
    val mimeType: String,
    /**
     * Approximate average bitrate in kbps; 0 means unknown.
     * Used by [com.lmusic.download.YouTubeDownloader] for quality selection.
     */
    val bitrateKbps: Int,
    /**
     * True if the stream carries audio only (no video mux).
     * The downloader prefers audio-only streams.
     */
    val isAudioOnly: Boolean,
    /**
     * Extra HTTP headers the CDN requires for this URL
     * (e.g. "Range", "Origin", or signed-URL headers).
     */
    val headers: Map<String, String> = emptyMap()
)
