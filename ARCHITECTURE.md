# How ManiacReplay works (plain-language overview)

This document explains how the mod works without assuming you know Minecraft modding. Class names appear in
`code font` so developers can find the code. Everything lives under `dev.robb.streamerreplay`.

## The big idea

When you play, the server constantly sends your game messages ("packets"): *this chunk looks like this*,
*that pig moved here*, *it started raining*. Your game turns these messages into the world you see.

ManiacReplay **keeps a copy of every message the server sends**, together with the moment it arrived.
Watching a replay means **feeding those same messages back into the game** at the same pace, from a fake
"server" that is really just the file. The game believes it is connected to a server and draws the world as
usual. You are only an invisible spectator with a free-flying camera.

This approach is small (only messages are stored, not video), exact (you can watch from any angle) and
compatible (the game does its normal work, so render mods such as Sodium still apply).

## Modules

| Folder | What it is responsible for |
| --- | --- |
| `format` | The file format: writing and reading packet streams, the `.sreplay` archive, metadata, markers. Pure Java with unit tests. |
| `record` | Recording: tapping the network connection, timing, writing in the background, saving. |
| `replay` | Playback: the fake connection, the replay clock, the free/follow camera, seeking. |
| `path` | Camera paths: keyframes and the smooth curve between them. Pure maths with unit tests. |
| `render` | Video export: rendering frame by frame and piping the frames to ffmpeg. Also draws the path preview. |
| `library` | The replay folder: listing, renaming, deleting, crash recovery, automatic clean-up. |
| `ui` | Every screen and HUD element (browser, editor, timeline, export, settings, REC badge). |
| `input` | Keybinds. |
| `mixin` | Small hooks into Minecraft's own code (see "Compatibility" below). |
| `compat` | Optional integrations (Mod Menu). Only loaded if that mod is installed. |

## Recording

1. **Tap.** Every network connection the game opens gets a tiny pass-through listener, `RecordingTap`. It sits
   in the network pipeline *after* decryption and decompression, so it sees each message exactly as the game
   will read it. While nothing is recorded it does a single check per message and nothing else.
2. **Start.** When the server accepts your login, `RecordingManager` checks the settings (the recording switch,
   and whether worlds and/or servers are recorded), creates a `Recorder`, and attaches it to the tap. The switch is
   in the settings and in the top left corner of the vanilla pause menu (`PauseMenuRecordingButton`); turning it
   off stops and saves the running recording.
3. **Write without stutter.** The network thread copies each message into a large reusable memory block (a
   "chunk"). `PacketStreamWriter` hands full chunks to its own background thread, which compresses them and writes
   them to a `.rec` file. Memory is reused, so there are no per-message allocations. Disk work never happens on
   the game thread. If the disk falls far behind (64 MB waiting), the writer stops taking data and the recording
   is saved with a message, instead of using more and more memory.
4. **Your own player.** The server never sends you your own movements, because your game already knows them.
   `SelfPlayerRecorder` adds them as extra messages, so you are visible in the replay as well.
5. **Markers and thumbnail.** Pressing M stores a marker (time and name). After a few seconds a small
   screenshot is taken as the thumbnail. Both are stored as small "sidecar" files next to the `.rec`.
6. **Save.** When you leave, a background thread packs the `.rec`, the metadata, the markers and the thumbnail into
   `name.sreplay.tmp`, renames it to `name.sreplay` and only then deletes the `.rec` and the sidecars. If anything
   fails, the partial archive is removed and the `.rec` stays for recovery.
7. **Crash safety.** The writer flushes about every second in a format that stays readable up to the last flush.
   If the game crashes, `RecoveryService` finds the left-over `.rec` on the next start and turns it into a
   normal replay marked as "recovered". It also removes partial archives left by a game killed while saving.
8. **Clean-up.** After each save and once at start-up, `ReplayLibrary.cleanUp` deletes replays older than the
   configured number of days. It is off unless the player switches it on (it deletes recordings). `CleanupPolicy`
   keeps every replay that has a marker or a name the player gave it, so nothing the player cared about disappears.

## Playback

1. **Fake server.** `ReplaySession` opens the file and builds a fake connection (`ReplayChannel`). The game connects to
   it like to any server. Instead of network data, it receives the recorded messages.
2. **Filtering.** `ReplayPacketFilter` adjusts a few messages so that you watch as a spectator instead of
   controlling the recorded player. For example, the "you are this player" information is replaced, and messages
   that would move your camera or open inventories are dropped. Chat is dropped too unless the player turned it on
   (a replay shown on stream must not show private messages); the action bar stays. Server resource packs are not
   loaded, since the mod never downloads anything on its own.
