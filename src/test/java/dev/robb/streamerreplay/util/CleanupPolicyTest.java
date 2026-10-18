package dev.robb.streamerreplay.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CleanupPolicyTest {
	private static final long DAY = 24L * 60 * 60 * 1000;
	private static final long NOW = 1_800_000_000_000L;

	private static CleanupPolicy.Candidate replay(String name, long ageDays, int markers) {
		return new CleanupPolicy.Candidate(Path.of(name + ".sreplay"), name, NOW - ageDays * DAY, markers);
	}

	@Test
	void deletesOnlyOldUntouchedRecordings() {
		var old = replay("2026-09-01_20-15-00 Survival", 40, 0);
		var oldTwin = replay("2026-09-01_20-15-00 Survival (2)", 40, 0);
		var recent = replay("2026-10-10_20-15-00 Survival", 8, 0);
		var marked = replay("2026-09-01_21-00-00 Survival", 40, 2);
		var renamed = replay("Dragon kill", 40, 0);
		List<Path> expired = CleanupPolicy.expired(List.of(old, oldTwin, recent, marked, renamed), 30, NOW);
		assertEquals(List.of(old.file(), oldTwin.file()), expired);
	}

	@Test
	void offAndUnknownDatesDeleteNothing() {
		var old = replay("2026-01-01_10-00-00 World", 300, 0);
		assertTrue(CleanupPolicy.expired(List.of(old), 0, NOW).isEmpty());
		var undated = new CleanupPolicy.Candidate(Path.of("x.sreplay"), "2026-01-01_10-00-00 World", 0, 0);
		assertTrue(CleanupPolicy.expired(List.of(undated), 30, NOW).isEmpty());
	}
}
