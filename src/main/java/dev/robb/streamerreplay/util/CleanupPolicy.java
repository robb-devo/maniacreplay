package dev.robb.streamerreplay.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which replays the automatic clean-up may delete.
 *
 * <p>The rule is meant to be guessable without reading a manual: a replay is deleted once it is older than the
 * configured number of days, unless the player showed interest in it. Interest means it has at least one marker
 * or was renamed (its name no longer is the automatic {@code yyyy-MM-dd_HH-mm-ss World} name).
 */
public final class CleanupPolicy {
	private static final long DAY_MS = 24L * 60 * 60 * 1000;
	/** Automatic names, including the " (2)" suffix used when two recordings start in the same second. */
	private static final Pattern AUTOMATIC_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2} .*");

	private CleanupPolicy() {
	}

	/** One replay as the clean-up sees it. {@code recordedAtMs <= 0} means unknown (never deleted). */
	public record Candidate(Path file, String name, long recordedAtMs, int markers) {
	}

	/** True if the replay is protected from the automatic clean-up. */
	public static boolean isKept(Candidate replay) {
		return replay.markers() > 0 || !AUTOMATIC_NAME.matcher(replay.name()).matches();
	}

	/** The replays to delete now; {@code keepDays <= 0} turns the clean-up off. */
	public static List<Path> expired(List<Candidate> replays, int keepDays, long nowMs) {
		List<Path> out = new ArrayList<>();
		if (keepDays <= 0) {
			return out;
		}
		long cutoff = nowMs - keepDays * DAY_MS;
		for (Candidate replay : replays) {
			if (replay.recordedAtMs() > 0 && replay.recordedAtMs() < cutoff && !isKept(replay)) {
				out.add(replay.file());
			}
		}
		return out;
	}
}