3. **Replay clock.** `ReplayClock` decides the current replay time and supports play/pause, speed and jumps.
   Each frame, all messages up to that time are delivered. Game ticks (animations, entity movement) are derived
   from replay time rather than wall time, so pause and slow motion affect the world but not the menus.
   - **Paused or very slow:** the game still runs one "frozen" tick every 50 ms, marked as a paused tick, exactly
     like a paused singleplayer game. Menus, key bindings, mouse look, flying and clicks keep working, but entities,
     block animations and textures stand still. Two things vanilla only updates in unpaused ticks are fed by hand
     in a frozen tick: the sky/fog colour probe (or the sky turns black after a world rebuild) and the level-load
     tracker (or the loading screen after a backward seek would never close).
   - Vanilla tutorial and onboarding toasts are filtered out while a replay is open (`ToastManagerMixin`).
4. **Seeking.**
   - Forward: the session delivers the messages in between quickly.
   - Backward: the session rebuilds the world from the start and fast-forwards. The messages describe *changes*,
     so there is no other way to go back in time. `RewindOverlay` first grabs the current picture and keeps it on
     screen (over the loading screens) with a small "Rewinding" badge until the world at the new time is complete,
     so a rewind looks like a video player buffering. Your camera position and any open editor are restored
     afterwards. Presses of ±5 s within 300 ms are bundled into one rebuild (`ReplaySession.nudge`).
   - While the session catches up after a jump, the clock stands still, so the replay arrives exactly at the
     requested moment even when catching up takes seconds. Each rebuild is logged with its duration.
   - Measured on a 46 minute singleplayer recording (Windows 11, real GPU): delivery costs about 1.6 ms per recorded
     second; jumping back near the end takes 3.9 s, to five minutes in 1.2 s. Short replays rebuild in 0.2–0.3 s,
     almost all of it the fixed reconnect cost. While the picture hides the world, each frame spends 80 ms instead
     of 25 ms delivering; that gained about 6 % here and more when rendering is expensive.
   - Checkpoints (periodic snapshots to jump back to) were rejected: the client's world (chunks with light, entities
     with their data, block entities, scoreboards, boss bars, maps, the player list, registries) cannot be copied and
     restored safely, and a half-correct snapshot is worse than a slower correct rebuild.
5. **Camera.** `ReplayCamera` flies smoothly with WASD and the mouse.
   - Clicking an entity follows it, using `EntityPicker`, a long-range ray from the camera.
   - Shift goes back to the free camera.
   - When the world switches dimension during normal playback, the camera follows the recorded player, since the
     old coordinates mean nothing in the new dimension. Switches replayed while catching up after a jump do not
     move the camera.
   - Field of view and roll are applied through small camera hooks.

## Camera paths

- A **keyframe** stores *where the camera is* (position, rotation, FOV, roll) at a given replay time.
- `CameraPath` keeps the keyframes sorted. `HermiteSpline` draws a smooth curve through them:
  - Tangents are time-weighted, so unevenly spaced keyframes give smooth motion.
  - Every channel is shape-preserving (Fritsch-Carlson): the camera never swings past a keyframe. Without that, a
    real path with keys 2 s and then minutes apart overshot by about 75 blocks.
  - Rotation always takes the short way round (`AngleMath`).
- `PathPlayer` moves the camera along the curve. It is used for the live preview and for export. During a preview,
  scrubbing the timeline or pressing ← / → moves the preview (`CameraPath.pathTimeAt` maps a replay time back to
  the path).
- Paths are saved (in the background) next to the replay as a small `.paths` file, so the large replay file is
  never rewritten. Replays from 1.1.x carry their path inside the archive; it is read from there if no `.paths`
  file exists. The editor's ✔ button overwrites the selected keyframe with the current camera.
- `PathPreviewRenderer` draws the path over the world while the editor is open: a dotted curve, keyframe markers
  and numbers. It projects the 3D path points through the current camera onto the screen, like a tiny
  camera of its own. That works no matter which renderer or shader pack draws the world.

## Video export

1. `VideoExporter` takes over the replay clock. For every video frame it:
   - advances replay time by exactly 1/FPS
   - places the camera on the path
   - waits until the world has finished building
   - captures the image
   - skips any render that is not at the export size (for example a screenshot tool rendering at window size)
2. **Native resolution.** `RenderResolution` tells the game its framebuffer has the export size (for example 1920×1080,
   or 1080×1920 for vertical video), so the world, post effects, shader packs and GUI all render at that size into the
   normal main target. Only the very last step changes: the finished image is scaled to the real window for the
   on-screen preview, letterboxed with black bars when the shapes differ. A window resize only re-applies the virtual
   size. Sizes above the GPU's texture limit are clamped and scaled by ffmpeg.
