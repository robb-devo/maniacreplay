package dev.robb.streamerreplay.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ReplayArchiveTest {
	@TempDir
	Path dir;

	private Path record(String name, int frames) throws IOException {
		Path rec = dir.resolve(name + ReplayFormat.RECORDING_EXTENSION);
		try (PacketStreamWriter writer = new PacketStreamWriter(rec)) {
			for (int i = 0; i < frames; i++) {
				byte[] data = ("packet-" + i).getBytes(StandardCharsets.UTF_8);
				writer.write(i * 50, data, 0, data.length);
			}
		}
		return rec;
	}

	@Test
	void finishWritesReadableArchiveAndDeletesRecording() throws IOException {
		Path rec = record("session", 200);
		Path target = dir.resolve("session" + ReplayFormat.EXTENSION);
		ReplayMeta meta = new ReplayMeta();
		meta.worldName = "My World";
		meta.durationMs = 9950;
		byte[] thumb = {1, 2, 3, 4};
		ReplayArchive.finish(rec, target, meta, List.of(new Marker(500, "b"), new Marker(100, "a")), thumb);

		assertFalse(Files.exists(rec));
		try (ZipFile zip = new ZipFile(target.toFile())) {
			ReplayMeta read = ReplayArchive.readMeta(zip);
			assertEquals("My World", read.worldName);
			assertEquals(9950, read.durationMs);
			List<Marker> markers = ReplayArchive.readMarkers(zip);
			assertEquals(List.of(new Marker(100, "a"), new Marker(500, "b")), markers);
			assertArrayEquals(thumb, ReplayArchive.readEntry(zip, ReplayFormat.ENTRY_THUMBNAIL));
			try (PacketStreamReader reader = ReplayArchive.openPackets(zip)) {
				int count = 0;
				while (reader.next()) {
					assertEquals("packet-" + count, new String(reader.data(), 0, reader.length(), StandardCharsets.UTF_8));
					count++;
				}
				assertEquals(200, count);
			}
		}
	}

	@Test
	void recoverTurnsOrphanedRecordingIntoReplay() throws IOException {
		Path rec = record("crash", 30);
		ReplayMeta base = new ReplayMeta();
		base.worldName = "Crashed World";
		Path recovered = ReplayArchive.recover(rec, base, List.of(new Marker(100, "ok"), new Marker(999_999, "too late")), null);
		assertTrue(recovered.getFileName().toString().endsWith(ReplayFormat.EXTENSION));
		try (ZipFile zip = new ZipFile(recovered.toFile())) {
			ReplayMeta meta = ReplayArchive.readMeta(zip);
			assertTrue(meta.recovered);
			assertEquals(29 * 50, meta.durationMs);
			assertEquals(30, meta.packetCount);
			assertEquals("Crashed World", meta.worldName);
			assertEquals(1, ReplayArchive.readMarkers(zip).size());
		}
	}

	@Test
	void uniqueSiblingAvoidsCollisions() throws IOException {
		Files.createFile(dir.resolve("x.sreplay"));
		assertEquals(dir.resolve("x (2).sreplay").toAbsolutePath(), ReplayArchive.uniqueSibling(dir.resolve("any"), "x", ".sreplay"));
	}
}
