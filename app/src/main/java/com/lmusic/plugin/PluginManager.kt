package com.lmusic.plugin

import android.content.Context
import android.util.Log
import com.lmusic.plugin.builtin.NewPipePlugin

/**
 * App-wide registry of stream-extraction plugins.
 *
 * Initialised once from [com.lmusic.LmusicApp.onCreate].
 * [YouTubeDownloader][com.lmusic.download.YouTubeDownloader] calls [findFor] to
 * obtain the right plugin for each download request.
 *
 * ── Priority ─────────────────────────────────────────────────────────────────
 *   1. Built-in plugins (NewPipePlugin) are always checked first.
 *   2. External sideloaded plugins are checked after built-ins.
 *
 * This means the built-in NewPipe implementation is always the default for YouTube;
 * an external plugin can override by also claiming supportsInput() = true for YouTube
 * URLs, but it would only win if it is listed before NewPipe — which never happens
 * given the current ordering.  If you want external plugins to take priority for
 * YouTube (e.g. to swap to an Innertube-based extractor), reverse the list order
 * inside [all].
 */
object PluginManager {

    private const val TAG = "PluginManager"

    // Immutable — built-ins always present.
    private val builtins: List<StreamPlugin> = listOf(NewPipePlugin())

    // Mutable — populated by refresh().
    private val externals = mutableListOf<StreamPlugin>()

    /** All currently available plugins (built-ins first). */
    val all: List<StreamPlugin> get() = builtins + externals

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Must be called once from Application.onCreate before any download.
     * Scans for external plugins and loads them.
     */
    fun init(ctx: Context) = refresh(ctx)

    /**
     * Re-scan for external plugins.
     * Call after the user installs or uninstalls a plugin APK.
     */
    fun refresh(ctx: Context) {
        externals.clear()
        externals += PluginLoader.discoverAll(ctx)
        Log.i(TAG, "Active plugins: ${all.map { "${it.name} v${it.version}" }}")
    }

    /**
     * Returns the first plugin that claims to handle [input],
     * or null if no plugin supports it.
     */
    fun findFor(input: String): StreamPlugin? =
        all.firstOrNull { it.supportsInput(input) }
}
