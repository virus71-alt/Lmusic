# =============================================================================
# Lmusic ProGuard / R8 rules — release shrink + obfuscate friendly.
# =============================================================================

# ── Project ──────────────────────────────────────────────────────────────────

# Room entities + DAO interfaces are referenced reflectively by generated code
-keep class com.lmusic.data.** { *; }

# Plugin interfaces are reflected on by DexClassLoader for external plugin APKs.
# The classes must be loadable by their declared name, fields untouched.
-keep interface com.lmusic.plugin.StreamPlugin { *; }
-keep interface com.lmusic.plugin.SearchablePlugin { *; }
-keep class com.lmusic.plugin.ExtractionResult { *; }
-keep class com.lmusic.plugin.AudioStream { *; }
-keep class com.lmusic.plugin.SearchResult { *; }
-keep class com.lmusic.plugin.PluginLoader { *; }

# Built-in plugins are instantiated by reflection via the manifest meta-data key.
-keep class com.lmusic.plugin.builtin.** { *; }

# Crash logger uses reflection-friendly install path.
-keep class com.lmusic.util.CrashLogger { *; }

# ── NewPipeExtractor ─────────────────────────────────────────────────────────
# Uses runtime reflection + Rhino JS engine; safest to keep the whole package.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.mozilla.**

# ── Media3 (ExoPlayer) ───────────────────────────────────────────────────────
# Reflective lookups for codec selectors + renderers.
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Room ─────────────────────────────────────────────────────────────────────
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# ── Kotlin coroutines ────────────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.flow.**
-dontwarn kotlinx.coroutines.debug.**

# ── OkHttp / Okio ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── WorkManager ──────────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class com.lmusic.worker.** { *; }

# ── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── ViewBinding ──────────────────────────────────────────────────────────────
-keep class com.lmusic.databinding.** { *; }

# ── App-wide stripping safety ────────────────────────────────────────────────

# Strip Log.d / Log.v from release builds (saves ~5-10% method count + frees the
# obfuscated names from leaking debug strings into stack traces).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Allow R8 to inline + optimize aggressively.
-allowaccessmodification
-repackageclasses ''
