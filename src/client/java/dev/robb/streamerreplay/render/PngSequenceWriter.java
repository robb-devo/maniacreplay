package dev.robb.streamerreplay.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.ui.LocalizedIOException;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Export without ffmpeg: every frame becomes a numbered PNG ({@code frame_000001.png}) in a folder next to where the
 * video would have gone. Any video editor (or ffmpeg later) can turn the sequence into a video.
 *
 * <p>PNG compression is slow, and every frame is its own file, so several writer threads encode in parallel. Frames
 * waiting or being encoded stay within {@link #BUFFER_BUDGET_BYTES}, so even 4K fits a default 2 GB heap.
 */
public final class PngSequenceWriter implements FrameSink {
	private static final long BUFFER_BUDGET_BYTES = 256L * 1024 * 1024;
	private static final Frame END = new Frame(-1, new byte[0]);

	private record Frame(int index, byte[] data) {
	}

	private final int width;
	private final int height;
	private final Path folder;
	private final BlockingQueue<Frame> queue;
	private final BlockingQueue<byte[]> pool;
	private final Thread[] writers;
	private volatile IOException failure;
	private int index;

	private PngSequenceWriter(Path folder, int width, int height) {
		this.folder = folder;
		this.width = width;
		this.height = height;
		// in flight: the queue (threads + 1), one frame per thread and the one being filled
		long frames = Math.max(4, BUFFER_BUDGET_BYTES / ((long) width * height * 4));
		int threads = (int) Math.clamp(Math.min(Runtime.getRuntime().availableProcessors() / 2, (frames - 2) / 2), 1, 4);
		this.queue = new ArrayBlockingQueue<>(threads + 1);
		this.pool = new ArrayBlockingQueue<>(threads + 2);
		this.writers = new Thread[threads];
		for (int i = 0; i < threads; i++) {
			writers[i] = Thread.ofPlatform().name("ManiacReplay-png-writer-" + i).daemon().start(this::writeLoop);
		}
	}

	/** The folder used for a given video file name: "Name.mp4" becomes "Name (PNG frames)". */
	public static Path folderFor(Path video) {
		String name = video.getFileName().toString();
		if (name.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
			name = name.substring(0, name.length() - 4);
		}
		return video.resolveSibling(name + " (PNG frames)");
	}

	public static PngSequenceWriter start(Path video, int width, int height) throws IOException {
		Path folder = folderFor(video);
		Files.createDirectories(folder);
		StreamerReplay.LOGGER.info("Exporting PNG frames (no ffmpeg) to {}", folder);
		return new PngSequenceWriter(folder, width, height);
	}

	@Override
	public int frameBytes() {
		return width * height * 4;
	}

	@Override
	public byte[] borrow() {
		byte[] b = pool.poll();
		return b != null ? b : new byte[frameBytes()];
	}

	@Override
	public void submit(byte[] data) throws IOException {
		check();
		Frame frame = new Frame(++index, data);
		try {
			while (!queue.offer(frame, 100, TimeUnit.MILLISECONDS)) {
				check();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LocalizedIOException("streamerreplay.error.interrupted");
		}
	}

	private void check() throws IOException {
		if (failure != null) {
			throw failure;
		}
	}

	private void writeLoop() {
		try {
			while (true) {
				Frame frame = queue.take();
				if (frame == END) {
					break;
				}
				write(frame);
				pool.offer(frame.data());
			}
		} catch (IOException e) {
			failure = e;
			StreamerReplay.LOGGER.error("Could not write PNG frame", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void write(Frame frame) throws IOException {
		try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false)) {
			long pointer = image.getPointer();
			int row = width * 4;
			ByteBuffer target = MemoryUtil.memByteBuffer(pointer, row * height);
			for (int y = 0; y < height; y++) { // GPU rows are bottom-up
				target.put(y * row, frame.data(), (height - 1 - y) * row, row);
			}
			image.writeToFile(folder.resolve(String.format(Locale.ROOT, "frame_%06d.png", frame.index())));
		}
	}

	@Override
	public IOException failure() {
		return failure;
	}

	@Override
	public int finish(long timeoutSeconds) throws IOException {
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
			for (int i = 0; i < writers.length; i++) {
				queue.put(END);
			}
			for (Thread writer : writers) {
				writer.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LocalizedIOException("streamerreplay.error.interrupted");
		}
		check();
		return 0;
	}

	@Override
	public void close() {
		for (Thread writer : writers) {
			writer.interrupt();
		}
	}

	public Path folder() {
		return folder;
	}
}
