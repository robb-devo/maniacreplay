# ManiacReplay

[![build](https://github.com/robb-devo/maniacreplay/actions/workflows/build.yml/badge.svg)](https://github.com/robb-devo/maniacreplay/actions/workflows/build.yml)

A replay mod for Minecraft 26.3 (Fabric). It records every session on its own, lets you watch any moment again
from any angle, and exports smooth MP4 videos. It is built for streamers: there is nothing to set up.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5 or newer, Fabric API 0.163.0 or newer, Java 25.
- Client only. It works on any server; the server does not need it.
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) for the settings button in the mod list, ffmpeg for MP4
  export (the mod can install it on Windows).

## Using it

1. Put `maniacreplay-<version>.jar` into `.minecraft/mods` and play. Recording starts when you join a world or a
   server and is saved when you leave. A small `● REC` badge shows that it is running.
2. Press **M** at a moment worth keeping. Markers show up in the replay list and on the timeline.
3. On the title screen, click the film-strip button next to *Accessibility*. Click a replay to watch it, or click
   one of its ⚑ markers to start 10 seconds before that moment.
4. Press **J** in a replay to open the editor and click *Export Video*. Without a camera path it films from the
   current view; without ffmpeg it offers to install it (Windows) or saves PNG frames.

To play without recording, open the pause menu and switch *Recording* (top left) off. It stays off until you
switch it on again.

## Controls

All keys can be changed under *Options → Controls → ManiacReplay*.

| Key | Action |
| --- | --- |
| M | Add a marker while recording |
| ↓ | Play / pause |
| ← / → | Back / forward 5 s (during a path preview: move the preview) |
| Ctrl + ← / → | Previous / next marker |
| J | Camera path editor |
| U | Add a camera keyframe at the current view |
| Left click | Follow the player or mob under the crosshair; Shift returns to the free camera |
| WASD, Space, Shift | Fly the free camera; the scroll wheel changes its speed |
| Esc | Replay menu |
| *(unbound)* | Recording on / off |

In the editor, hold the right mouse button to look around. The default keys do not overlap with vanilla 26.3
(including F3 combinations) or with the defaults of Iris, Sodium and Mod Menu.

## Features

**Recording.** Every packet the server sends is copied with a timestamp into pooled buffers and compressed and
written by a background thread; when nothing is recorded the cost is one volatile read per packet. The file is
flushed about once a second, so after a crash the recording is recovered on the next start. If the disk falls far
behind, the recording is stopped and saved instead of growing in memory. Automatic clean-up of old replays is
available but off by default; replays with markers or a custom name are never deleted by it.

**Watching.** Play, pause, 0.1× to 16× speed, a timeline to scrub, a free camera and click-to-follow, all of it
also while paused. Jumping back rebuilds the world from the start of the recording (see *Limitations*); meanwhile
the last picture stays on screen with a progress badge. When the recorded player changes dimension, the camera
follows them. Recorded chat, including private messages, is hidden unless *Chat in Replays* is switched on.

**Camera paths.** Keyframes store position, direction, FOV and roll. The curve between them is a shape-preserving
cubic spline: smooth, and it never swings past a keyframe, however unevenly the keyframes are spaced. The path is
drawn into the world while the editor is open and can be previewed in real time.

**Video export.** Frames are rendered one by one at a fixed rate, so the video is smooth regardless of the frame
rate on screen; the window only shows a preview. The game renders natively at the export size: 720p, 1080p,
1440p, 4K or 1080 × 1920 for Shorts and TikTok, independent of the window. Name tags, the block outline and debug
overlays are left out by default; the HUD is never in the video. H.264 through `libx264`, MPEG-4 otherwise.

## Files

| Path | Content |
| --- | --- |
| `.minecraft/replays/` | `*.sreplay` recordings, `*.sreplay.paths` camera paths |
| `.minecraft/replay_videos/` | Exported videos, each with its ffmpeg log |
| `.minecraft/ffmpeg/` | ffmpeg, if installed from the mod |
| `.minecraft/config/streamerreplay.json` | Settings |

The mod id, package and config file still carry the project's first name, `streamerreplay`, so that settings,
recordings and key bindings survive the rename.

## Privacy and network

- No telemetry, analytics or update checks.
- The mod connects to the internet only when you click *Install ffmpeg*. It then downloads one file,
  [ffmpeg 9.0.2 essentials](https://github.com/GyanD/codexffmpeg/releases/tag/9.0.2) from Gyan's GitHub releases,
  verifies its SHA-256 against the value in
  [`FfmpegInstaller`](src/client/java/dev/robb/streamerreplay/render/FfmpegInstaller.java), and unpacks only
  `ffmpeg.exe`.
- The only program it runs is ffmpeg, to check it and to export.
- Replays store your player name and UUID. The server address is only stored if you switch that on.
- Some servers do not allow recording. Respect their rules.

## Limitations

- **Recording starts when you join.** A replay needs the world exactly as the server sent it at login; rebuilding
  that from the client's partial copy would give replays that look right but break when you seek. To record a
  session that is already running, leave and rejoin.
- **Jumping back replays the recording from its start.** It costs about 1.6 ms per recorded second, measured on a
  46 minute singleplayer recording on Windows 11: 3.9 s to jump back near its end, 1.2 s to a point five minutes in.
  An hour-long recording takes about 6 s, three hours about 20 s. Jumping forward is fast. Snapshots that would
  avoid the rebuild were considered and rejected: the client world cannot be copied and restored safely.
- Replays play only in the Minecraft version they were recorded with.
- Videos have no sound.
- Server resource packs are not loaded in replays.
- The largest export size is limited by the GPU's maximum texture size.
- Not compatible with ReplayMod; both tap the same network pipeline.

## Compatibility

The mixins are small injections (`@Inject`, one `@ModifyArg`, one accessor; no `@Overwrite` or `@Redirect`), and
every hook returns at once when no replay is open. The full end-to-end test passes on Windows 11 with an NVIDIA GPU,
on both graphics backends (OpenGL and Vulkan) and in English and German. On Vulkan it does not resize the window: in
Minecraft 26.3 that loses the Vulkan device even on the title screen, with no replay open. Version 1.1 also passed
it with Sodium 0.9.3-beta.1, Lithium 0.26.2, Mod Menu 21.0.0, and with Iris 1.11.7 and the MakeUp UltraFast shader
pack (Linux, OpenGL). macOS has not been tested.

Please report problems with your exact mod list and `logs/latest.log`.

## Building and testing

```
./gradlew build               # mod jar in build/libs, runs the unit tests
./gradlew runClientGameTest   # end-to-end test in a real game window
```

The unit tests cover the file format and crash recovery, camera path interpolation, the clean-up rule, time
formatting, ffmpeg discovery on Windows, macOS and Linux, and that German has every English text.

The end-to-end test records a singleplayer world and a dedicated server (including a trip to the Nether), uses the
recording switch, browses, renames, seeks, follows entities, looks around and clicks while paused, builds a camera
path, exports at window size, native 1080p and 1080 × 1920 (checked with ffprobe and a sharpness measurement),
compares clean frames, checks the window preview of an export to the pixel, clicks through the editor with real
mouse input and checks that every label fits its button. Options:

- `-PgametestLang=de_de` runs it in German.
- `-PgraphicsApi=vulkan` (or `opengl`) chooses Minecraft's graphics backend.
- `-PcompatMods=a.jar,b.jar` loads other mods, `-PshaderPack=pack.zip` enables an Iris shader pack.
- `-PgametestProps=streamerreplay.gametest.mode=seekfile,streamerreplay.gametest.replay=/path/to/x.sreplay`
  measures seek times on any recording.
- `scripts/crash-recovery-test.sh` (Linux) kills the game with `SIGKILL` while recording and checks the recovery.

How the mod works is described in [ARCHITECTURE.md](ARCHITECTURE.md), changes in [CHANGELOG.md](CHANGELOG.md).

## License

MIT. The code is original and contains no code from other replay mods.
