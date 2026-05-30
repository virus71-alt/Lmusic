package com.lmusic.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class YouTubeDownloader(private val ctx: Context) {

    companion object {
        private const val TAG = "YouTubeDownloader"
        private val initialized = AtomicBoolean(false)

        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    init {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(NewPipeDownloaderImpl)
        }
    }

    fun download(input: String, onProgress: (Float) -> Unit): DownloadResult {
        return try {
            // Resolve "ytsearch1:query" → real video URL via NewPipe search
            val videoUrl = if (input.startsWith("ytsearch")) {
                val query = input.substringAfter(":").trim()
                Log.d(TAG, "Resolving search query: $query")
                resolveSearchQuery(query)
                    ?: return DownloadResult.Failure("No results for: $query")
            } else input

            Log.d(TAG, "Extracting stream info for: $videoUrl")
            val info: StreamInfo = getStreamInfoWithRetry(videoUrl)

            Log.d(TAG, "Streams available: audio=${info.audioStreams?.size ?: 0} " +
                    "video=${info.videoStreams?.size ?: 0} " +
                    "videoOnly=${info.videoOnlyStreams?.size ?: 0}")

            // Prefer audio-only (smaller, better quality per byte).
            // Fall back to progressive video (.mp4 with embedded audio) when audio-only fails —
            // happens on some videos when nsig decryption can't handle the audio adaptive set.
            val audioStreams = info.audioStreams.orEmpty()
            val videoStreams = info.videoStreams.orEmpty()

            val (streamUrl, ext) = when {
                audioStreams.isNotEmpty() -> {
                    val a = audioStreams.maxByOrNull {
                        it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate
                    } ?: audioStreams.first()
                    Log.d(TAG, "Selected audio-only: ${a.format?.name} ${a.averageBitrate}kbps")
                    val url = a.content
                        ?: return DownloadResult.Failure("Audio stream URL missing")
                    url to (a.format?.suffix ?: "m4a")
                }
                videoStreams.isNotEmpty() -> {
                    // Pick lowest-res progressive video (we only want audio anyway)
                    val v = videoStreams.minByOrNull { it.height.takeIf { h -> h > 0 } ?: Int.MAX_VALUE }
                        ?: videoStreams.first()
                    Log.d(TAG, "Falling back to progressive video: ${v.format?.name} ${v.resolution}")
                    val url = v.content
                        ?: return DownloadResult.Failure("Video stream URL missing")
                    url to (v.format?.suffix ?: "mp4")
                }
                else -> return DownloadResult.Failure(
                    "No playable streams found. YouTube may have restricted this video."
                )
            }

            val title = sanitizeFilename(info.name ?: "track")
            downloadStream(streamUrl, title, ext, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            DownloadResult.Failure(e.message ?: e::class.java.simpleName)
        }
    }

    private fun downloadStream(
        url: String,
        title: String,
        ext: String,
        onProgress: (Float) -> Unit
    ): DownloadResult {
        val filename = "$title.$ext"
        val mime = when (ext.lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "webm" -> "audio/webm"
            "opus" -> "audio/ogg"
            else -> "audio/*"
        }

        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                return DownloadResult.Failure("HTTP ${response.code} from CDN")
            }
            val body = response.body ?: return DownloadResult.Failure("Empty response body")
            val totalBytes = body.contentLength().coerceAtLeast(1L)

            val out = openOutputStream(filename, mime)
                ?: return DownloadResult.Failure("Could not open output file")

            out.use { sink ->
                body.byteStream().use { source ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0
                    var totalRead = 0L
                    var lastReport = 0L
                    while (source.read(buf).also { read = it } != -1) {
                        sink.write(buf, 0, read)
                        totalRead += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            onProgress((totalRead * 100f / totalBytes).coerceIn(0f, 100f))
                            lastReport = now
                        }
                    }
                    sink.flush()
                }
            }
        }

        onProgress(100f)
        return DownloadResult.Success(filename)
    }

    /**
     * On Android 10+, write through MediaStore (no MANAGE_EXTERNAL_STORAGE needed).
     * On older Android, write directly to the public Music directory.
     */
    private fun openOutputStream(filename: String, mime: String): OutputStream? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Lmusic")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            return resolver.openOutputStream(uri)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Lmusic"
            )
            dir.mkdirs()
            return FileOutputStream(File(dir, filename))
        }
    }

    private fun sanitizeFilename(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(120)

    private fun getStreamInfoWithRetry(url: String, attempts: Int = 3): StreamInfo {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return StreamInfo.getInfo(ServiceList.YouTube, url)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Attempt ${attempt + 1}/$attempts failed: ${e.message}")
                if (attempt < attempts - 1) {
                    Thread.sleep(1000L * (attempt + 1))  // 1s, 2s backoff
                }
            }
        }
        throw lastError ?: RuntimeException("Stream extraction failed")
    }

    private fun resolveSearchQuery(query: String): String? {
        return try {
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(
                query,
                listOf("videos"),
                ""
            )
            val extractor = service.getSearchExtractor(handler)
            extractor.fetchPage()
            val firstStream = extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .firstOrNull()
            firstStream?.url.also { Log.d(TAG, "Search '$query' → $it") }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$query'", e)
            null
        }
    }
}

sealed class DownloadResult {
    data class Success(val filename: String) : DownloadResult()
    data class Failure(val error: String) : DownloadResult()
}
