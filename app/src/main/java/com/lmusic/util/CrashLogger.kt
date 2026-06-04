package com.lmusic.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val MAX_LOG_BYTES = 64 * 1024L

    fun install(appContext: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { writeCrash(appContext, thread, throwable) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Append an exception that was caught but is still worth recording. */
    fun logNonFatal(appContext: Context, tag: String, throwable: Throwable) {
        try { writeCrash(appContext, Thread.currentThread(), throwable, prefix = "NON-FATAL [$tag]") }
        catch (_: Throwable) {}
    }

    private fun writeCrash(
        ctx: Context,
        thread: Thread,
        throwable: Throwable,
        prefix: String = "CRASH"
    ) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val text = buildString {
            append("===== $prefix at $ts =====\n")
            append("Thread: ${thread.name}\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT})\n")
            append("Message: ${throwable.message}\n")
            append(sw.toString())
            append("\n\n")
        }

        val file = File(ctx.filesDir, "crash.log")
        // Rotate if oversized
        if (file.exists() && file.length() > MAX_LOG_BYTES) file.delete()
        file.appendText(text)
        Log.e(TAG, text)
    }

    fun read(ctx: Context): String? {
        val file = File(ctx.filesDir, "crash.log")
        return if (file.exists()) file.readText() else null
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, "crash.log").delete()
    }
}
