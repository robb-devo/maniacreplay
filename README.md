# Streamer Replay

A replay mod for **Minecraft 26.3 (Fabric)** made with streamers in mind. Recording starts by itself. You can re-watch any
moment from any angle, fly cinematic camera paths and export them to MP4, all without needing a tutorial.

## Features

- **Automatic recording** in singleplayer and multiplayer. It starts when you join and is saved when you leave.
  - A small `● REC 12:34` badge shows while recording. You can turn it off for a clean stream.
  - Press **M** to add a marker ("that was a great moment"). Markers appear on the timeline.
  - Recording runs fully off-thread: packets are copied into pooled buffers and compressed and written by a background thread.
    When nothing is being recorded the cost is one volatile read per packet.
  - If the game crashes, unfinished recordings are recovered on the next start (tested by killing the game with
    `SIGKILL` in the middle of a recording, see *Testing*).
  - Pressing *Stop Recording* when nothing is recording tells you why and how to start one.
- **Replay browser** (film-strip button on the title screen):
  - Thumbnails, world/server name, date, duration, size and marker count.
  - Watch, rename, delete (with confirmation) and open the folder.
- **Replay viewer**:
  - Play/pause, speed from 0.1× to 16×, a timeline you can scrub, and jumps of ±5 s.
  - Free camera with smooth flying.
  - **Click an entity or player to follow it**. Shift goes back to the free camera.
  - **Everything works while paused**: look around with the mouse, fly, click entities to follow them. The world stays
    frozen (like a paused singleplayer game) and the replay time does not move.
  - Vanilla tutorial / onboarding toasts ("Press WASD", social interactions, "unsecure server") are not shown in replays.
- **Camera path editor** (**O**):
  - Add keyframes with **I**. Each keyframe stores position, rotation, FOV and **roll**.
  - Catmull-Rom-style (non-uniform Hermite) splines give smooth motion even when keyframes are unevenly spaced.
  - The path is drawn over the world while the editor is open, and you can preview it in real time.
  - Click a keyframe to jump to it. Delete removes it.
- **Video export** to MP4 (H.264 through `libx264`, with an automatic MPEG-4 fallback):
  - Rendered frame by frame at a fixed FPS, so output is smooth even on a slow PC.
  - **Native resolution**: 1080p, 1440p or 4K are rendered at that size by the game itself (off-screen), whatever the
    window size. The window just shows a scaled preview, and resizing it during an export is harmless.
  - **Clean frames** (on by default): name tags, the block outline, the hand and debug views (hitboxes, chunk borders)
    are left out of the video. HUD and menus are never in the video.
  - Works with **Iris shader packs** (tested with MakeUp UltraFast on OpenGL).
  - **ffmpeg is found automatically** in the usual places (PATH, WinGet, Scoop, Chocolatey, `C:\ffmpeg`, Homebrew,
    MacPorts, or an `ffmpeg` folder inside `.minecraft`).
  - A progress screen shows ETA and a cancel button.
- **Config screen** (also reachable through Mod Menu). English and German translations (German fully translated, including errors and Mod Menu texts; a unit test fails the build if any key is missing). Umlauts in world names, replay names and video file names work.

## Controls (all rebindable under *Options → Controls → Streamer Replay*)

| Key | Action |
| --- | --- |
| M | Add marker (while recording) |
| K | Play / pause the replay |
| ← / → | Back / forward 5 s |
| O | Open the camera path editor |
| I | Add a camera keyframe at the current view |
| Left click | Follow the entity under the crosshair (Shift leaves) |
| WASD + Space/Shift | Fly the free camera (scroll changes speed) |
| Esc | Replay menu (back to replay, editor, settings, exit) |
| *(unbound)* | Stop recording |

In the editor, **right-drag** looks around and **WASD** flies.

## Install

