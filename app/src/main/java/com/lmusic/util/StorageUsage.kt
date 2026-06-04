package com.lmusic.util

import android.content.Context
import androidx.core.net.toUri
import com.lmusic.data.DownloadRecord
import java.io.File
import java.text.DecimalFormat

object StorageUsage {

    /**
     * Best-effort total size of all downloaded tracks in bytes.
     * MediaStore URIs are sized via the content resolver; file paths via File.length().
     */
    fun totalBytes(ctx: Context, records: List<DownloadRecord>): Long {
        var total = 0L
        for (r in records) {
            val uri = r.fileUri ?: continue
            total += try {
                if (uri.startsWith("content://")) {
                    ctx.contentResolver.openAssetFileDescriptor(uri.toUri(), "r")?.use { it.length }
                        ?: 0L
                } else {
                    File(uri).takeIf { it.exists() }?.length() ?: 0L
                }
            } catch (_: Exception) { 0L }
        }
        return total
    }

    fun format(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digit = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
        val df = DecimalFormat("#,##0.#")
        return df.format(bytes / Math.pow(1024.0, digit.toDouble())) + " " + units[digit]
    }
}
