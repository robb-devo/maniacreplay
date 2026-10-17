# Streamer Replay

A replay mod for **Minecraft 26.3 (Fabric)** made with streamers in mind. Recording starts by itself. You can re-watch any
moment from any angle, fly cinematic camera paths and export them to MP4, all without needing a tutorial.

## Features

- **Automatic recording** in singleplayer and multiplayer. It starts when you join and is saved when you leave.
  - A small `● REC 12:34` badge shows while recording. You can turn it off for a clean stream.
  - Press **M** to add a marker ("that was a great moment"). Markers appear on the timeline.
  - Recording runs fully off-thread: packets are copied into pooled buffers and compressed and written by a background thread.
    When nothing is being recorded the cost is one volatile read per packet.
  - If the game crashes, unfinished recordings are recovered on the next start.
- **Replay browser** (film-strip button on the title screen):
  - Thumbnails, world/server name, date, duration, size and marker count.
  - Watch, rename, delete (with confirmation) and open the folder.
- **Replay viewer**:
  - Play/pause, speed from 0.1× to 16×, a timeline you can scrub, and jumps of ±5 s.
  - Free camera with smooth flying.
  - **Click an entity or player to follow it**. Shift goes back to the free camera.
- **Camera path editor** (**O**):
  - Add keyframes with **I**. Each keyframe stores position, rotation, FOV and **roll**.
  - Catmull-Rom-style (non-uniform Hermite) splines give smooth motion even when keyframes are unevenly spaced.
  - The path is drawn in the world, and you can preview it in real time.
  - Click a keyframe to jump to it. Delete removes it.
- **Video export** to MP4 (H.264 through `libx264`, with an automatic MPEG-4 fallback):
  - Rendered frame by frame at a fixed FPS, so output is smooth even on a slow PC.
  - A progress screen shows ETA and a cancel button.
- **Config screen** (also reachable through Mod Menu). English and German translations.

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
2. Put `streamer-replay-mod-1.0.0.jar` into `.minecraft/mods`.
3. Optional:
   - [Mod Menu](https://modrinth.com/mod/modmenu) to open the config from the mod list.
   - **ffmpeg** for video export: either on your `PATH`, or set its full path in the config screen. The export screen
     tells you whether ffmpeg was found.
4. Java 25 is required, the same as Minecraft 26.3 itself.

Files are stored in:
- `.minecraft/replays/`: `*.sreplay`, plus a `*.paths` sidecar for camera paths.
- `.minecraft/replay_videos/`: exported videos, with an ffmpeg log next to each one.

## Compatibility

- **Injections only**: no `@Overwrite`, no `@Redirect`. A small mixin set covers the connection tap, the login hook, the
  game timer during replays, camera pose/FOV/roll, a post-render frame hook and the pause menu.
- **Idle cost is near zero**. Every hook first checks one static field and returns immediately when no replay is open.
- **Mod Menu** is an optional integration. It is loaded only when present.
- **Sodium, Iris and Lithium** have **not been tested yet**. The mod avoids the rendering paths they replace:
  - The camera is driven through `Camera` injections.
  - Frames are read back from the main render target after post effects.

  Report problems with an exact mod list.
- The original *ReplayMod* is marked as `breaks`, because both mods tap the same network pipeline.

## Known limitations

- A replay only starts at join time. You can't start recording in the middle of a session yet, except by rejoining.
- Videos are rendered at window resolution and then scaled by ffmpeg. For crisp 1080p, make the window at least 1080p.
- Replays are only compatible with the protocol version they were recorded with (they are tagged with it).
- Seeking backwards rebuilds the world from the start, then fast-forwards. On long replays this takes a moment, and
  an overlay is shown while it runs.

## Building

```
export JAVA_HOME=/path/to/java-25
./gradlew build                 # jar in build/libs, runs unit tests
./gradlew runClientGameTest     # full end-to-end client test (needs a display, e.g. Xvfb)
```

## License

MIT. All code is original and does not reuse code from ReplayMod.
