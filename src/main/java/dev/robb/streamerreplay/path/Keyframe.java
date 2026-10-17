package dev.robb.streamerreplay.path;

/**
 * One camera keyframe.
 *
 * @param pathTimeMs   position on the camera-path (= output video) timeline
 * @param replayTimeMs replay time shown at this keyframe; between keyframes it is interpolated linearly, which
 *                     allows time-lapses, slow motion and "frozen time" camera moves
 * @param camera       camera pose
 */
public record Keyframe(long pathTimeMs, long replayTimeMs, CameraState camera) {
}
