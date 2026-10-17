package dev.robb.streamerreplay.replay;

/** Replay time in milliseconds, advanced by real time multiplied by the playback speed. */
public final class ReplayClock {
	public static final double MIN_SPEED = 0.05;
	public static final double MAX_SPEED = 16.0;

	private final long durationMs;
	private double timeMs;
	private boolean playing;
	private double speed = 1.0;
	private long lastNanos = -1;

	public ReplayClock(long durationMs) {
		this.durationMs = durationMs;
	}

	/** Advances by the real time since the last call (if playing). */
	public void update(long nowNanos) {
		if (lastNanos >= 0 && playing) {
			double delta = (nowNanos - lastNanos) / 1_000_000.0;
			timeMs = Math.min(durationMs, timeMs + Math.min(delta, 250.0) * speed);
			if (timeMs >= durationMs) {
				playing = false;
			}
		}
		lastNanos = nowNanos;
	}

	/** Moves by an exact amount (used for deterministic video export). */
	public void step(double deltaMs) {
		timeMs = Math.max(0, Math.min(durationMs, timeMs + deltaMs));
	}

	public double timeMs() {
		return timeMs;
	}

	public void setTime(double time) {
		timeMs = Math.max(0, Math.min(durationMs, time));
	}

	public long durationMs() {
		return durationMs;
	}

	public boolean isPlaying() {
		return playing;
	}

	public void setPlaying(boolean playing) {
		if (playing && timeMs >= durationMs) {
			timeMs = 0; // play from the start again when at the end
		}
		this.playing = playing;
	}

	public double speed() {
		return speed;
	}

	public void setSpeed(double speed) {
		this.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
	}
}
