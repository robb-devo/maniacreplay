# Changelog

## 1.1.2 (Minecraft 26.3, Fabric)

- **New name: ManiacReplay** (formerly Streamer Replay), with a new icon. Mod id, settings, recordings and key bindings are unchanged, so updating keeps everything. Jar: `maniacreplay-1.1.2.jar`.

## 1.1.1 (Minecraft 26.3, Fabric)

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

## 1.1.0 (Minecraft 26.3, Fabric)

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

## 1.0.0 (first release, Minecraft 26.3, Fabric)

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
