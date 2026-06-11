package com.lmusic.download

import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File

/**
 * Converts audio files (m4a, webm, mp4, ogg, etc.) to MP3 using FFmpeg-Kit.
 *
 * The conversion uses a 192 kbps encode by default — good balance between
 * quality and file size for music downloaded from YouTube.
 */
object Mp3Converter {

    private const val TAG = "Mp3Converter"

    data class Result(val success: Boolean, val outputFile: File?, val error: String? = null)

    /**
     * Convert [inputFile] to MP3 in the same directory.
     * Returns [Result] with the output .mp3 [File] on success.
     *
     * @param bitrate Target bitrate in kbps (128, 192, 256, 320). Default 192.
     * @param onProgress Optional callback with progress percentage (0–100).
     */
    fun convert(
        inputFile: File,
        bitrate: Int = 192,
        onProgress: ((Float) -> Unit)? = null
    ): Result {
        val outputFile = File(
            inputFile.parentFile,
            inputFile.nameWithoutExtension + ".mp3"
        )
        outputFile.delete()

        val cmd = buildString {
            append("-i \"${inputFile.absolutePath}\" ")
            append("-vn ")                   // strip any video/cover art stream
            append("-acodec libmp3lame ")     // encode to MP3
            append("-ab ${bitrate}k ")       // target bitrate
            append("-ar 44100 ")             // standard sample rate
            append("-ac 2 ")                 // stereo
            append("-y ")                    // overwrite output
            append("\"${outputFile.absolutePath}\"")
        }

        Log.d(TAG, "FFmpeg command: $cmd")

        val session = FFmpegKit.execute(cmd)
        val rc = session.returnCode

        return if (ReturnCode.isSuccess(rc)) {
            Log.d(TAG, "Conversion OK: ${outputFile.name} (${outputFile.length() / 1024}KB)")
            Result(success = true, outputFile = outputFile)
        } else {
            val error = session.failStackTrace ?: "FFmpeg returned code ${rc?.value}"
            Log.e(TAG, "Conversion failed: $error")
            outputFile.delete()
            Result(success = false, outputFile = null, error = error)
        }
    }
}
