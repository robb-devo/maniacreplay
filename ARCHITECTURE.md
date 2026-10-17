# How Streamer Replay works (plain-language overview)

This document explains how the mod works without assuming you know Minecraft modding. Class names appear in
`code font` so developers can find the code. Everything lives under `dev.robb.streamerreplay`.

## The big idea

When you play, the server constantly sends your game messages ("packets"): *this chunk looks like this*,
*that pig moved here*, *it started raining*. Your game turns these messages into the world you see.

Streamer Replay **keeps a copy of every message the server sends**, together with the moment it arrived.
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
| `render` | Video export: rendering frame by frame and piping the frames to ffmpeg. Also draws the path preview in the world. |
| `library` | The replay folder: listing, renaming, deleting, crash recovery. |
| `ui` | Every screen and HUD element (browser, editor, timeline, export, settings, REC badge). |
| `input` | Keybinds. |
| `mixin` | Small hooks into Minecraft's own code (see "Compatibility" below). |
| `compat` | Optional integrations (Mod Menu). Only loaded if that mod is installed. |

## Recording

1. **Tap.** Every network connection the game opens gets a tiny pass-through listener, `RecordingTap`. It sits
   in the network pipeline *after* decryption and decompression, so it sees each message exactly as the game
   will read it. While nothing is recorded it does a single check per message and nothing else.
2. **Start.** When the server accepts your login, `RecordingManager` checks the settings (singleplayer and/or
   multiplayer), creates a `Recorder`, and attaches it to the tap. Recording is automatic.
3. **Write without stutter.** The network thread copies each message into a large reusable memory block (a
   "chunk"). `PacketStreamWriter` hands full chunks to its own background thread, which compresses them and writes
   them to a `.rec` file. Memory is reused, so there are no per-message allocations. Disk work never happens on
   the game thread.
4. **Your own player.** The server never sends you your own movements, because your game already knows them.
   `SelfPlayerRecorder` adds them as extra messages, so you are visible in the replay as well.
5. **Markers and thumbnail.** Pressing M stores a marker (time and name). After a few seconds a small
   screenshot is taken as the thumbnail. Both are stored as small "sidecar" files next to the `.rec`.
6. **Save.** When you leave, a background thread packs the `.rec`, the metadata, the markers and the thumbnail into
   one `.sreplay` file (a zip) and deletes the temporary files.
7. **Crash safety.** The writer flushes about every second in a format that stays readable up to the last flush.
   If the game crashes, `RecoveryService` finds the left-over `.rec` on the next start and turns it into a
   normal replay marked as "recovered".

## Playback

1. **Fake server.** `ReplaySession` opens the file and builds a fake connection (`ReplayChannel`). The game connects to
   it like to any server. Instead of network data, it receives the recorded messages.
2. **Filtering.** `ReplayPacketFilter` adjusts a few messages so that you watch as a spectator instead of
   controlling the recorded player. For example, the "you are this player" information is replaced, and messages
   that would move your camera or open inventories are dropped.
3. **Replay clock.** `ReplayClock` decides the current replay time and supports play/pause, speed and jumps.
   Each frame, all messages up to that time are delivered. Game ticks (animations, entity movement) are derived
   from replay time rather than wall time, so pause and slow motion affect the world but not the menus.
4. **Seeking.**
   - Forward: the session delivers the messages in between quickly.
   - Backward: the session rebuilds the world from the start and fast-forwards. The messages describe *changes*,
     so there is no other way to go back in time. An overlay shows while this runs, and your camera position and
     any open editor are restored afterwards.
5. **Camera.** `ReplayCamera` flies smoothly with WASD and the mouse.
   - Clicking an entity follows it, using `EntityPicker`, a long-range ray from the camera.
   - Shift goes back to the free camera.
   - Field of view and roll are applied through small camera hooks.

## Camera paths

- A **keyframe** stores *where the camera is* (position, rotation, FOV, roll) at a given replay time.
- `CameraPath` keeps the keyframes sorted. `HermiteSpline` draws a smooth curve through them:
  - It is Catmull-Rom style and handles keyframes that are unevenly spaced in time.
  - Rotation always takes the short way round (`AngleMath`).
  - FOV never overshoots.
- `PathPlayer` moves the camera along the curve. It is used for the live preview and for export.
- Paths are saved next to the replay as a small `.paths` file, so the replay itself is never modified.
- `PathPreviewRenderer` draws the path in the world while the editor is open: a line, points and keyframe numbers.

## Video export

1. `VideoExporter` takes over the replay clock. For every video frame it:
   - advances replay time by exactly 1/FPS
   - places the camera on the path
   - waits until the world has finished building
   - captures the image
2. **Capture.** The image is copied from the graphics card into a reusable buffer. Up to 3 frames are in flight, so the
   graphics card and the CPU work at the same time.
3. **Encode.** `FfmpegEncoder` pipes the raw frames to an external ffmpeg process on its own writer thread. It uses H.264
   (`libx264`) when available and MPEG-4 otherwise. Next to the video it writes a log file, for troubleshooting.
4. Export runs at whatever speed your PC manages, while the video plays back smoothly at the chosen FPS.
   Cancelling deletes the half-written file.

## User interface

- **Title screen.** A film-strip button opens the **browser** (`ReplayBrowserScreen`), which shows thumbnails, names,
  dates and durations. It loads all of this in the background.
- **While watching.** A slim bar shows play state, time, timeline and speed (`ReplayHud`). A short hint fades out after
  10 seconds. Esc opens the replay menu.
- **Editor** (`ReplayEditorScreen`): timeline with keyframes and markers, transport buttons, speed, FOV, roll,
  keyframe buttons, preview and export.
- **Recording.** A small REC badge (`RecordingIndicatorHud`) shows while recording. It can be turned off for a clean stream.

## Compatibility and performance principles

- **Mixins are small injections only.** Nothing replaces Minecraft code wholesale (no `@Overwrite`/`@Redirect`).
  Each hook first checks "is a replay open?" and returns immediately otherwise, so the cost is near zero while you
  play normally.
- **Optional integrations are guarded.** Mod Menu support is only loaded when Mod Menu is present.
- **Background threads do the heavy work:** writing, compressing, saving, scanning the library, loading thumbnails,
  probing ffmpeg and encoding.
- The mod declares a conflict with the original ReplayMod, because both tap the same network pipeline.

## Testing

- **Unit tests** (`./gradlew test`) cover the file format, recovery, splines and time formatting.
- **End-to-end client test** (`./gradlew runClientGameTest`) starts the real game and checks the whole flow, taking
  screenshots along the way:
  - records singleplayer and a real dedicated server
  - browses, watches, seeks and follows an entity
  - builds a camera path and exports a video
- Add `-PcompatMods=a.jar,b.jar` to run the same test with other mods loaded, for example Sodium, Iris and Lithium.
