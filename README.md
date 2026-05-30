# 🎵 Lmusic

> Download YouTube audio with a hardware-button gesture. No menus. No copy-paste. No foreground app required.

Play any video in the YouTube app, hold **Volume-Up + Volume-Down together** for a quarter second, and the audio is saved straight to your Music folder. 🎧

Built as a native Android app in Kotlin. Uses NewPipeExtractor to resolve YouTube streams — no external binaries, no server. ✨

---

## ⚡ How it works

```
🎮 Hold Vol-Up + Vol-Down for 250ms
        |
        v
🛡️ AccessibilityService detects the combo and fires a trigger
        |
        v
🔍 YouTubeDetector finds what's playing:
   • NotificationListener cache (exact URL if YouTube exposes it)
   • MediaSession metadata fallback (title + artist)
        |
        v
🧩 NewPipeExtractor resolves to a video and extracts stream URLs
        |
        v
📥 Foreground DownloadService streams audio to /Music/Lmusic/
   • Prefers audio-only (m4a / webm)
   • Falls back to progressive mp4 if audio-only is restricted
        |
        v
🔔 Notification shows progress, then completion
💾 Room database records the download for the in-app history
```

---

## 🚀 Setup

1. 📦 Clone the repo and open in Android Studio (Hedgehog or later).
2. ⏳ Let Gradle sync. The first sync builds NewPipeExtractor from a JitPack snapshot and can take several minutes.
3. 📱 Plug in an Android phone (API 26+, ARM64) and hit Run.

No yt-dlp binary, no FFmpeg, no native dependencies. Everything runs in-process on the JVM. 🪶

---

## 🔐 Permissions

The app shows a guided 4-step setup on first launch. Grant in order:

| 🧩 Permission | 💡 What it does | 📍 Where to grant |
|---|---|---|
| 🔔 Notification Access | Reads YouTube's media notification to find the video URL | Settings → Notifications → Special access |
| ♿ Accessibility Service | Detects the Vol-Up + Vol-Down combo | Settings → Accessibility |
| 📢 Notifications | Posts download progress | System dialog on first launch |
| 💾 Storage | Only required on Android 9 and below | Auto-granted on Android 10+ via MediaStore |

> 🛡️ **Privacy:** The accessibility service only reads volume key events. It does not read screen content, intercept other keys, or interact with other apps.

---

## 🎯 Usage

1. ▶️ Play any song or video in the YouTube or YouTube Music app.
2. 🎮 Hold **Vol-Up + Vol-Down together** for about a quarter second.
3. ⏬ Wait for the "Downloaded" notification.
4. 📂 Find the file under `/Music/Lmusic/`.

Files are saved in their original format — usually `.m4a` (AAC), occasionally `.webm` (Opus), or `.mp4` for restricted videos. All three play in every modern Android music player. 🎶

---

## 🛠️ Tech stack

- 🟣 **Kotlin** with Android Views (no Compose; ViewBinding for layouts)
- 🧪 **NewPipeExtractor** (master-SNAPSHOT via JitPack) for YouTube stream resolution
- 🌐 **OkHttp** for the HTTP layer (both NewPipe's downloader and the file fetch)
- 🗄️ **Room** for the download history
- 🔁 **WorkManager** for retry on transient failures
- ⚙️ **Coroutines** for the foreground service work

---

## 📁 Project layout

```
app/src/main/java/com/lmusic/
  LmusicApp.kt                  🚪 Application class, notification channels
  MainActivity.kt               🏠 Hosts the setup wizard or download history
  service/
    LmusicAccessibilityService  🎮 Vol-Up + Vol-Down combo detection
    LmusicNotificationListener  🔔 Caches YouTube's current video URL/title
    DownloadService             📥 Foreground service running the download
  youtube/YouTubeDetector       🔍 Resolves the playing video URL
  download/
    NewPipeDownloaderImpl       🌐 OkHttp-backed HTTP layer for NewPipe
    YouTubeDownloader           🧩 Stream extraction + file download
  data/                         🗄️ Room entity, DAO, database
  ui/                           🎨 Permission wizard + history fragments
```

---

## ⚠️ Known limitations

- 🏗️ **ARM64 only in practice.** Tested on `arm64-v8a` devices; should also work on x86_64 since there are no native binaries, but untested.
- 🔄 **YouTube changes frequently.** When YouTube updates its player obfuscation, the `master-SNAPSHOT` NewPipeExtractor build picks up fixes within days. If extraction breaks, re-sync Gradle to pull the latest snapshot.
- 🎼 **Output format follows YouTube.** Files are saved in whatever container YouTube serves. There is no MP3 transcoding step (would require shipping FFmpeg, ~30 MB).
- 🔎 **Search fallback may pick the wrong video** when the YouTube notification doesn't expose the exact URL. The first search result for the song title is almost always correct, but lyric videos and live versions can occasionally win over the official audio.

---

## ❤️ Acknowledgments

- 🧩 [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — the YouTube stream extraction engine.
- 🛠️ [yt-dlp](https://github.com/yt-dlp/yt-dlp) — originally bundled, removed once it became clear no Linux yt-dlp build runs on Android's bionic libc.

---

<sub>Made with caffeine ☕ and stubbornness 💪</sub>
