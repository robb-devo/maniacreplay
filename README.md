# ManiacReplay

A replay mod for **Minecraft 26.3 (Fabric)**, made for streamers. It records every session on its own, lets you
watch any moment again from any angle, and turns it into a smooth MP4, without setup or a tutorial.

## Getting started

1. Install Fabric Loader **0.19.5+** and **Fabric API 0.163.0+** for Minecraft 26.3 and put
   `maniacreplay-1.2.0.jar` into `.minecraft/mods`. Java 25 is required, the same as Minecraft 26.3.
2. Play. Recording starts when you join a world or server and is saved when you leave. Press **M** at a great
   moment to set a marker.
3. On the title screen, click the film-strip button next to *Accessibility*. Pick a replay, or click one of its
   **⚑ markers** to start 10 seconds before that moment.
4. In the replay press **J** for the editor and click *Export Video*. The first time, the export screen offers to
   install ffmpeg with one click (Windows); after that you get MP4 files.

Just want to play without recording? Open the pause menu and switch **Recording** (top left) off. It stays off
until you switch it back on.

## Features

**Recording**
- Automatic in singleplayer and on servers, saved when you leave. A small `● REC 12:34 ⚑2` badge shows while
  recording (can be hidden); it lights up gold when you set a marker, so no toast pops up on stream.
