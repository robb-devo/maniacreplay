# Progress log (survives interruptions)

Target: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3, Loom 1.18-SNAPSHOT, Gradle 9.7.1, Java 25.
Build: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64; ./gradlew build --no-daemon`

## Done
- Pure core (src/main): replay format, async packet writer/reader, archive + crash recovery, camera path spline. 18 unit tests pass.
- Client: recording (tap after splitter), replay session (EmbeddedChannel fake connection), camera, path player,
  video exporter (ffmpeg), UI (browser, editor, timeline, export, config, pause, HUDs), keybinds, mixins, Mod Menu.

## In progress
- First compile of the client source set.

## Next
- Lang files (en_us, de_de), icons, compile fixes, self-review #1, runtime tests under Xvfb, screenshots, README, dist jar.
