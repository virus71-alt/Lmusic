# 🎵 Lmusic

> Download, stream, and play YouTube audio — straight from your phone, no copy-paste, no menus, no third-party server.

Lmusic turns your Android phone into a one-gesture music collector. Open YouTube, hear something you love, and **hold both volume buttons together for a quarter second** — the audio is saved to your Music folder before the song even ends.

It's also a full local music player: search YouTube, stream tracks instantly, build your favorites, browse a weekly listening rhythm, and control playback from the lock screen or a home-screen widget.

---

## ✨ Features

- 🎮 **One-gesture downloads** — hold Vol-Up + Vol-Down while any track is playing in YouTube
- 🔍 **In-app search & streaming** — type any song name and play it instantly
- 📚 **Library** — sort, filter, group by artist, search your downloaded tracks
- ❤️ **Favorites** — heart any track, see your top picks in Profile
- 📊 **Listener insights** — hours of music, top artists, weekly download chart
- ▶️ **Full-screen Now Playing** — circular vinyl art, sleep timer, queue sheet, shuffle & repeat
- 🏠 **Home-screen widget** — play / pause / skip without opening the app
- 💾 **Backup & restore** — export your library as a single file, re-download everything on a new phone with one tap
- 🔌 **Plugin system** — swap the stream extractor for an external plugin if YouTube changes
- 🌑 **Premium Doodle theme** — warm dark palette with hand-drawn-style edges

---

## 📥 Installing

1. Download the latest APK from the **[Releases](../../releases)** page.
2. Open it on your Android phone (Android 8.0 / API 26 or newer).
3. Tap **Install** — you may need to allow installs from your browser / file manager.

> The app is offered as a free APK — no Play Store listing required.

---

## 🔐 First-launch setup

The app walks you through four short steps on first open:

| Permission | What it's for | Where to grant |
|---|---|---|
| 🔔 Notification Access | Reads YouTube's media notification to find the current track | Settings → Notifications → Special access |
| ♿ Accessibility Service | Detects the Vol-Up + Vol-Down gesture | Settings → Accessibility |
| 📢 Notifications | Posts download progress | System dialog on launch |
| 💾 Storage | Only on Android 9 and below | Auto-granted on Android 10+ |

> 🛡️ **Privacy first.** The accessibility service only watches the two volume keys. It never reads screen content, never touches other apps, and never phones home.

---

## 🎯 How to use

### Download with the volume gesture

1. Play any song or video in **YouTube** or **YouTube Music**.
2. Hold **Vol-Up + Vol-Down together** for ~ a quarter second.
3. Wait for the "Downloaded" notification.
4. The file lands in `/Music/Lmusic/` — playable in every modern Android music player.

### Search and stream

1. Tap the **Search** tab at the bottom.
2. Type a song name or pick a mood chip (Lofi, Pop, Hip Hop, …).
3. Tap **▶** on any result to stream, or **↓** to download for offline listening.
4. The next few tracks are pre-queued automatically — Shuffle and Next just work.

### Add to favorites

Tap the heart on any row in **Library** or **Now Playing**. Your top picks show up on the **Favs** tab and the **Profile** screen.

### Control from anywhere

- 🔒 **Lock screen / shade** — the media notification has play, pause, skip
- 🏠 **Home screen widget** — long-press home → Widgets → drop "Lmusic" anywhere on the screen
- 🎧 **Bluetooth headphones** — standard play / pause / skip buttons are honoured

### Back up your library

Open **Settings → Backup & Restore → Export backup** to save a single `.lmusicbackup` file (just titles + YouTube URLs, tiny). On a new phone, import the same file and Lmusic re-downloads every track in the background.

---

## ⚙️ Settings worth knowing

- **Wi-Fi only** — skip downloads on mobile data
- **Audio quality** — best vs. smaller file size
- **Clean up song titles** — strips "(Official Video)", "[Lyrics]" etc.
- **Hold duration** — make the volume gesture more or less twitchy
- **Stream plugins** — swap the extractor without rebuilding the app
- **Download notifications** — silence them entirely if you prefer

---

## ⚠️ Known limitations

- 🔄 **YouTube changes often.** When YouTube updates their player, extraction can break for a few days until the underlying library catches up.
- 🎼 **Files keep YouTube's container.** Tracks land as `.m4a` or `.webm` (occasionally `.mp4`) — Lmusic does no transcoding to keep the APK small.
- 🔎 **Search-based detection isn't perfect.** When the YouTube notification doesn't expose an exact URL, Lmusic searches by song title. Live versions or lyric videos occasionally win over the official audio.

---

## 🐛 Reporting bugs

Open an [issue](../../issues) with:

- The screen / action that triggered the bug
- Any crash text from **Settings → View crash log**
- Your Android version + device model

---

## ❤️ Credits

Built with love for music nerds who hate menus. Open-source extraction powered by the **NewPipeExtractor** community.

---

<sub>Made with caffeine ☕ and stubbornness 💪</sub>
