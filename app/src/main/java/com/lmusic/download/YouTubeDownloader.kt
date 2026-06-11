package com.lmusic.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.lmusic.data.Settings
import com.lmusic.plugin.AudioStream
import com.lmusic.plugin.PluginManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Orchestrates a full download:
 *   1. Delegate stream resolution to the appropriate [com.lmusic.plugin.StreamPlugin]
 *      via [PluginManager].
 *   2. Select the best audio stream based on user quality settings.
 *   3. Fetch cover art.
 *   4. Download the audio stream to the device cache.
 *   5. Copy the file into the public Music/Lmusic MediaStore folder.
 *
 * This class contains NO YouTube-specific code.  All platform-specific extraction
 * is encapsulated inside plugin implementations (e.g. NewPipePlugin).
 */
class YouTubeDownloader(private val ctx: Context) {

    companion object {
        private const val TAG = "YouTubeDownloader"

        /** Shared OkHttp client — used only for thumbnail + CDN file downloads. */
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    fun download(
        input: String,
        onProgress: (Float) -> Unit,
        onStage: (String) -> Unit = {}
    ): DownloadResult {
        return try {
            // ── 1. Find a plugin ───────────────────────────────────────────
            onStage("Resolving stream…")
            val plugin = PluginManager.findFor(input)
                ?: return DownloadResult.Failure(
                    "No plugin can handle this URL.\n" +
                    "Install a plugin from Settings → Plugins."
                )

            Log.d(TAG, "Using plugin '${plugin.name}' for: $input")

            // ── 2. Extract streams + metadata ──────────────────────────────
            val result = plugin.extract(input)   // blocking — must run on IO thread

            if (result.streams.isEmpty()) {
                return DownloadResult.Failure("Plugin '${plugin.name}' returned no streams.")
            }

            // ── 3. Quality selection ───────────────────────────────────────
            val settings = Settings(ctx)
            val preferSmallest = settings.audioQuality == Settings.Quality.SMALLER
            val chosen: AudioStream = selectBest(result.streams, preferSmallest)
                ?: return DownloadResult.Failure("No playable stream found.")

            Log.d(TAG, "Chosen: ${chosen.extension} ~${chosen.bitrateKbps}kbps " +
                    "audioOnly=${chosen.isAudioOnly}")

            // ── 4. Prepare filenames ───────────────────────────────────────
            val rawTitle    = result.title
            val cleanTitle  = if (settings.stripTitleNoise) TitleCleaner.clean(rawTitle) else rawTitle
            val cleanChan   = TitleCleaner.cleanChannel(result.channel)
            val safeFile    = sanitizeFilename(cleanTitle)

            Log.d(TAG, "Title: \"$rawTitle\" → \"$cleanTitle\"")

            // ── 5. Cover art ───────────────────────────────────────────────
            onStage("Fetching cover art…")
            val thumbnailPath = result.thumbnailUrl?.let { downloadThumbnail(it) }

            // ── 6. Audio download ──────────────────────────────────────────
            onStage("Downloading audio…")
            val cacheTemp = File(ctx.cacheDir, "lmusic_dl.${chosen.extension}")
            cacheTemp.delete()
            val downloadOk = downloadToFile(chosen.url, cacheTemp, chosen.headers) { p ->
                onProgress(p * 0.80f)
            }
            if (!downloadOk) return DownloadResult.Failure("Download from CDN failed")

            // ── 7. Convert to MP3 ─────────────────────────────────────────
            val wantMp3 = settings.convertToMp3
            val finalFile: File
            val finalExtension: String
            val finalMime: String

            if (wantMp3 && chosen.extension != "mp3") {
                onStage("Converting to MP3…")
                onProgress(82f)
                val conv = Mp3Converter.convert(cacheTemp)
                if (conv.success && conv.outputFile != null) {
                    cacheTemp.delete()
                    finalFile = conv.outputFile
                    finalExtension = "mp3"
                    finalMime = "audio/mpeg"
                } else {
                    Log.w(TAG, "MP3 conversion failed, saving original: ${conv.error}")
                    finalFile = cacheTemp
                    finalExtension = chosen.extension
                    finalMime = chosen.mimeType
                }
            } else {
                finalFile = cacheTemp
                finalExtension = chosen.extension
                finalMime = chosen.mimeType
            }

            // ── 8. Save to Music folder ────────────────────────────────────
            onStage("Saving to Music folder…")
            onProgress(92f)
            val outFilename = "$safeFile.$finalExtension"
            val savedUri = saveToMusicFolder(finalFile, outFilename, finalMime)
            finalFile.delete()
            if (savedUri == null) return DownloadResult.Failure("Could not save to Music folder")
            onProgress(100f)

            DownloadResult.Success(
                filename        = outFilename,
                displayTitle    = cleanTitle,
                channel         = cleanChan,
                durationSeconds = result.durationSeconds,
                thumbnailPath   = thumbnailPath,
                fileUri         = savedUri
            )
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            DownloadResult.Failure(e.message ?: e::class.java.simpleName)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stream selection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prefer audio-only streams; among those pick highest or lowest bitrate
     * based on [preferSmallest].  Fall back to mixed streams if no audio-only available.
     */
    private fun selectBest(streams: List<AudioStream>, preferSmallest: Boolean): AudioStream? {
        val pool = streams.filter { it.isAudioOnly }.ifEmpty { streams }
        return if (preferSmallest) {
            pool.minByOrNull { it.bitrateKbps.takeIf { b -> b > 0 } ?: Int.MAX_VALUE }
        } else {
            pool.maxByOrNull { it.bitrateKbps }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Thumbnail
    // ─────────────────────────────────────────────────────────────────────────

    private fun downloadThumbnail(url: String): String? {
        return try {
            val thumbDir = File(ctx.filesDir, "thumbnails").apply { mkdirs() }
            val outFile  = File(thumbDir, "${url.hashCode()}.jpg")
            if (outFile.exists()) return outFile.absolutePath
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                outFile.outputStream().use { body.byteStream().copyTo(it) }
            }
            outFile.absolutePath
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File download with progress (supports plugin-supplied custom headers)
    // ─────────────────────────────────────────────────────────────────────────

    private fun downloadToFile(
        url: String,
        dest: File,
        extraHeaders: Map<String, String>,
        onProgress: (Float) -> Unit
    ): Boolean {
        val reqBuilder = Request.Builder().url(url)
        extraHeaders.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code} from CDN")
                return false
            }
            val body       = response.body ?: return false
            val totalBytes = body.contentLength().coerceAtLeast(1L)
            dest.outputStream().use { sink ->
                body.byteStream().use { source ->
                    val buf       = ByteArray(64 * 1024)
                    var read: Int
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
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaStore / filesystem save
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveToMusicFolder(src: File, filename: String, mime: String): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Lmusic")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val ok = resolver.openOutputStream(uri)?.use { sink ->
                src.inputStream().use { it.copyTo(sink) }; true
            } ?: false
            if (ok) uri.toString() else null
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Lmusic"
            )
            dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { sink -> src.inputStream().use { it.copyTo(sink) } }
            file.absolutePath
        }
    }

    private fun sanitizeFilename(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(120)
}

// ─────────────────────────────────────────────────────────────────────────────
// Result type
// ─────────────────────────────────────────────────────────────────────────────

sealed class DownloadResult {
    data class Success(
        val filename: String,
        val displayTitle: String = filename,
        val channel: String? = null,
        val durationSeconds: Long = 0L,
        val thumbnailPath: String? = null,
        val fileUri: String? = null
    ) : DownloadResult()

    data class Failure(val error: String) : DownloadResult()
}
