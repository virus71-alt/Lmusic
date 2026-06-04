package com.lmusic

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lmusic.download.DownloadRunner

/**
 * Invisible activity that receives YouTube share intents.
 * User taps Share in YouTube → selects "Lmusic" → this fires → download starts → activity finishes.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = extractUrl(intent)
        if (url != null) {
            DownloadRunner.start(this, url)
            Toast.makeText(this, "Lmusic: downloading…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Lmusic: couldn't find a YouTube URL", Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    private fun extractUrl(intent: Intent?): String? {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        // YouTube share text is usually "Song title\nhttps://youtu.be/ID" or a full URL
        val ytRegex = Regex("""https?://(?:(?:www\.)?youtube\.com/watch\?v=|youtu\.be/)([\w-]{11})""")
        val match = ytRegex.find(text) ?: return null
        return match.value
    }
}
