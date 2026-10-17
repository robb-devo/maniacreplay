package dev.robb.streamerreplay.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Reading, writing, updating and recovering {@value ReplayFormat#EXTENSION} archives. */
public final class ReplayArchive {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final TypeToken<List<Marker>> MARKER_LIST = new TypeToken<>() {
	};

	private ReplayArchive() {
	}

	/**
	 * Packages a finished recording stream into a replay archive. The packet stream is copied verbatim
	 * (no re-compression), so this is I/O bound and fast. On success the {@code .rec} file is deleted.
	 */
	public static void finish(Path recording, Path target, ReplayMeta meta, List<Marker> markers, byte[] thumbnailPng) throws IOException {
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try (OutputStream fileOut = Files.newOutputStream(temp); ZipOutputStream zip = new ZipOutputStream(fileOut)) {
			putDeflated(zip, ReplayFormat.ENTRY_META, GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
			putDeflated(zip, ReplayFormat.ENTRY_MARKERS, GSON.toJson(sorted(markers)).getBytes(StandardCharsets.UTF_8));
			if (thumbnailPng != null && thumbnailPng.length > 0) {
				putStored(zip, ReplayFormat.ENTRY_THUMBNAIL, thumbnailPng);
			}
			putStoredFile(zip, ReplayFormat.ENTRY_PACKETS, recording);
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		Files.deleteIfExists(recording);
	}

	private static List<Marker> sorted(List<Marker> markers) {
		List<Marker> list = new ArrayList<>(markers == null ? List.of() : markers);
		Collections.sort(list);
		return list;
	}

	private static void putDeflated(ZipOutputStream zip, String name, byte[] data) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(data);
		zip.closeEntry();
	}

	private static void putStored(ZipOutputStream zip, String name, byte[] data) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setMethod(ZipEntry.STORED);
		CRC32 crc = new CRC32();
		crc.update(data);
		entry.setCrc(crc.getValue());
		entry.setSize(data.length);
		entry.setCompressedSize(data.length);
		zip.putNextEntry(entry);
		zip.write(data);
		zip.closeEntry();
	}

	/** Stores an already-compressed file. Requires one extra read pass for the CRC, which is cheap compared to deflating. */
	private static void putStoredFile(ZipOutputStream zip, String name, Path file) throws IOException {
		long size = Files.size(file);
		CRC32 crc = new CRC32();
		byte[] buffer = new byte[256 * 1024];
		try (InputStream in = Files.newInputStream(file)) {
			int read;
			while ((read = in.read(buffer)) > 0) {
				crc.update(buffer, 0, read);
			}
		}
		ZipEntry entry = new ZipEntry(name);
		entry.setMethod(ZipEntry.STORED);
		entry.setCrc(crc.getValue());
		entry.setSize(size);
		entry.setCompressedSize(size);
		zip.putNextEntry(entry);
		try (InputStream in = Files.newInputStream(file)) {
			in.transferTo(zip);
		}
		zip.closeEntry();
	}

	public static ReplayMeta readMeta(ZipFile zip) throws IOException {
		byte[] json = readEntry(zip, ReplayFormat.ENTRY_META);
		if (json == null) {
			throw new IOException("Missing " + ReplayFormat.ENTRY_META);
		}
		ReplayMeta meta = GSON.fromJson(new String(json, StandardCharsets.UTF_8), ReplayMeta.class);
		if (meta == null) {
			throw new IOException("Empty " + ReplayFormat.ENTRY_META);
		}
		return meta;
	}

	public static List<Marker> readMarkers(ZipFile zip) throws IOException {
		byte[] json = readEntry(zip, ReplayFormat.ENTRY_MARKERS);
		if (json == null) {
			return new ArrayList<>();
		}
		List<Marker> markers = GSON.fromJson(new String(json, StandardCharsets.UTF_8), MARKER_LIST.getType());
		return markers == null ? new ArrayList<>() : sorted(markers);
	}

	/** Returns the raw bytes of an entry or {@code null} if absent. */
	public static byte[] readEntry(ZipFile zip, String name) throws IOException {
		ZipEntry entry = zip.getEntry(name);
		if (entry == null) {
			return null;
		}
		try (InputStream in = zip.getInputStream(entry)) {
			return in.readAllBytes();
		}
	}

	/** Opens a reader positioned at the first packet frame. */
	public static PacketStreamReader openPackets(ZipFile zip) throws IOException {
		ZipEntry entry = zip.getEntry(ReplayFormat.ENTRY_PACKETS);
		if (entry == null) {
			throw new IOException("Missing " + ReplayFormat.ENTRY_PACKETS);
		}
		return new PacketStreamReader(zip.getInputStream(entry));
	}

	/**
	 * Adds or replaces small entries (meta, markers, paths) in place. Uses the JDK zip file system, which copies
	 * untouched entries (the large packet stream) without re-compressing them.
	 */
	public static void updateEntries(Path archive, Map<String, byte[]> entries) throws IOException {
		URI uri = URI.create("jar:" + archive.toUri());
		try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				Path path = fs.getPath(e.getKey());
				if (e.getValue() == null) {
					Files.deleteIfExists(path);
				} else {
					Files.write(path, e.getValue());
				}
			}
		}
	}

	public static byte[] toJson(Object value) {
		return GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Turns an orphaned {@code .rec} file (left behind by a crash) into a normal replay. Scans the stream to find
	 * the last intact frame, which becomes the replay duration.
	 *
	 * @param baseMeta metadata written when the recording started (may be {@code null})
	 * @return the path of the created archive
	 */
	public static Path recover(Path recording, ReplayMeta baseMeta, List<Marker> markers, byte[] thumbnailPng) throws IOException {
		long frames = 0;
		long lastTime = 0;
		try (PacketStreamReader reader = new PacketStreamReader(Files.newInputStream(recording))) {
			while (reader.next()) {
				frames++;
				lastTime = reader.time();
			}
		}
		if (frames == 0) {
			throw new IOException("Recording contains no data");
		}
		ReplayMeta meta = baseMeta != null ? baseMeta.copy() : new ReplayMeta();
		meta.durationMs = lastTime;
		meta.packetCount = frames;
		meta.recovered = true;
		String fileName = recording.getFileName().toString();
		String base = fileName.substring(0, fileName.length() - ReplayFormat.RECORDING_EXTENSION.length());
		Path target = uniqueSibling(recording, base, ReplayFormat.EXTENSION);
		List<Marker> clamped = new ArrayList<>();
		if (markers != null) {
			for (Marker marker : markers) {
				if (marker.timeMs() <= lastTime) {
					clamped.add(marker);
				}
			}
		}
		finish(recording, target, meta, clamped, thumbnailPng);
		return target;
	}

	/** Returns {@code dir/base+ext}, or {@code dir/base (2)+ext} etc. if taken. */
	public static Path uniqueSibling(Path sibling, String base, String extension) {
		Path dir = sibling.toAbsolutePath().getParent();
		Path candidate = dir.resolve(base + extension);
		int n = 2;
		while (Files.exists(candidate)) {
			candidate = dir.resolve(base + " (" + n++ + ")" + extension);
		}
		return candidate;
	}
}
