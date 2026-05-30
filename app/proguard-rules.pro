# Keep Room entities
-keep class com.lmusic.data.** { *; }
# Keep yt-dlp manager intact (uses reflection for Process)
-keep class com.lmusic.download.** { *; }
