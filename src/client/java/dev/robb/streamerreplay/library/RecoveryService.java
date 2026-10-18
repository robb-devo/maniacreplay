package dev.robb.streamerreplay.library;

import com.google.gson.reflect.TypeToken;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.format.Marker;
import dev.robb.streamerreplay.format.ReplayArchive;
import dev.robb.streamerreplay.format.ReplayFormat;
import dev.robb.streamerreplay.format.ReplayMeta;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.ui.Notifications;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Turns recordings left behind by a crash or power loss ({@code .sreplay.rec} plus sidecars) into normal replays.
 * Runs on the recording saver thread, so it never races with a recording that is being saved.
 */
public final class RecoveryService {
	private RecoveryService() {
	}

	public static CompletableFuture<Integer> recoverAsync() {
		return CompletableFuture.supplyAsync(RecoveryService::recoverAll, RecordingManager.saver());
	}

	/** A partial archive this old is not being written by anyone; it was left by a game that was killed while saving. */
	private static final long STALE_PARTIAL_MS = 10 * 60 * 1000L;

	static int recoverAll() {
		deleteStalePartialArchives();
		Path activeFile = RecordingManager.activeRecordingFile();
		int recovered = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(StreamerReplay.replayFolder(), "*" + ReplayFormat.RECORDING_EXTENSION)) {
			for (Path rec : files) {
				if (rec.equals(activeFile)) {
					continue;
				}
				if (recover(rec)) {
					recovered++;
				}
			}
		} catch (IOException e) {
			StreamerReplay.LOGGER.warn("Could not scan for unfinished recordings", e);
		}
		if (recovered > 0) {
			Notifications.recording(recovered == 1 ? Component.translatable("streamerreplay.toast.recovered.one") : Component.translatable("streamerreplay.toast.recovered", recovered), null);
		}
		return recovered;
	}

	private static void deleteStalePartialArchives() {
		long cutoff = System.currentTimeMillis() - STALE_PARTIAL_MS;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(StreamerReplay.replayFolder(), "*" + ReplayFormat.PARTIAL_EXTENSION)) {
			for (Path partial : files) {
				if (Files.getLastModifiedTime(partial).toMillis() < cutoff) {
					Files.deleteIfExists(partial);
					StreamerReplay.LOGGER.info("Deleted {}, left behind by an interrupted save", partial.getFileName());
				}
			}
		} catch (IOException e) {
			StreamerReplay.LOGGER.warn("Could not clean up partial archives", e);
		}
	}

	private static boolean recover(Path rec) {
		String name = rec.getFileName().toString();
		String base = name.substring(0, name.length() - ReplayFormat.RECORDING_EXTENSION.length());
		Path metaFile = rec.resolveSibling(base + ReplayFormat.META_SIDECAR_EXTENSION);
		Path markersFile = rec.resolveSibling(base + ReplayFormat.MARKERS_SIDECAR_EXTENSION);
		Path thumbFile = rec.resolveSibling(base + ReplayFormat.THUMB_SIDECAR_EXTENSION);
		try {
			ReplayMeta meta = Files.exists(metaFile) ? ReplayArchive.GSON.fromJson(Files.readString(metaFile, StandardCharsets.UTF_8), ReplayMeta.class) : null;
			List<Marker> markers = Files.exists(markersFile)
				? ReplayArchive.GSON.fromJson(Files.readString(markersFile, StandardCharsets.UTF_8), new TypeToken<List<Marker>>() { }.getType())
				: List.of();
			byte[] thumb = Files.exists(thumbFile) ? Files.readAllBytes(thumbFile) : null;
			Path saved = ReplayArchive.recover(rec, meta, markers, thumb);
			StreamerReplay.LOGGER.info("Recovered unfinished recording as {}", saved.getFileName());
			deleteSidecars(metaFile, markersFile, thumbFile);
			return true;
		} catch (IOException | RuntimeException e) {
			StreamerReplay.LOGGER.warn("Could not recover {}: {}", rec.getFileName(), e.getMessage());
			if (isEmpty(rec)) {
				deleteQuietly(rec); // nothing worth keeping
				deleteSidecars(metaFile, markersFile, thumbFile);
			}
			return false; // otherwise keep the file; maybe a later version can read it
		}
	}

	private static void deleteSidecars(Path... files) {
		for (Path file : files) {
			deleteQuietly(file);
		}
	}

	private static boolean isEmpty(Path file) {
		try {
			return Files.size(file) <= ReplayFormat.STREAM_MAGIC.length + 16;
		} catch (IOException e) {
			return false;
		}
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// best effort
		}
	}
}
