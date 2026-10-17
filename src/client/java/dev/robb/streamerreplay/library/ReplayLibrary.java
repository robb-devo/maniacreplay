package dev.robb.streamerreplay.library;

import dev.robb.streamerreplay.ui.LocalizedIOException;

import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.format.ReplayArchive;
import dev.robb.streamerreplay.format.ReplayFormat;
import dev.robb.streamerreplay.format.ReplayMeta;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
		List<ReplayInfo> result = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(StreamerReplay.replayFolder(), "*" + ReplayFormat.EXTENSION)) {
			for (Path file : files) {
				result.add(read(file));
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
		String name = displayName(file);
		long size = 0;
		long modified = 0;
		try {
			size = Files.size(file);
			modified = Files.getLastModifiedTime(file).toMillis();
			try (ZipFile zip = new ZipFile(file.toFile())) {
				ReplayMeta meta = ReplayArchive.readMeta(zip);
				int markers = ReplayArchive.readMarkers(zip).size();
				byte[] thumb = ReplayArchive.readEntry(zip, ReplayFormat.ENTRY_THUMBNAIL);
				return new ReplayInfo(file, name, meta, markers, size, modified, thumb != null && thumb.length > 0 ? thumb : null, null);
			}
		} catch (IOException | RuntimeException e) {
			return new ReplayInfo(file, name, null, 0, size, modified, null, String.valueOf(e.getMessage()));
		}
	}

	public static String displayName(Path file) {
		String name = file.getFileName().toString();
		return name.endsWith(ReplayFormat.EXTENSION) ? name.substring(0, name.length() - ReplayFormat.EXTENSION.length()) : name;
	}

	/** Characters that are not allowed in file names on at least one desktop OS. */
	public static boolean isValidName(String name) {
		String trimmed = name.strip();
		return !trimmed.isEmpty() && trimmed.length() <= 120 && !trimmed.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")
			&& !trimmed.startsWith(".") && !trimmed.endsWith(".");
	}

	/** Renames a replay (and its camera path sidecar). */
	public static Path rename(Path file, String newName) throws IOException {
		Path target = file.resolveSibling(newName.strip() + ReplayFormat.EXTENSION);
		if (Files.exists(target) && !Files.isSameFile(file, target)) {
			throw new LocalizedIOException("streamerreplay.error.name_taken");
		}
		Path moved = Files.move(file, target);
		Path paths = file.resolveSibling(file.getFileName() + ".paths");
		if (Files.exists(paths)) {
			Files.move(paths, target.resolveSibling(target.getFileName() + ".paths"));
		}
		return moved;
	}

	public static void delete(Path file) throws IOException {
		Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".paths"));
		Files.delete(file);
	}
}
