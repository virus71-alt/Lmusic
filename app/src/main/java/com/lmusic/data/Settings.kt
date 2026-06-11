package com.lmusic.data

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Typed wrapper over SharedPreferences. Keys match those in res/xml/preferences.xml.
 */
class Settings(ctx: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    enum class Quality(val key: String) { BEST("best"), SMALLER("smaller") }
    enum class SortOrder(val key: String) {
        NEWEST("newest"),
        OLDEST("oldest"),
        TITLE_AZ("title_az"),
        ARTIST_AZ("artist_az")
    }

    val audioQuality: Quality
        get() = when (prefs.getString(KEY_QUALITY, Quality.BEST.key)) {
            Quality.SMALLER.key -> Quality.SMALLER
            else -> Quality.BEST
        }

    val holdDurationMs: Long
        get() = prefs.getString(KEY_HOLD_DURATION, "250")?.toLongOrNull() ?: 250L

    val wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)

    val stripTitleNoise: Boolean
        get() = prefs.getBoolean(KEY_STRIP_NOISE, true)

    val convertToMp3: Boolean
        get() = prefs.getBoolean(KEY_CONVERT_MP3, true)

    /**
     * Lmusic ships in dark-only mode — the warm doodle palette is designed for
     * a dark background.  Returning MODE_NIGHT_YES unconditionally makes the OS
     * pick the `values-night` resource set across the app, regardless of system
     * setting.
     */
    val themeMode: Int
        get() = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES

    var sortOrder: SortOrder
        get() = SortOrder.values().firstOrNull { it.key == prefs.getString(KEY_SORT, null) }
            ?: SortOrder.NEWEST
        set(value) { prefs.edit().putString(KEY_SORT, value.key).apply() }

    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) { prefs.edit().putBoolean(KEY_ONBOARDING, value).apply() }

    var groupByArtist: Boolean
        get() = prefs.getBoolean(KEY_GROUP_BY_ARTIST, false)
        set(value) { prefs.edit().putBoolean(KEY_GROUP_BY_ARTIST, value).apply() }

    /** True if the user wants download progress / status notifications shown. */
    var downloadNotifications: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) { prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply() }

    var autoSyncThumbnails: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC_THUMBNAILS, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_SYNC_THUMBNAILS, value).apply() }

    companion object {
        const val KEY_QUALITY = "pref_quality"
        const val KEY_HOLD_DURATION = "pref_hold_duration"
        const val KEY_WIFI_ONLY = "pref_wifi_only"
        const val KEY_STRIP_NOISE = "pref_strip_noise"
        const val KEY_CONVERT_MP3 = "pref_convert_mp3"
        const val KEY_SORT = "pref_sort_order"
        const val KEY_ONBOARDING = "pref_seen_onboarding"
        const val KEY_GROUP_BY_ARTIST = "pref_group_by_artist"
        const val KEY_NOTIFICATIONS   = "pref_notifications"
        const val KEY_AUTO_SYNC_THUMBNAILS = "pref_auto_sync_thumbnails"
    }
}
