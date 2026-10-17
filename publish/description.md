# Streamer Replay

**Record everything automatically. Re-watch it from any angle. Fly cinematic camera paths. Export to MP4. No setup.**

Streamer Replay is a replay mod for **Minecraft 26.3 (Fabric)**, built for streamers and content creators who want
the clip *after* it happened, without having to plan for it.

## ✨ Highlights

- **Automatic recording.** Recording starts when you join a world or server and saves when you leave. It works in singleplayer and multiplayer.
- **REC badge and markers.** A small `● REC` indicator shows while recording (it can be hidden for a clean stream).
  Press **M** to mark a great moment. Markers show up on the replay timeline.
- **Replay browser** on the title screen:
  - Thumbnails, world name, date, duration and marker count.
  - Rename and delete with one click.
- **Watch like a video.**
  - Play/pause (**K**), ±5 s (**← / →**), speed from 0.1× to 16×.
  - A timeline you can scrub.
- **Free camera.** Fly anywhere. **Click any player or mob to follow them** (Shift returns to the free camera).
  All of this also works **while paused**: the world freezes, you keep looking around, flying and picking targets.
- **Camera path editor** (**O**):
  - Add keyframes (**I**). Each one stores position, rotation, FOV and **camera roll**.
  - Paths are smooth, drawn over the world while editing and previewed in real time.
- **Video export to MP4** (H.264 via ffmpeg):
  - Rendered frame by frame, so the video is perfectly smooth even when your PC is not.
  - **Real 1080p, 1440p or 4K**, rendered at that size whatever your window size.
  - **Clean frames** by default: no name tags, block outline, hand or debug overlays in the video.
  - Works with **Iris shader packs**.
  - A progress screen shows the ETA and has a cancel button.
- **Crash-safe.** If the game crashes mid-recording, the replay is recovered on the next start.
- **Settings screen** (also via Mod Menu), in **English and German**. Every text, including errors, is translated natively, and umlauts in replay and video names work.

## ⚡ Built to stay out of your way

- **Light on resources.**
  - Recording copies network data into reusable buffers and compresses and writes it on a background thread.
    There is no stutter and no per-packet garbage.
  - With nothing recording or replaying, the mod's hooks cost practically nothing.
- **Compatibility-friendly.** It uses only small, targeted mixin injections. Integrations are optional and guarded.
  The full end-to-end test (record, watch, follow, camera path, export, multiplayer) passes with **Sodium, Lithium and Mod Menu** installed,
  and with **Iris + Sodium and the MakeUp UltraFast shader pack** on OpenGL (software rendering on the test machine).
- **Private by default.** The server address is *not* stored in replays unless you enable it.

## 🎬 Video export needs ffmpeg

The mod does not include ffmpeg. Install it (e.g. `winget install ffmpeg`, `brew install ffmpeg`, or your Linux package manager)
or unzip it into `.minecraft/ffmpeg`. It is found automatically (PATH, WinGet, Scoop, Chocolatey, Homebrew, MacPorts),
or you can point the settings to `ffmpeg.exe`. The export screen tells you whether ffmpeg was found. Recording and watching
work without ffmpeg.

## 🎮 Controls (all rebindable)

| Key | Action |
| --- | --- |
| M | Add marker while recording |
| K | Play / pause |
| ← / → | Back / forward 5 seconds |
| O | Camera path editor |
| I | Add camera keyframe |
| Left click | Follow the entity under the crosshair |
| Esc | Replay menu |

## 📋 Requirements

- Minecraft **26.3**, Fabric Loader **0.19.5+**, **Fabric API**, Java 25.
- Client-side only. Servers don't need it, and it works on any server.

## ⚠ Notes

- Recording starts at join, so to record an ongoing session, rejoin (the game tells you this).
- Seeking backwards rebuilds the world; it takes well under a second for typical replays.
- Replays play back on the same Minecraft version they were recorded on.
- Not compatible with the original ReplayMod. Both tap the same network pipeline, so use one or the other.
- Respect server rules. Some servers do not allow recording.

## 🔒 Privacy

The mod makes **no network connections** of its own: no telemetry, no update checks, no analytics.
Replays and videos stay on your disk, in `.minecraft/replays` and `.minecraft/replay_videos`.
The only external program it starts is ffmpeg, and only when you export a video.

## License

MIT, open source: https://github.com/robb-devo/streamer-replay-mod

## 🛡 Is this safe?

- **Open source.** The full code is on GitHub.
- **No network connections, no telemetry.** The only program the mod ever starts is your own ffmpeg, and only when you export a video.
- **Verify the file.** Check it on [VirusTotal](https://www.virustotal.com) and compare its SHA-256 checksum:
  `2b3c5b71a673c319fb32235f0b8dee4ee8f271eb384637b2e383c38e4c1791af`
