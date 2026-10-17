# Progress log (survives interruptions)

Target: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3, Loom 1.18-SNAPSHOT (1.18.4), Gradle 9.7.1, Java 25.
Build: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64; ./gradlew build --no-daemon`. The jar is copied to `dist/`.

E2E test: `DISPLAY=:99 timeout 900 ./gradlew runClientGameTest --no-daemon`. Add `-PcompatMods=a.jar,b.jar` for a compat run.
Screenshots: `build/run/clientGameTest/screenshots`, copied to `screenshots/` (and `screenshots/compat-sodium-lithium-modmenu/`).

## Status: release candidate 1.0.0
- Unit tests: 18 (format, recovery, splines, time format).
- The e2e client gametest passes on its own and with Sodium 0.9.3-beta.1, Lithium 0.26.2 and Mod Menu 21.0.0. It covers:
  - SP auto-record, marker, thumbnail, browser, settings
  - replay open, seek forward/back, click-ray follow and unfollow, Esc replay menu
  - camera path (3 keyframes) and editor overlay
  - MP4 export (H.264) to DONE, video > 10 KB
  - dedicated-server recording (TCP, compression 64) and its playback containing server-placed blocks
  - return to the title screen
- Docs: README (features, controls, install, safety/checksum, compat, limits), ARCHITECTURE.md, LICENSE (MIT).
- Publish kit: `publish/` (descriptions EN + DE short, summary, tags, changelog, gallery, 512px icon, jar, SHA256SUMS).

## Not tested / open
- Browser pre-selects the newest replay (added after review pass 5).
- Iris: the box has no GLX visual (OpenGL), so Iris aborts at startup in this environment, with or without this mod.
  The game itself ran on the Vulkan backend (llvmpipe).
- The OpenGL backend in general (same reason); all runs used Vulkan.
- Real mouse clicks and key presses: the test drives the same code paths from code.
- Windows/macOS ffmpeg discovery (only Linux ffmpeg 7.1.5 used).
- Very long replays (hours): reading is streamed, but seeking backwards rebuilds from the start, which costs time.

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