- One switch for on/off: in the pause menu, in the settings ("Record: Everywhere / Worlds only / Servers only /
  Off") and on an optional hotkey.
- Runs off-thread: packets are copied into pooled buffers and compressed and written by a background thread.
  With nothing recording, the cost is one volatile read per packet.
- Crash-safe: an unfinished recording is recovered on the next start (tested by killing the game with `SIGKILL`).
- Cleans up after itself: replays older than 30 days are deleted unless they have a marker or a name you gave
  them. Choose Never, 7, 14, 30 or 90 days in the settings.

**Watching**
- Play/pause, 0.1× to 16× speed, a timeline you can drag, ±5 s jumps.
- Free camera with smooth flying. Click an entity or player to follow it; Shift returns to the free camera.
- Everything works while paused: look around, fly, click to follow. The world stays frozen.
- Rewinding keeps the last picture on screen until the world at the new time is ready, like a video player
  buffering. Several ±5 s presses in a row are combined into one jump.
- Markers are flags on the timeline. When playback passes one, its name shows above the timeline for a moment;
  Ctrl + ← / → jumps between them.
- Chat is hidden in replays unless you turn it on, so private messages never show up on stream. Vanilla
  tutorial and onboarding toasts are not shown either.

**Camera paths** (editor: **J**)
- Keyframes (**U**) store position, direction, FOV and roll. ✔ updates the selected keyframe with the current
  camera, ✘ (or the Delete key) removes it.
- Smooth non-uniform Hermite splines (Catmull-Rom style), even for unevenly spaced keyframes. FOV and pitch never
  overshoot.
- The path is drawn into the world while the editor is open and can be previewed in real time.

**Video export**
- Rendered frame by frame at a fixed frame rate, so the video is smooth even when your PC is not. The window
  only shows a preview, which may stutter; that is expected.
- Native **1080p, 1440p, 4K** and **1080 × 1920 (9:16)** for Shorts and TikTok, whatever the window size. The
  preview shows a vertical video upright with black bars.
- **Clean frames** (on by default): no name tags, block outline, hand or debug views. HUD and menus are never in
  the video.
- Works with Iris shader packs.
- Without a camera path it films from your current view for 10 s, 30 s, 60 s or to the end.
- Without ffmpeg it saves PNG images. ffmpeg is found automatically (PATH, WinGet, Scoop, Chocolatey,
  `C:\ffmpeg`, Homebrew, MacPorts or `.minecraft/ffmpeg`), even when the launcher does not see your PATH.

English and German, including every error message. Settings are also reachable through Mod Menu.

## Controls

All keys can be changed under *Options → Controls → ManiacReplay*.

| Key | Action |
| --- | --- |
| M | Add marker (while recording) |
| ↓ (arrow down) | Play / pause the replay |
| ← / → | Back / forward 5 s |
| Ctrl + ← / → | Previous / next marker (starts 10 s before it) |
| J | Open the camera path editor |
| U | Add a camera keyframe at the current view |
| Left click | Follow the entity under the crosshair (Shift leaves) |
| WASD + Space/Shift | Fly the free camera (scroll changes speed) |
| Esc | Replay menu (back to replay, editor, settings, exit) |
| *(unbound)* | Recording on / off |

In the editor, **right-drag** looks around and **WASD** flies. The defaults clash with nothing in vanilla 26.3
(including F3 combinations), Iris, Sodium or Mod Menu; an automated test checks this.

## Files

- `.minecraft/replays/`: `*.sreplay` files, plus a small `*.sreplay.paths` file for each camera path.
- `.minecraft/replay_videos/`: exported videos, with an ffmpeg log next to each one.
- `.minecraft/ffmpeg/`: ffmpeg, if you installed it with the button.
- `.minecraft/config/streamerreplay.json`: settings.

## Privacy and network

- **No telemetry, no analytics, no update checks.**
- The mod goes online **only** when you click *Install ffmpeg*. It then downloads exactly one file,
  [ffmpeg 9.0.2 essentials](https://github.com/GyanD/codexffmpeg/releases/tag/9.0.2) from Gyan's GitHub page (the
  build `winget install ffmpeg` installs), checks its SHA-256 against the value in
  [`FfmpegInstaller`](src/client/java/dev/robb/streamerreplay/render/FfmpegInstaller.java) and unpacks only
  `ffmpeg.exe`.
- The only program it starts is ffmpeg, and only to check it or to export a video.
- Server addresses are not stored in replays unless you turn it on. Your player name and UUID are stored, so a
  replay knows who recorded it.
- Respect server rules: some servers do not allow recording.

**Check the file yourself:** compare the SHA-256 of the jar with the one in the release notes
(`Get-FileHash maniacreplay-1.2.0.jar -Algorithm SHA256` on Windows, `sha256sum` elsewhere), or upload it to
[VirusTotal](https://www.virustotal.com). The source code is in this repository under the MIT license and contains
no code from other replay mods.

## Known limitations

- **Recording starts when you join.** A replay needs the world exactly as the server sent it at login
  (registries, chunks, entities); rebuilding that from the client's partial copy would give replays that look
  right but break when you seek. To record a session that is already running, leave and rejoin.
- **Rewinding rebuilds the world** from the start and fast-forwards. Measured: 0.3–0.5 s for 15 s and 93 s
  replays, almost all of it the fixed reconnect cost. Packet delivery takes about 1.3 ms per recorded second, so
  a one-hour replay should take about 5 s (estimated, not measured). Periodic snapshots were considered and
  rejected: the client world cannot be copied and restored safely.
- Replays only play in the Minecraft version they were recorded with.
- Videos have no sound.
- Server resource packs are not loaded in replays (the mod never downloads them).
- The largest export size is limited by the GPU's maximum texture size (4K works on every current GPU).
- Not compatible with the original *ReplayMod*: both tap the same network pipeline.

## Compatibility

- Mixins are small injections (`@Inject`, one `@ModifyArg`, one accessor); no `@Overwrite`, no `@Redirect`.
  Every hook first checks one static field and returns at once when no replay is open.
- The full end-to-end test passes on **Windows 11 with a real GPU** (version 1.2.0, English and German).
- Earlier versions also passed it with **Sodium 0.9.3-beta.1, Lithium 0.26.2, Mod Menu 21.0.0**, and with
  **Iris 1.11.7 + Sodium and the MakeUp UltraFast 9.5g shader pack** on OpenGL (Linux, software rendering).
  Not tested yet: macOS, Vulkan with Iris.
- Report problems with an exact mod list.

## Building

```
./gradlew build                 # jar in build/libs, runs the unit tests (needs Java 25)
./gradlew runClientGameTest     # full end-to-end test in a real game window
```

## Testing

- `./gradlew build`: unit tests for the file format, crash recovery, camera paths and splines, the clean-up rule,
  time formatting, ffmpeg discovery on Windows/macOS/Linux (simulated file systems), and a check that German has
  every English key.
- `./gradlew runClientGameTest`: one end-to-end client test. It records a singleplayer world, uses the recording
  switch, renames, opens, seeks and rewinds, follows entities, uses mouse look, flight and clicks while paused,
  builds a camera path, exports MP4 at window size, native 1080p (ffprobe plus a sharpness measurement) and
  1080×1920, compares clean frames on and off, records a dedicated server and checks that every label fits its
  button.
  - `-PgametestLang=de_de` runs it in German.
  - `-PcompatMods=a.jar,b.jar` adds other mods (Sodium, Lithium, Mod Menu, Iris).
  - `-PshaderPack=pack.zip` installs and enables a shader pack (with Iris). On Linux without a GPU this needs
    `SDL_VIDEO_FORCE_EGL=1 LIBGL_ALWAYS_SOFTWARE=1`.
  - `-PgametestProps=streamerreplay.gametest.mode=seekbench` records a 93 s replay and measures seeks.
- `scripts/crash-recovery-test.sh` (Linux): records, kills the game with `SIGKILL`, starts it again and checks that
  the replay was recovered with its marker and plays.

## License

MIT. All code is original.
