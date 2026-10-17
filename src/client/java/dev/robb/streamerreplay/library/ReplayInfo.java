package dev.robb.streamerreplay.library;

import dev.robb.streamerreplay.format.ReplayMeta;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/** One replay file as shown in the browser. {@code meta} is null if the file could not be read. */
public record ReplayInfo(Path file, String name, @Nullable ReplayMeta meta, int markers, long sizeBytes, long modifiedMs,
						 byte @Nullable [] thumbnailPng, @Nullable String error) {
	public boolean readable() {
		return meta != null;
	}
}
