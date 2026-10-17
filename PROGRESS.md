# Progress log (survives interruptions)

Target: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3, Loom 1.18-SNAPSHOT, Gradle 9.7.1, Java 25.
Build: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64; ./gradlew build --no-daemon`
E2E test: `DISPLAY=:99 timeout 900 ./gradlew runClientGameTest --no-daemon`. Screenshots go to `build/run/clientGameTest/screenshots`.

## Done (verified)
- Core: replay format, async writer/reader, crash recovery, camera path spline. 18 unit tests.
- Client compiles and runs. The e2e client gametest covers:
  - SP auto-record, marker, thumbnail, browser, settings
  - open replay, seek forward/back
  - follow entity by click-ray, Esc replay menu
  - 3-keyframe path + editor, ffmpeg export (H.264) to DONE
  - **MP on a dedicated server** (TCP, compression threshold 64): records, the replay opens and contains the server-placed blocks
- Fixed through testing:
  - load stall (clock held at 0)
  - lost screens on backward seek
  - camera inside the head
  - foggy first export frame
  - export result buttons not appearing
  - `.sreplay` extension in the toast and menu
  - "1 markers"
- README, LICENSE (MIT).

## In progress
- Re-running the e2e test after making the pick test robust (camera above the pig).

## Next
- Self-review passes (code / perf / compat / UX) and fixes.
- Copy screenshots to ./screenshots, dist jar, final build.
- GitHub repo: needs explicit user approval before `gh repo create robb-devo/streamer-replay-mod --private --source=. --push`.

## Not tested
- Sodium/Iris/Lithium together with this mod (not available offline on the box).
- Real mouse-click paths in the UI: the test drives screens through code, not clicks.

## Review log
(see below, appended per pass)
