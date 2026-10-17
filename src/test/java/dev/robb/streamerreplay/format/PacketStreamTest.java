package dev.robb.streamerreplay.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class PacketStreamTest {
	@TempDir
	Path dir;

	private static byte[] payload(Random random, int size) {
		byte[] data = new byte[size];
		random.nextBytes(data);
		return data;
	}

	@Test
	void roundTripsFramesIncludingOversizedOnes() throws IOException {
		Path file = dir.resolve("a.rec");
		Random random = new Random(42);
		List<byte[]> written = new ArrayList<>();
		try (PacketStreamWriter writer = new PacketStreamWriter(file, 1024, 6)) {
			for (int i = 0; i < 500; i++) {
				int size = i % 97 == 0 ? 5000 : random.nextInt(300); // some frames exceed the chunk size
				byte[] data = payload(random, size);
				written.add(data);
				if (i % 2 == 0) {
					writer.write(i * 10, data, 0, data.length);
				} else {
					ByteBuf buf = Unpooled.wrappedBuffer(new byte[3], data).skipBytes(3);
					writer.write(i * 10, buf);
					assertEquals(data.length, buf.readableBytes(), "writer must not consume the buffer");
				}
				if (i % 50 == 0) {
					writer.flush();
				}
			}
			assertEquals(500, writer.frameCount());
		}
		try (PacketStreamReader reader = new PacketStreamReader(Files.newInputStream(file))) {
			for (int i = 0; i < 500; i++) {
				assertTrue(reader.next(), "frame " + i);
				assertEquals(i * 10, reader.time());
				assertArrayEquals(written.get(i), Arrays.copyOf(reader.data(), reader.length()));
			}
			assertFalse(reader.next());
			assertFalse(reader.wasTruncated());
		}
	}

	@Test
	void timestampsNeverGoBackwards() throws IOException {
		Path file = dir.resolve("b.rec");
		try (PacketStreamWriter writer = new PacketStreamWriter(file)) {
			writer.write(100, new byte[1], 0, 1);
			writer.write(50, new byte[1], 0, 1);
		}
		try (PacketStreamReader reader = new PacketStreamReader(Files.newInputStream(file))) {
			assertTrue(reader.next());
			assertTrue(reader.next());
			assertEquals(100, reader.time());
		}
	}

	@Test
	void concurrentWritersProduceIntactFrames() throws Exception {
		Path file = dir.resolve("c.rec");
		int threads = 4;
		int perThread = 2000;
		try (PacketStreamWriter writer = new PacketStreamWriter(file, 4096, 1)) {
			CountDownLatch start = new CountDownLatch(1);
			List<Thread> list = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				byte value = (byte) t;
				Thread thread = new Thread(() -> {
					try {
						start.await();
					} catch (InterruptedException e) {
						return;
					}
					byte[] data = new byte[64];
					Arrays.fill(data, value);
					for (int i = 0; i < perThread; i++) {
						writer.write(i, data, 0, data.length);
					}
				});
				thread.start();
				list.add(thread);
			}
			start.countDown();
			for (Thread thread : list) {
				thread.join();
			}
		}
		int count = 0;
		try (PacketStreamReader reader = new PacketStreamReader(Files.newInputStream(file))) {
			while (reader.next()) {
				assertEquals(64, reader.length());
				byte first = reader.data()[0];
				for (int i = 0; i < 64; i++) {
					assertEquals(first, reader.data()[i], "frames must not interleave");
				}
				count++;
			}
		}
		assertEquals(threads * perThread, count);
	}

	@Test
	void truncatedStreamIsReadableUpToLastFlush() throws IOException {
		Path file = dir.resolve("d.rec");
		PacketStreamWriter writer = new PacketStreamWriter(file);
		for (int i = 0; i < 100; i++) {
			writer.write(i, new byte[]{(byte) i, 1, 2, 3}, 0, 4);
		}
		writer.flush();
		// Give the writer thread time to sync-flush, then simulate a crash by copying the file without closing.
		long deadline = System.currentTimeMillis() + 5000;
		while (Files.size(file) < 20 && System.currentTimeMillis() < deadline) {
			Thread.onSpinWait();
		}
		awaitStableSize(file);
		Path crashed = dir.resolve("crashed.sreplay.rec");
		Files.copy(file, crashed);
		writer.close();

		int frames = 0;
		try (PacketStreamReader reader = new PacketStreamReader(Files.newInputStream(crashed))) {
			while (reader.next()) {
				assertEquals(frames, reader.time());
				frames++;
			}
			assertTrue(reader.wasTruncated());
		}
		assertEquals(100, frames);
	}

	private static void awaitStableSize(Path file) throws IOException {
		long last = -1;
		for (int i = 0; i < 50; i++) {
			long size = Files.size(file);
			if (size == last) {
				return;
			}
			last = size;
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				return;
			}
		}
	}
}
