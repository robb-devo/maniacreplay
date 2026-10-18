package dev.robb.streamerreplay.format;

/** A named point of interest on the replay timeline, e.g. set with the marker hotkey while recording. */
public record Marker(long timeMs, String name) implements Comparable<Marker> {
	/** A marker is set after the moment happened ("that was great"), so jumping to one starts this much earlier. */
	public static final long PREROLL_MS = 10_000;

	public Marker {
		name = name == null ? "" : name;
	}

	/** Where watching starts when jumping to this marker. */
	public long startMs() {
		return Math.max(0, timeMs - PREROLL_MS);
	}

	@Override
	public int compareTo(Marker other) {
		return Long.compare(timeMs, other.timeMs);
	}
}
