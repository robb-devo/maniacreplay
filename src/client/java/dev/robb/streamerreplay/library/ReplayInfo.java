package dev.robb.streamerreplay.library;

import dev.robb.streamerreplay.format.Marker;
import dev.robb.streamerreplay.format.ReplayMeta;
import dev.robb.streamerreplay.util.CleanupPolicy;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/** One replay file as shown in the browser. {@code meta} is null if the file could not be read. */
public record ReplayInfo(Path file, String name, @Nullable ReplayMeta meta, List<Marker> markerList, long sizeBytes, long modifiedMs,
						 byte @Nullable [] thumbnailPng, @Nullable String error) {
	public boolean readable() {
		return meta != null;
	}

	public int markers() {
		return markerList.size();
	}

	CleanupPolicy.Candidate cleanupCandidate() {
		return new CleanupPolicy.Candidate(file, name, meta == null ? 0 : meta.recordedAt, markers());
	}

	/** Never deleted by the automatic clean-up (it has markers or was renamed). */
	public boolean kept() {
		return CleanupPolicy.isKept(cleanupCandidate());
	}
}
