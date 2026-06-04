package com.lmusic.download

/**
 * Strips noise from YouTube video titles to recover something close to the actual song name.
 *
 * Examples:
 *   "Blinding Lights (Official Music Video)"        -> "Blinding Lights"
 *   "Tum Hi Ho - Aashiqui 2 | Official Video | HD"  -> "Tum Hi Ho"
 *   "Stairway to Heaven [Remastered 2007] (HD)"     -> "Stairway to Heaven"
 *   "Song Name | T-Series | New Hindi Song 2024"    -> "Song Name"
 *   "Believer (Lyrics)"                              -> "Believer"
 */
object TitleCleaner {

    // Each pattern matches a piece of "noise" inside parentheses or brackets.
    // Patterns are applied in order; whitespace cleanup happens at the end.
    private val NOISE_PATTERNS = listOf(
        // (Official Music Video), [Official Video], (Official Audio), (Official Lyric Video)
        Regex(
            """\s*[\[\(]\s*official\s+(?:music\s+)?(?:video|audio|lyric\s+video|lyrics\s+video|mv)\s*[\]\)]""",
            RegexOption.IGNORE_CASE
        ),
        // (Music Video), (Audio), (Video), (MV)
        Regex(
            """\s*[\[\(]\s*(?:music\s+video|audio|video|mv)\s*[\]\)]""",
            RegexOption.IGNORE_CASE
        ),
        // (Lyrics), (Lyric Video), [Lyrics Video], (Lyric)
        Regex(
            """\s*[\[\(]\s*(?:lyric|lyrics)\s*(?:video|vid)?\s*[\]\)]""",
            RegexOption.IGNORE_CASE
        ),
        // (HD), [4K], (UHD), [1080p], [720p], [480p]
        Regex(
            """\s*[\[\(]\s*(?:hd|uhd|4k|8k|1080p|720p|480p)\s*[\]\)]""",
            RegexOption.IGNORE_CASE
        ),
        // (Full Video Song), (Full Song), (Full Video), [Full HD Video]
        Regex(
            """\s*[\[\(]\s*full\s+(?:hd\s+)?(?:video\s+song|song|video)\s*[\]\)]""",
            RegexOption.IGNORE_CASE
        ),
        // (Remastered 2011), (Remastered), (Remaster)
        Regex(
            """\s*[\[\(]\s*remaster(?:ed)?(?:\s+\d{4})?\s*[\]\)]""",
            RegexOption.IGNORE_CASE
        ),
        // (Visualizer), (Visualiser), (Lyric Visualizer)
        Regex(
            """\s*[\[\(]\s*(?:lyric\s+)?visualis(?:er|or)\s*[\]\)]""",
            RegexOption.IGNORE_CASE
        ),
        // (Slowed + Reverb), (Slowed and Reverb), (Slowed), (Reverb), (Sped Up), (Nightcore)
        Regex(
            """\s*[\[\(]\s*(?:slowed(?:\s*(?:\+|&|and|n)\s*reverb)?|reverb|sped\s*up|nightcore)\s*[\]\)]""",
            RegexOption.IGNORE_CASE
        ),
        // (Live), (Live at...), (Acoustic), (Acoustic Version), (Cover), (Remix), (Extended Mix)
        Regex(
            """\s*[\[\(]\s*(?:live(?:\s+(?:performance|at\s+[^\[\]\(\)]+))?|acoustic(?:\s+version)?|cover|(?:original\s+|extended\s+|club\s+)?(?:re)?mix|instrumental|karaoke|mp3|320kbps|128kbps)\s*[\]\)]""",
            RegexOption.IGNORE_CASE
        ),
        // Standalone year in brackets: (2024), [2023]
        Regex("""\s*[\[\(]\s*\d{4}\s*[\]\)]"""),
        // (New Song), (Hindi Song), (Punjabi Song), (Latest Song)
        Regex(
            """\s*[\[\(]\s*(?:new\s+|latest\s+)?(?:hindi|punjabi|tamil|telugu|bhojpuri|english|malayalam|kannada)?\s*song\s*\d*\s*[\]\)]""",
            RegexOption.IGNORE_CASE
        ),

        // "FULL VIDEO:" or "FULL SONG:" prefix
        Regex("""^\s*full\s+(?:video|song)\s*[:\-–—]\s*""", RegexOption.IGNORE_CASE),
        // "NEW SONG:" prefix
        Regex("""^\s*new\s+(?:hindi\s+)?song\s*[:\-–—]\s*""", RegexOption.IGNORE_CASE),
        // Trailing "Full Video Song" / "Full Song" without brackets
        Regex("""\s+full\s+(?:video\s+song|song|video)\s*$""", RegexOption.IGNORE_CASE),

        // ----------------------------------------------------------------------
        // Unbracketed noise words anywhere in the title:
        //   "Din Shagna Da Lyrical Video | Phillauri" → "Din Shagna Da | Phillauri"
        // Order matters — multi-word patterns first so they match before single words.
        // ----------------------------------------------------------------------
        Regex("""\s+official\s+(?:music\s+)?(?:lyric\s+|lyrics\s+)?(?:video|audio|mv)\b""", RegexOption.IGNORE_CASE),
        Regex("""\s+lyrical\s+video\b""", RegexOption.IGNORE_CASE),
        Regex("""\s+lyric\s+video\b""", RegexOption.IGNORE_CASE),
        Regex("""\s+lyrics\s+video\b""", RegexOption.IGNORE_CASE),
        Regex("""\s+music\s+video\b""", RegexOption.IGNORE_CASE),
        Regex("""\s+full\s+hd\s+video\b""", RegexOption.IGNORE_CASE),
        Regex("""\s+full\s+video\b""", RegexOption.IGNORE_CASE),
        Regex("""\s+with\s+lyrics?\b""", RegexOption.IGNORE_CASE),

        // Trailing label segment after | : "| Official Audio", "| T-Series", "| Sony Music"
        // We match a pipe followed by everything to end of string if it contains a known label
        Regex(
            """\s*\|\s*(?:[^|]*\b(?:official|t-series|sony\s+music|saregama|zee\s+music|tips\s+(?:official|music)|yrf|warner\s+music|universal\s+music|jiosaavn|gaana|spotify|apple\s+music|hd|4k|hindi|punjabi|new\s+song|latest\s+song|lyric|lyrics|audio|video|music\s+video)\b[^|]*)\s*$""",
            RegexOption.IGNORE_CASE
        ),
        // Recursive: also strip a second trailing pipe segment if it's now junky too
        Regex(
            """\s*\|\s*(?:[^|]*\b(?:official|hd|4k|lyric|lyrics|audio|video|music\s+video|new\s+song|hindi\s+song|punjabi\s+song)\b[^|]*)\s*$""",
            RegexOption.IGNORE_CASE
        ),

        // "feat." normalization handled separately below
    )

    fun clean(raw: String): String {
        var result = raw

        // Apply each noise pattern repeatedly until no more matches
        // (handles "(Official Video) (HD)" — two consecutive groups)
        for (pattern in NOISE_PATTERNS) {
            var prev: String
            do {
                prev = result
                result = pattern.replace(result, "")
            } while (prev != result)
        }

        // Collapse whitespace and trim separators
        result = result
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('|', '-', '–', '—', '·', ':', ',')
            .trim()

        // Drop empty parens/brackets and collapse stray pipe separators
        result = result
            .replace(Regex("""\(\s*\)|\[\s*\]"""), "")
            .replace(Regex("""\s*\|\s*\|\s*"""), " | ")        // "A | | B" -> "A | B"
            .replace(Regex("""^\s*\|\s*|\s*\|\s*$"""), "")     // strip leading/trailing pipes
            .replace(Regex("""\s+"""), " ")
            .trim()

        return result.ifBlank { raw }
    }

    /**
     * Cleans the channel/uploader name. Removes the YouTube auto-generated "- Topic" suffix
     * that appears on Music's algorithm-created artist channels.
     */
    fun cleanChannel(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        return raw
            .replace(Regex("""\s*-\s*Topic\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { raw }
    }
}
