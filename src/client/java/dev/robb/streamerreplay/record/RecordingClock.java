package dev.robb.streamerreplay.record;

/**
 * Milliseconds since the recording started, excluding time spent in the singleplayer pause menu.
 * Readable from any thread; {@link #setPaused(boolean)} is called from the render thread.
 */
final class RecordingClock {
	private final long startNanos = System.nanoTime();
	private volatile long pausedNanos;
	private volatile long pauseStartedAt = -1;

	int now() {
		long pauseStart = pauseStartedAt;
		long end = pauseStart >= 0 ? pauseStart : System.nanoTime();
		return (int) Math.min(Integer.MAX_VALUE, (end - startNanos - pausedNanos) / 1_000_000L);
	}

	void setPaused(boolean paused) {
		if (paused && pauseStartedAt < 0) {
			pauseStartedAt = System.nanoTime();
		} else if (!paused && pauseStartedAt >= 0) {
			pausedNanos += System.nanoTime() - pauseStartedAt;
			pauseStartedAt = -1;
		}
	}
}
