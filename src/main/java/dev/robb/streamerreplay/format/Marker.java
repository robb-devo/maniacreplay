package dev.robb.streamerreplay.format;

/** A named point of interest on the replay timeline, e.g. set with the marker hotkey while recording. */
public record Marker(long timeMs, String name) implements Comparable<Marker> {
	public Marker {
		name = name == null ? "" : name;
	}

	@Override
	public int compareTo(Marker other) {
		return Long.compare(timeMs, other.timeMs);
	}
}