1. Install Fabric Loader **0.19.5+** for Minecraft **26.3**, and **Fabric API 0.163.0+26.3**.
2. Put `streamer-replay-mod-1.1.0.jar` into `.minecraft/mods`.
3. Optional:
   - [Mod Menu](https://modrinth.com/mod/modmenu) to open the config from the mod list.
   - **ffmpeg** for video export. Install it the usual way (`winget install ffmpeg`, `brew install ffmpeg`, your
     Linux package manager) or unzip it into `.minecraft/ffmpeg/`. It is found automatically, even when the
     launcher does not see your terminal's PATH. You can also enter the full path. The export screen tells you
     whether ffmpeg was found.
4. Java 25 is required, the same as Minecraft 26.3 itself.

Files are stored in:
- `.minecraft/replays/`: `*.sreplay`, plus a `*.paths` sidecar for camera paths.
- `.minecraft/replay_videos/`: exported videos, with an ffmpeg log next to each one.

## Compatibility

- **Injections only**: no `@Overwrite`, no `@Redirect`. A small mixin set covers the connection tap, the login hook, the
  game timer during replays, camera pose/FOV/roll, a post-render frame hook, the pause menu, the render size during
  export, the clean-frame switches (name tags, block outline, debug renderer) and toast filtering in replays.
- **Idle cost is near zero**. Every hook first checks one static field and returns immediately when no replay is open.
- **Mod Menu** is an optional integration. It is loaded only when present.
- **Sodium 0.9.3-beta.1, Lithium 0.26.2 and Mod Menu 21.0.0** were tested together with this mod. The full end-to-end
  test passes with them installed (record, watch, seek, follow, camera path, MP4 export, dedicated-server recording).
- **Iris 1.11.7 + Sodium 0.9.3-beta.1 with the MakeUp UltraFast 9.5g shader pack** pass the same end-to-end test on
  OpenGL (Mesa llvmpipe software rendering, so it is slow but real): recording, replay, seeking, following, paused
  controls, and MP4 export at window size and native 1080p with the shader visible in the frames.
  Complementary Reimagined was downloaded too but is not part of the test run.
- Report problems with an exact mod list.
- The original *ReplayMod* is marked as `breaks`, because both mods tap the same network pipeline.

## Known limitations

- **Recording starts when you join a world.** Starting in the middle of a session is not supported on purpose: a
  replay needs the world exactly as the server sent it at login (registries, chunks, entities), and rebuilding that
  from the client's partial copy would give replays that look right but break when you seek. Leave and rejoin to
  record; the game tells you this if you press *Stop Recording* while nothing is recorded.
- Replays are only compatible with the protocol version they were recorded with (they are tagged with it).
- **Seeking backwards rebuilds the world from the start**, then fast-forwards. Measured on the test machine (software
  rendering): about 0.3–0.5 s for a 15 s and for a 93 s busy replay (40 mobs). Almost all of that is the fixed
  reconnect and level-load cost; delivering packets takes about 1.3 ms per recorded second, so a one-hour replay
  should take roughly 5 s (an estimate, not measured). Periodic checkpoints were considered and rejected: the client
  world cannot be snapshotted and restored safely without re-creating every piece of client state.
- The largest export size is limited by the GPU's maximum texture size (4K works on every current GPU).

## Is this safe? How can I check?

You don't need to take anyone's word for it:

- **The source code is public.** Everything the mod does is in this repository, under the MIT license.
  It contains no code from other replay mods.
- **No internet access.** The mod makes **no network connections** of its own: no telemetry, no analytics,
  no update checks, no downloads. It only reads and writes files in `.minecraft/replays` and
  `.minecraft/replay_videos` (plus its config file).
- **The only program it starts is ffmpeg**, on your own PC, and only when you click *Export Video*. You
  install ffmpeg yourself and can choose its path in the settings.
- **Scan the jar yourself.** Go to <https://www.virustotal.com>, choose *File*, and upload
  `streamer-replay-mod-1.1.0.jar`. Dozens of virus scanners check it at once. You can also paste the checksum
  below into VirusTotal's *Search* tab to see earlier scans of exactly this file.
- **Make sure you have the original file.** Compare its SHA-256 checksum with the one published here and in
  the release notes:
  - **Windows** (PowerShell): `Get-FileHash streamer-replay-mod-1.1.0.jar -Algorithm SHA256`
  - **macOS / Linux**: `shasum -a 256 streamer-replay-mod-1.1.0.jar` or `sha256sum streamer-replay-mod-1.1.0.jar`

  ```
  SHA-256 (streamer-replay-mod-1.1.0.jar)
  2b3c5b71a673c319fb32235f0b8dee4ee8f271eb384637b2e383c38e4c1791af
  ```

  If the value differs, the file is not the official release. Delete it and download from the official
  page (Modrinth, CurseForge or GitHub Releases).

## Building

```
export JAVA_HOME=/path/to/java-25
./gradlew build                 # jar in build/libs, runs unit tests
./gradlew runClientGameTest     # full end-to-end client test (needs a display, e.g. Xvfb)
```

## Testing

What the automated tests cover (all run on Linux under Xvfb):

- `./gradlew build`: unit tests for the file format, camera paths, time formatting, ffmpeg discovery on
  Windows/macOS/Linux (simulated file systems), and a check that German has every English key.
- `./gradlew runClientGameTest`: one end-to-end client test: records a singleplayer world, renames, opens, seeks,
  follows entities, uses mouse look/flight/clicks while paused, builds a camera path, exports MP4 at window size and
  at native 1080p (checked with ffprobe and a sharpness measurement), exports with clean frames on and off and
  compares the frames, records a dedicated server, and checks that every label fits its button.
  - `-PgametestLang=de_de` runs it in German.
  - `-PcompatMods=a.jar,b.jar` adds other mods (Sodium, Lithium, Mod Menu, Iris).
  - `-PshaderPack=pack.zip` installs and enables a shader pack (with Iris). On a box without a GPU this needs
    `SDL_VIDEO_FORCE_EGL=1 LIBGL_ALWAYS_SOFTWARE=1` (Mesa llvmpipe).
  - `-PgametestProps=streamerreplay.gametest.mode=seekbench` records a 93 s replay and measures seeks.
- `scripts/crash-recovery-test.sh`: records, kills the game with `SIGKILL`, starts it again and checks the replay
  was recovered with its marker and plays.

Not tested automatically: real Windows and macOS machines, real GPUs (NVIDIA/AMD/Intel/Apple), Vulkan with Iris.

## License

MIT. All code is original and does not reuse code from ReplayMod.
