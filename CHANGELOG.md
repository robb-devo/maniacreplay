# Changelog

All notable changes. Versions follow [Semantic Versioning](https://semver.org/); every release targets
Minecraft 26.3 with Fabric.

## 1.2.3

### Fixed
- The German settings screen still showed the old name in its title ("Streamer-Replay-Einstellungen"). It now reads
  "ManiacReplay-Einstellungen".

### Tests
- The translation test checks that every German text keeps the mod name wherever the English one has it.

**SHA-256** of `maniacreplay-1.2.3.jar`:
`95722b1738e1df5874a64245b9261d12650a3d2dfe417c036f4efdc248749b7c`

## 1.2.2

### Fixed
- The window preview during a vertical (1080 × 1920) export, or any export whose shape differs from the window,
  showed the picture squeezed into a thin strip on OpenGL. The exported video itself was always correct. Minecraft's
  OpenGL backend treats the size of a texture copy as end coordinates, so the preview now scales the picture straight
  into its centred rectangle there, and keeps the copy on Vulkan, where it is exact.

### Tests
- The end-to-end test reads the picture the window shows during an export and checks its position to the pixel,
  for a vertical export (bars left and right) and a 16:9 export in a narrower window (bars above and below), and
  checks that the vertical video is filled edge to edge.
- `-PgraphicsApi=vulkan` runs the end-to-end test on Vulkan; it passes on OpenGL and Vulkan (NVIDIA).

**SHA-256** of `maniacreplay-1.2.2.jar`:
`0e8a3df78d7255f954391df3502d24bb09f636af78723b5e61af04f0bdcb246e`

## 1.2.1

### Fixed
- Editor: *Exit Replay* did nothing, and looking around worked with the left instead of the right mouse button.
  Minecraft 26.3 numbers mouse buttons differently (left is 1), so every left click above the editor panel was
  taken for "look around".
- Camera paths no longer swing past their keyframes when the keyframes are unevenly spaced in time. A real path
  with keys 2 s and then minutes apart overshot by about 75 blocks; every channel now uses shape-preserving
  interpolation.
- Jumping back while the replay plays lands where you asked. The clock kept running while the world was rebuilt,
  so near the end of a 46 min replay ← moved back about 1 s instead of 5 s.
- A failed save no longer leaves a half-written `.sreplay.tmp` next to the recording. Leftovers from a game that
  was killed while saving are removed on the next start.

### Changed
- Automatic clean-up is off by default, because it deletes recordings; switch it on under Settings → Auto-delete.
  A config written by 1.2.0 with the then-default of 30 days is reset to Never once.
- During a camera path preview, scrubbing the timeline or pressing ← / → moves the preview instead of stopping it.
  The FOV and roll sliders are disabled while a path plays.
- The rewind picture stays until the world at the new time is complete instead of giving up after 6 s; Cancel
  appears when a rewind takes longer than 3 s. Every rewind is logged with its duration.
- When the recorded player changes dimension during playback, the camera follows them instead of staying at the
  old coordinates in the new dimension.
- No "Recording" toast when joining a world; the REC badge already shows it.
- Export without ffmpeg keeps its frame buffers under 256 MB, so 4K fits a default heap.

### Measured
- Rewinding costs about 1.6 ms per recorded second: 3.9 s for a jump near the end of a 46 min singleplayer
  recording (42 MB per hour), Windows 11 with a real GPU.

**SHA-256** of `maniacreplay-1.2.1.jar`:
`7e74161f0dab42d2f98a917b02458c762b5de3bf807a59ad9126c163b83a1f24`

## 1.2.0

### Added
- Recording switch in the top left corner of the pause menu, in the settings ("Record: Everywhere / Worlds only /
  Servers only / Off") and on an optional key. Off stops and saves the running recording.
- *Install ffmpeg* on Windows: downloads ffmpeg 9.0.2 (Gyan's essentials build, the one `winget install ffmpeg`
  installs), checks its SHA-256 against the value in the mod and unpacks `ffmpeg.exe` to `.minecraft/ffmpeg`.
  Nothing is downloaded unless you click it.
- Markers in the replay browser; clicking one starts the replay 10 s before it. Clicking a marker on the timeline
  jumps to it, Ctrl + ← / → jumps between markers, and passing one shows its name above the timeline.
- 1080 × 1920 export preset for Shorts, TikTok and Reels, previewed upright in the window.
- ✔ in the editor updates the selected keyframe with the current camera, ✘ removes it.
- Optional automatic clean-up of old replays; replays with markers or a custom name are always kept.
- The settings show how much space the replays use.

### Changed
- Rewinding keeps the last picture on screen with a progress badge instead of showing loading screens. Several
  ← presses in a row rebuild the world once.
- Chat is hidden in replays unless *Chat in Replays* is switched on.
- Setting a marker lights up the REC badge instead of showing a toast (the toast stays when the badge is hidden).
- Panels, bars, the timeline, the REC badge and the in-world camera path were redrawn for clarity, in vanilla style.
- Export defaults for new installs: 1080p and *Balanced* quality.
- Export without ffmpeg writes PNG frames on several threads, about 6× faster.

### Fixed
- FOV and pitch could overshoot at a peak between unevenly spaced keyframes.
- Leaving a replay no longer rewrites the whole replay file; the camera path lives in a small `.paths` file next
  to it (paths stored inside 1.1.x replays are still read).
- A disk that cannot keep up stops and saves the recording with a message instead of using more and more memory.
- Windows device names (CON, NUL, …) are rejected when renaming; the controls hint wraps on small screens.

**SHA-256** of `maniacreplay-1.2.0.jar`:
`75650830ccb4ab6f650f8b38f0429fc164510079877a67ead2dd5e0026578bfc`

## 1.1.2

- **New name: ManiacReplay** (formerly Streamer Replay), with a new icon. Mod id, settings, recordings and key bindings are unchanged, so updating keeps everything. Jar: `maniacreplay-1.1.2.jar`.

## 1.1.1

- **New default keys that clash with nothing**: play/pause **↓**, camera editor **J**, keyframe **U** (marker stays **M**,
  seek ← / →). The old K/O/I clashed with vanilla (O = Friends) and Iris (K, I). Every default is checked against all
  vanilla 26.3 bindings (including F3 combos) and Iris/Sodium/Mod Menu, and an automated test now fails on any
  conflict. If you already played 1.1.0, your saved keys stay until you reset them under *Controls*.
- **Export is never greyed out any more**: buttons stay clickable and say why when something is missing.
- **Export without a camera path**: with no keyframes, Export films from your current camera for 10 s, 30 s, 60 s
  or to the end.
- **Without ffmpeg, export saves PNG images** (a "… (PNG frames)" folder) instead of failing. A help screen (opens by
  itself the first time) explains `winget install ffmpeg` or unzipping into `.minecraft/ffmpeg`, with a button to open
  the download page (only when you click it) and one to open the ffmpeg folder. Unzipped builds in subfolders are found.
- **Real ffmpeg error messages**: when ffmpeg stops, the game and the log show its exit code and last error line
  (also for the Windows "pipe is being closed" case). The output folder is created reliably, and paths with spaces or
  umlauts are tested.

## 1.1.0

- **Paused replays are fully interactive**: mouse look, flying and click-to-follow work while paused. The world stays
  frozen and the replay time does not move.
- **Native-resolution export**: 1080p, 1440p and 4K are rendered by the game at that size, off-screen, whatever the
  window size. Resizing the window during an export no longer breaks it.
- **Clean frames** (new export option, on by default): hides name tags, the block outline, the hand and debug views
  (hitboxes, chunk borders) in the video.
- **Iris shader packs are tested**: Iris 1.11.7 + Sodium with MakeUp UltraFast pass the full test on OpenGL, export included.
- **ffmpeg is found automatically** in PATH, WinGet, Scoop, Chocolatey, `C:\ffmpeg`, Homebrew, MacPorts or `.minecraft/ffmpeg`,
  even when the launcher does not see your PATH.
- Vanilla tutorial and onboarding toasts are no longer shown during replays.
- *Stop Recording* without a running recording now explains how to start one (recordings start when you join).
- Fixes:
  - A backward seek while paused no longer gets stuck on the loading screen.
  - The sky no longer turns black after a world rebuild while paused or exporting.
  - The "recordings restored" message no longer shows a raw text key when it appears during start-up.
- Tests: a real crash test (the game is killed with SIGKILL while recording, then the replay is recovered and played),
  unit tests for ffmpeg discovery on Windows/macOS/Linux, and a seek benchmark.

**SHA-256** of `streamer-replay-mod-1.1.0.jar`:
`2b3c5b71a673c319fb32235f0b8dee4ee8f271eb384637b2e383c38e4c1791af`

## 1.0.0

- Automatic recording in singleplayer and multiplayer, with a REC indicator, markers (M) and crash recovery.
- Replay browser: thumbnails, metadata, rename, delete, open folder. The newest replay is pre-selected.
- Replay viewer:
  - Play/pause, 0.1×–16× speed, a scrubbable timeline and ±5 s jumps.
  - Free camera, and click-to-follow for any entity.
- Camera path editor: keyframes with position, rotation, FOV and roll; smooth splines; in-world path preview.
- MP4 export through ffmpeg (H.264, with an MPEG-4 fallback), with a progress screen, ETA and cancel.
- Settings screen and Mod Menu integration. English and German. German is complete, error messages included, and checked on screen for text overflow.

**SHA-256** of `streamer-replay-mod-1.0.0.jar`:
`d98e4ee64e3fbfa0382ced2bf99fc3a985cef4300333ba86b4080d95a407301f`
