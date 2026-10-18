# Progress log (survives interruptions)

Target: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3, Loom 1.18-SNAPSHOT (1.18.4), Gradle 9.7.1, Java 25.
Build: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64; ./gradlew build --no-daemon`. The jar is copied to `dist/`.

E2E test: `DISPLAY=:99 timeout 900 ./gradlew runClientGameTest --no-daemon`. Add `-PcompatMods=a.jar,b.jar` for a compat run.
Screenshots: `build/run/clientGameTest/screenshots`, copied to `screenshots/`, `screenshots/de_de/`, `screenshots/compat-*/`, `screenshots/crash-recovery/`.

## Status: 1.1.0 (perfection pass done)
- Release jar: `dist/maniacreplay-1.1.2.jar`, SHA-256 `84553f3a1e55ddaaf39a98e7f369b7411b032a36c9dd35f0c775270cd4981d7c` (rebuild is byte-identical).
- Unit tests: 31 (format, recovery, splines, time format, translations, ffmpeg discovery for Windows/macOS/Linux).
- E2E client gametest passes in four configurations (runs of 2026-10-17, 23:13–23:20 CEST):
  vanilla EN, vanilla DE (`-PgametestLang=de_de`, label fit ok on every screen), Sodium 0.9.3-beta.1 + Lithium 0.26.2 +
  Mod Menu 21.0.0, and Iris 1.11.7 + Sodium + MakeUp UltraFast 9.5g on OpenGL 4.6 (Mesa llvmpipe, `SDL_VIDEO_FORCE_EGL=1`).
  On top of the 1.0.0 coverage it checks:
  - paused replay: mouse look (SDL relative motion event), W flight, real left click follows the pig, Shift unfollows,
    replay time unchanged, backward seek while paused leaves the loading screen and stays paused
  - onboarding toasts suppressed in replays
  - "Stop Recording" without recording shows the rejoin hint
  - native 1080p export from an 854×480 window, window resized to 700×520 mid-export, harness screenshot (extra render
    at window size) mid-export: ffprobe `h264,1920,1080`; sharpness (Laplacian variance) native 123.8 vs same frame
    through 480p 16.6 (ratio 7.5; with Iris 3.4); mean luma > 40 and sky colour known after the world rebuild
  - clean frames: hitboxes + name tag on vs. off, ~7500 px differ (5562 with Iris); frames in `screenshots/export-evidence/`
- `scripts/crash-recovery-test.sh`: SIGKILL of the game JVM mid-recording, restart, recovered `.sreplay` with world
  name and marker, sidecars cleaned up, replay plays (13 s). Passes.
- Seek benchmark (`-PgametestProps=streamerreplay.gametest.mode=seekbench`, 93 s replay, 40 mobs, 343 KB):
  98%→90% 511 ms, 98%→50% 441 ms, 98%→10% 436 ms, 10%→98% 136 ms (25 ms budget); a 100 ms budget was not faster.

## Not tested / open
- Real Windows/macOS machines (ffmpeg discovery is unit-tested with simulated disks only).
- Real GPUs; all runs used software rendering (Vulkan llvmpipe, or OpenGL llvmpipe for Iris).
- Iris on Vulkan, other shader packs than MakeUp UltraFast (Complementary is downloaded but not run).
- 1440p/4K exports (same code path as 1080p, only 1080p was run).
- Replays of hours: seek time extrapolated (~5 s/hour), not measured.
- The compat DE screenshot folder (`screenshots/compat-sodium-lithium-modmenu-de_de/`) is from the 1.0.0 run.

## Review log
1. **After first compile and e2e run.**
   - Replay load deadlock: the clock was held at 0 ms while the first chunk was at 556 ms.
   - Screens were lost on a backward seek.
   - The camera started inside the player's head.
   - The first export frame was foggy and empty. Fixed with a stricter settle.
   - Fixed a gametest hang caused by a paused replay running no ticks.
2. **UX and visual pass.**
   - The export result buttons did not appear (they are now updated on render).
   - `.sreplay` showed in toasts and the menu title.
   - Fixed the "1 markers" wording.
   - Added click-to-follow; leaving follow now places the camera behind the target instead of inside it.
   - Removed per-frame string building in the pause screen.
3. **Robustness and edge cases.**
   - **Critical:** in a paused replay no game ticks run, so K, ←/→, O, I and click-to-follow did nothing while
     paused. Replay input is now handled per frame, and screens get 20 Hz UI ticks while the replay is frozen.
   - The world loading screen keeps real-time ticks, avoiding a possible deadlock.
   - Replays from another protocol version are refused with a clear message.
   - Marker sidecar writes moved off the render thread.
   - Verified handling of window resizes during export, disconnects (the tap's channelInactive saves the recording)
     and crashes (`.rec` and sidecars are recovered).
4. **Compatibility.**
   - Added a `-PcompatMods` test option; the e2e run passes with Sodium, Lithium and Mod Menu.
   - Vanilla gizmos never appeared for the path preview. It was replaced by a renderer-independent screen-space
     overlay, which is also safer with Iris shader packs.
   - Iris could not be tested (see above).
5. **Final audit.**
   - No network code, telemetry or secrets in the sources; the only external process is user-configured ffmpeg.
   - The license is clean (MIT, no third-party code).
   - Fully-qualified-name clean-ups.
   - Docs checked against the code (speed range 0.1–16×, spline type, folder names).
   - UX: the browser now pre-selects the newest replay, so Watch works with one click.
   - Gallery screenshots no longer have toasts covering the screen titles.

6. **German polish pass.**
   - Every visible string is translated, including errors, toasts and Mod Menu texts. Error messages come from translation keys, not hard-coded English (LocalizedIOException).
   - Dates use a locale format (17.10.2026, 21:58), with decimal commas (0,5x) and German quotes.
   - Overflow found and fixed: settings "Serveradresse speichern" (became "Server-IP speichern"). In the editor, "Replay beenden" moved to the top right and buttons now size to their labels. On the export screen, resolution got its own full-width row.
   - The editor hint moves up one line if it would collide with the path info.

7. **1.1.0 perfection pass** (each item reviewed after implementing it).
   - Paused interaction: replaced the 20 Hz "UI ticks" with real frozen game ticks (paused-tick semantics). Found and
     fixed: the left click was eaten by vanilla's tick before our handler (now handled in the pre-attack callback).
   - Clean frames: the first version had no effect because the new mixins were not registered; caught by the
     frame-comparison test.
   - Native resolution: found a black sky after the world rebuild at the start of an export (the sky/fog probe is
     only ticked in unpaused ticks) and fixed it; found that a render at another size (screenshot) failed the
     export and made it skip such renders instead.
   - Backward seek: found that a paused replay stayed on the loading screen forever after seeking back (the
     level-load tracker is only ticked by the game mode in unpaused ticks). Fixed, with a regression check.
     Measured seek costs; a larger delivery budget did not help and was removed; checkpoints rejected (see ARCHITECTURE).
   - Recording mid-session: rejected as not robust; a clear hint instead.
   - Crash test: found that the "recordings restored" toast showed a raw key when created during start-up resource
     loading; toasts are now queued until loading has finished.
   - ffmpeg discovery: pure, unit-tested `FfmpegLocator`; custom paths are respected as typed (quotes stripped, `.exe` added).
   - OpenGL: Xvfb GLX has no sRGB-capable visual, which the game requests; EGL works (`SDL_VIDEO_FORCE_EGL=1`).

## Publishing
- GitHub: https://github.com/robb-devo/maniacreplay (private), pushed after every step.
- Modrinth/CurseForge: the user uploads `publish/maniacreplay-1.1.2.jar` with the texts in `publish/`.

## 1.1.1

- Conflict-free default keys (↓/J/U/M, gametest check), never-disabled export, no-path fixed-length export, PNG sequence fallback without ffmpeg, FfmpegHelpScreen, real ffmpeg exit reasons, unzipped-ffmpeg discovery. Verified: 32 unit tests and the full EN gametest (incl. 301-frame PNG export into an umlaut path).