3. **Clean frames.** `CleanFrames` is on while an export with "hide name tags & overlays" runs. Small hooks then
   drop name tags (`EntityRendererMixin`), the block outline (`LevelExtractorMixin`) and debug gizmos such as hitboxes
   (`DebugRendererMixin`), and mark the HUD hidden (which also hides the hand). The 2D HUD and menus are never in the
   video anyway, because the image is captured right after the world and its post effects, before the GUI.
4. **Capture.** The image is copied from the graphics card into a reusable buffer. Up to 3 frames are in flight, so the
   graphics card and the CPU work at the same time.
5. **Encode.** `FfmpegEncoder` pipes the raw frames to an external ffmpeg process on its own writer thread. It uses H.264
   (`libx264`) when available and MPEG-4 otherwise. Next to the video it writes a log file, for troubleshooting.
   Without ffmpeg, `PngSequenceWriter` saves numbered PNG images instead (both implement `FrameSink`). Without a
   camera path, `ExportScreen` films a fixed shot from the current camera for the chosen length.
   `FfmpegLocator` finds ffmpeg when the setting is just `ffmpeg`: PATH first, then a copy in the game folder, then
   where WinGet, Scoop, Chocolatey, Homebrew and MacPorts put it. That matters because launchers often do not see the
   PATH of the user's terminal. It only checks which files exist.
   On Windows, `FfmpegInstaller` can install ffmpeg when the player clicks *Install ffmpeg*: it downloads one pinned
   file (Gyan's ffmpeg 9.0.2 essentials build from GitHub), checks its SHA-256 against the value in the source and
   unpacks only `ffmpeg.exe` to `.minecraft/ffmpeg`. This is the mod's only network access.
6. Export runs at whatever speed your PC manages, while the video plays back smoothly at the chosen FPS.
   Cancelling deletes the half-written file.

## User interface

- **Title screen.** A film-strip button opens the **browser** (`ReplayBrowserScreen`), which shows thumbnails, names,
  dates and durations, and every marker as a clickable ⚑ chip that starts the replay 10 s before it. It loads all
  of this in the background.
- **While watching.** A slim bar shows play state, time, timeline and speed (`ReplayHud`). A short hint fades out after
  10 seconds. Esc opens the replay menu.
- **Editor** (`ReplayEditorScreen`): timeline with keyframes and markers, transport buttons, speed, FOV, roll,
  keyframe buttons, preview and export.
- **Recording.** A small REC badge (`RecordingIndicatorHud`) shows while recording. It can be turned off for a clean stream.
  The recording switch sits in the top left corner of the pause menu.

## Compatibility and performance principles

- **Mixins are small injections only.** Nothing replaces Minecraft code wholesale (no `@Overwrite`/`@Redirect`).
  Each hook first checks "is a replay open?" and returns immediately otherwise, so the cost is near zero while you
  play normally.
- **Optional integrations are guarded.** Mod Menu support is only loaded when Mod Menu is present.
- **Background threads do the heavy work:** writing, compressing, saving, scanning the library, loading thumbnails,
  probing ffmpeg and encoding.
- The mod declares a conflict with the original ReplayMod, because both tap the same network pipeline.

## Testing

- **Unit tests** (`./gradlew test`) cover the file format, recovery, splines, time formatting, ffmpeg discovery on
  Windows/macOS/Linux (with a simulated disk) and that German has every English key.
- **End-to-end client test** (`./gradlew runClientGameTest`) starts the real game and checks the whole flow, taking
  screenshots along the way:
  - records singleplayer and a real dedicated server
  - browses, watches, seeks and follows an entity
  - builds a camera path and exports a video
  - uses mouse look, flying and clicks while paused
  - exports at native 1080p (checked with ffprobe and a sharpness measure) and with clean frames on/off (frames compared)
  - exports a 9:16 video (1080×1920, checked with ffprobe)
  - follows the recorded player into the Nether on a dedicated server
  - clicks the editor's Exit button and drags the camera with real mouse input
- `-PgametestProps=streamerreplay.gametest.mode=seekfile,streamerreplay.gametest.replay=/path/x.sreplay` measures
  seek times on any recording.
- Add `-PcompatMods=a.jar,b.jar` to run the same test with other mods loaded, for example Sodium, Iris and Lithium,
  and `-PshaderPack=pack.zip` to enable an Iris shader pack.
- `scripts/crash-recovery-test.sh` kills the game with SIGKILL mid-recording, restarts it and checks the recovered replay.
- Recording a session that is already running is deliberately not supported (see README, *Known limitations*); the
  game explains how to record instead.
