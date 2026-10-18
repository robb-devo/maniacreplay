package dev.robb.streamerreplay.library;

import dev.robb.streamerreplay.ui.LocalizedIOException;

import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.format.ReplayArchive;
import dev.robb.streamerreplay.format.ReplayFormat;
import dev.robb.streamerreplay.format.Marker;
import dev.robb.streamerreplay.format.ReplayMeta;
import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.ui.Notifications;
import dev.robb.streamerreplay.util.CleanupPolicy;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

/** File operations on the replay folder. All methods do blocking I/O; the UI calls them off-thread. */
public final class ReplayLibrary {
	private ReplayLibrary() {
	}

	public static CompletableFuture<List<ReplayInfo>> scanAsync() {
		return CompletableFuture.supplyAsync(ReplayLibrary::scan);
	}

	/** Lists all replays, newest first. */
	public static List<ReplayInfo> scan() {
		return scan(true);
	}

	private static List<ReplayInfo> scan(boolean thumbnails) {
		List<ReplayInfo> result = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(StreamerReplay.replayFolder(), "*" + ReplayFormat.EXTENSION)) {
			for (Path file : files) {
				result.add(read(file, thumbnails));
			}
		} catch (IOException e) {
			StreamerReplay.LOGGER.warn("Could not list replays", e);
		}
		result.sort(Comparator.comparingLong(ReplayLibrary::sortKey).reversed());
		return result;
	}

	private static long sortKey(ReplayInfo info) {
		return info.meta() != null && info.meta().recordedAt > 0 ? info.meta().recordedAt : info.modifiedMs();
	}

	static ReplayInfo read(Path file) {
		return read(file, true);
	}

	private static ReplayInfo read(Path file, boolean withThumbnail) {
		String name = displayName(file);
		long size = 0;
		long modified = 0;
		try {
			size = Files.size(file);
			modified = Files.getLastModifiedTime(file).toMillis();
			try (ZipFile zip = new ZipFile(file.toFile())) {
				ReplayMeta meta = ReplayArchive.readMeta(zip);
				List<Marker> markers = List.copyOf(ReplayArchive.readMarkers(zip));
				byte[] thumb = withThumbnail ? ReplayArchive.readEntry(zip, ReplayFormat.ENTRY_THUMBNAIL) : null;
				return new ReplayInfo(file, name, meta, markers, size, modified, thumb != null && thumb.length > 0 ? thumb : null, null);
			}
		} catch (IOException | RuntimeException e) {
			return new ReplayInfo(file, name, null, List.of(), size, modified, null, String.valueOf(e.getMessage()));
		}
	}

	public static String displayName(Path file) {
		String name = file.getFileName().toString();
		return name.endsWith(ReplayFormat.EXTENSION) ? name.substring(0, name.length() - ReplayFormat.EXTENSION.length()) : name;
	}

	/** Device names Windows refuses as file names, whatever the extension. */
	private static final Set<String> WINDOWS_RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
		"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
		"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

	/** False for names that at least one desktop OS does not allow as a file name. */
	public static boolean isValidName(String name) {
		String trimmed = name.strip();
		return !trimmed.isEmpty() && trimmed.length() <= 120 && !trimmed.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")
			&& !trimmed.startsWith(".") && !trimmed.endsWith(".") && !WINDOWS_RESERVED.contains(trimmed.toUpperCase(Locale.ROOT));
	}

	/** Renames a replay (and its camera path sidecar). */
	public static Path rename(Path file, String newName) throws IOException {
		Path target = file.resolveSibling(newName.strip() + ReplayFormat.EXTENSION);
		if (Files.exists(target) && !Files.isSameFile(file, target)) {
			throw new LocalizedIOException("streamerreplay.error.name_taken");
		}
		Path moved = Files.move(file, target);
		Path paths = pathsSidecar(file);
		if (Files.exists(paths)) {
			Files.move(paths, pathsSidecar(target));
		}
		return moved;
	}

	public static void delete(Path file) throws IOException {
		Files.deleteIfExists(pathsSidecar(file));
		Files.delete(file);
	}

	/** The camera path of a replay, {@code name.sreplay.paths}. */
	public static Path pathsSidecar(Path replay) {
		return replay.resolveSibling(replay.getFileName() + ReplayFormat.PATHS_SIDECAR_EXTENSION);
	}

	/**
	 * Deletes replays older than the configured number of days, except those with markers or a custom name (see
	 * {@link CleanupPolicy}). Runs on the saver thread, after a recording was saved and once at start-up.
	 */
	public static int cleanUp() {
		int days = ReplayConfig.get().cleanupAfterDays();
		if (days <= 0) {
			return 0;
		}
		Path open = ReplayManager.openFile();
		List<CleanupPolicy.Candidate> candidates = new ArrayList<>();
		for (ReplayInfo info : scan(false)) {
			if (info.readable() && !info.file().equals(open)) {
				candidates.add(info.cleanupCandidate());
			}
		}
		int deleted = 0;
		for (Path file : CleanupPolicy.expired(candidates, days, System.currentTimeMillis())) {
			try {
				delete(file);
				deleted++;
				StreamerReplay.LOGGER.info("Deleted old replay {} (older than {} days)", file.getFileName(), days);
			} catch (IOException e) {
				StreamerReplay.LOGGER.warn("Could not delete old replay {}", file.getFileName(), e);
			}
		}
		if (deleted > 0) {
			Notifications.recording(Component.translatable("streamerreplay.toast.cleaned_up", deleted, days), null);
		}
		return deleted;
	}
}
