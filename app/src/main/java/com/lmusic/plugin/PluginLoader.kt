package com.lmusic.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader

/**
 * Discovers and loads external [StreamPlugin] implementations from installed APKs.
 *
 * ── External plugin APK contract ─────────────────────────────────────────────
 *
 *  1. Declare a metadata entry inside <application> in AndroidManifest.xml:
 *
 *       <meta-data
 *           android:name="lmusic.plugin.class"
 *           android:value="com.example.myplugin.YourPlugin" />
 *
 *  2. Implement [StreamPlugin] + ship [ExtractionResult] and [AudioStream] at
 *     the identical package path (com.lmusic.plugin.*).
 *
 *  3. Constructor: either no-arg OR single-arg (android.content.Context).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Loading works by creating a [DexClassLoader] with the *host* app's classloader
 * as the parent.  This means the [StreamPlugin] interface class is always resolved
 * from the host side, so the cast is safe even across separate APKs.
 */
object PluginLoader {

    private const val TAG = "PluginLoader"

    /** Manifest metadata key every plugin APK must declare. */
    const val META_KEY = "lmusic.plugin.class"

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scan all installed packages for [META_KEY] metadata, load each one,
     * and return the successfully instantiated plugins.
     *
     * Failures are logged and skipped so a bad plugin never crashes the host.
     */
    fun discoverAll(ctx: Context): List<StreamPlugin> {
        val appInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        return appInfoList.mapNotNull { appInfo ->
            if (appInfo.packageName == ctx.packageName) return@mapNotNull null  // skip self
            val className = appInfo.metaData?.getString(META_KEY) ?: return@mapNotNull null
            instantiate(ctx, appInfo.packageName, appInfo.sourceDir,
                        appInfo.nativeLibraryDir, className)
        }
    }

    /**
     * Load a single plugin from an already-installed package by [packageName].
     * Returns null if the package is not installed, has no metadata, or fails to load.
     */
    fun loadFromPackage(ctx: Context, packageName: String): StreamPlugin? {
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            }
            val className = appInfo.metaData?.getString(META_KEY)
                ?: return null.also {
                    Log.w(TAG, "$packageName has no '$META_KEY' metadata — not a plugin APK")
                }
            instantiate(ctx, packageName, appInfo.sourceDir, appInfo.nativeLibraryDir, className)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not installed: $packageName")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun instantiate(
        ctx: Context,
        packageName: String,
        apkPath: String,
        nativeLibDir: String?,
        className: String
    ): StreamPlugin? = runCatching {
        // Parent = host classloader → StreamPlugin interface always resolved from host side.
        val loader = DexClassLoader(
            apkPath,
            ctx.cacheDir.absolutePath,
            nativeLibDir,
            ctx.classLoader
        )
        val clazz = loader.loadClass(className)

        // Try Context constructor first, then fall back to no-arg.
        val plugin = try {
            clazz.getDeclaredConstructor(Context::class.java).newInstance(ctx)
        } catch (_: NoSuchMethodException) {
            clazz.getDeclaredConstructor().newInstance()
        } as StreamPlugin

        Log.i(TAG, "✔ Loaded external plugin '${plugin.name}' v${plugin.version} " +
                "from $packageName")
        plugin
    }.onFailure { e ->
        Log.e(TAG, "✘ Failed to load plugin class '$className' from $packageName: ${e.message}", e)
    }.getOrNull()
}
