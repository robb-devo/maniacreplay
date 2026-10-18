# ManiacReplay

**Records everything on its own. Watch it again from any angle. Fly camera paths. Export MP4. No setup.**

ManiacReplay is a replay mod for **Minecraft 26.3 (Fabric)**, made for streamers and creators who want the clip
*after* it happened, without planning for it.

## ✨ What it does

- **Records automatically** in singleplayer and on servers, from the moment you join until you leave.
  - Press **M** at a great moment to set a marker.
  - Just want to play? Switch recording off in the **pause menu** (top left). It stays off until you switch it back on.
  - A small `● REC` badge shows while recording (can be hidden for a clean stream).
- **Replay browser** on the title screen (film-strip button): thumbnails, world name, date and length.
  Click a **⚑ marker** to start 10 seconds before it.
- **Watch like a video**: play/pause (**↓**), ±5 s (**← / →**), 0.1× to 16× speed, a timeline you can drag.
  Rewinding keeps the last picture on screen while the world is rebuilt, like a video player buffering.
- **Free camera**: fly anywhere. **Click any player or mob to follow them** (Shift returns to the free camera).
  Works while paused too.
- **Camera paths** (**J** opens the editor, **U** adds a keyframe): position, direction, FOV and camera roll,
  smooth curves, drawn into the world and previewed live.
- **Video export to MP4**:
  - Rendered frame by frame, so the video is perfectly smooth even when your PC is not.
  - **Real 1080p, 1440p or 4K**, whatever your window size, and **9:16 (1080 × 1920) for Shorts and TikTok**.
  - Name tags, block outline and debug overlays are left out by default. Iris shader packs show up in the video
    (tested with Iris 1.11 and MakeUp UltraFast).
  - No camera path needed: without one, it films from your current view.
- **ffmpeg in one click** on Windows: the export screen offers to install it (one checked download). macOS and
  Linux: `brew install ffmpeg` or your package manager. Without ffmpeg, export saves PNG images.
- **Optional clean-up**: delete replays after 7 to 90 days, except those with markers or a name you gave them
  (off by default).
- **Crash-safe**: if the game crashes while recording, the replay is recovered on the next start.
- English and German, settings also via Mod Menu.

## ⚡ Stays out of your way

- Recording copies network data into reusable buffers and compresses it on a background thread: no stutter.
- With nothing recording or replaying, the mod's hooks cost practically nothing.
- Small, targeted mixins only. Version 1.1 was tested together with Sodium, Lithium, Mod Menu and Iris.

## 🔒 Privacy

- No telemetry, no update checks. The mod goes online **only** when you click *Install ffmpeg*.
- Server addresses are not stored in replays unless you turn it on.
- Chat (including private messages) is hidden while watching replays unless you turn it on.
- Replays and videos stay on your PC: `.minecraft/replays` and `.minecraft/replay_videos`.

## 🎮 Controls (all rebindable)

| Key | Action |
| --- | --- |
| M | Add a marker while recording |
| ↓ | Play / pause |
| ← / → | Back / forward 5 seconds |
| J | Camera path editor |
| U | Add camera keyframe |
| Left click | Follow the entity under the crosshair |
| Esc | Replay menu |
| *(unbound)* | Recording on / off |

## 📋 Requirements

- Minecraft **26.3**, Fabric Loader **0.19.5+**, **Fabric API 0.163.0+**, Java 25.
- Client-side only. Works on any server; servers don't need it.

## ⚠ Good to know

- Recording starts when you join. To record a session that is already running, rejoin.
- Rewinding rebuilds the world from the start of the replay: well under a second for short replays, a few seconds
  for replays of hours.
- Replays play in the Minecraft version they were recorded in. Videos have no sound.
- Server resource packs are not loaded in replays.
- Not compatible with the original ReplayMod (both tap the same network pipeline).
- Respect server rules: some servers do not allow recording.

## License

MIT, open source: https://github.com/robb-devo/maniacreplay
