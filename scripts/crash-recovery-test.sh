#!/usr/bin/env bash
# Real crash-recovery test: records in a game, kills the JVM with SIGKILL mid-recording (no shutdown hooks,
# nothing saved), then starts the game again on the same folder and checks that the replay was recovered and plays.
# Usage: DISPLAY=:99 scripts/crash-recovery-test.sh
set -u
cd "$(dirname "$0")/.."
LOG1=build/crash-phase1.log
LOG2=build/crash-phase2.log
mkdir -p build

./gradlew runClientGameTest --no-daemon -PcrashPhase=crash > "$LOG1" 2>&1 &
GRADLE_PID=$!
for _ in $(seq 1 600); do
	if grep -q "CRASH-POINT" "$LOG1"; then break; fi
	if ! kill -0 "$GRADLE_PID" 2>/dev/null; then echo "phase 1 ended before the crash point"; tail -40 "$LOG1"; exit 1; fi
	sleep 1
done
grep "CRASH-POINT" "$LOG1" || { echo "no crash point"; kill "$GRADLE_PID"; exit 1; }
# the game JVM is the one running the client gametest from this project's run directory
GAME_PID=$(pgrep -f "streamerreplay.gametest.mode=crash" | while read -r p; do
	if [ "$(cat /proc/$p/comm 2>/dev/null)" = "java" ] && tr '\0' ' ' < /proc/$p/cmdline | grep -q "net.fabricmc.devlaunchinjector.Main"; then echo "$p"; fi
done | head -1)
[ -n "$GAME_PID" ] || { echo "game JVM not found"; kill "$GRADLE_PID"; exit 1; }
echo "killing game JVM $GAME_PID with SIGKILL at $(date '+%H:%M:%S %Z')"
kill -9 "$GAME_PID"
wait "$GRADLE_PID" 2>/dev/null
echo "left behind:"; ls -la build/run/clientGameTest/replays/

./gradlew runClientGameTest --no-daemon -PcrashPhase=recover > "$LOG2" 2>&1
STATUS=$?
grep -E "\[gametest\]|Recovered unfinished|Could not recover" "$LOG2"
if [ $STATUS -eq 0 ] && grep -q "CRASH RECOVERY CHECKS PASSED" "$LOG2"; then
	echo "CRASH RECOVERY TEST PASSED"
else
	echo "CRASH RECOVERY TEST FAILED (exit $STATUS)"; tail -60 "$LOG2"; exit 1
fi
