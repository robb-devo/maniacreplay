package dev.robb.streamerreplay.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.ui.LocalizedIOException;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Export without ffmpeg: every frame becomes a numbered PNG ({@code frame_000001.png}) in a folder next to where the
 * video would have gone. Any video editor (or ffmpeg later) can turn the sequence into a video.
 */
public final class PngSequenceWriter implements FrameSink {
	private static final byte[] END = new byte[0];

	private final int width;
	private final int height;
	private final Path folder;
	private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(3);
	private final BlockingQueue<byte[]> pool = new ArrayBlockingQueue<>(6);
	private final Thread writer;
	private volatile IOException failure;
	private int index;

	private PngSequenceWriter(Path folder, int width, int height) {
		this.folder = folder;
		this.width = width;
		this.height = height;
		this.writer = Thread.ofPlatform().name("StreamerReplay-png-writer").daemon().start(this::writeLoop);
	}

	/** The folder used for a given video file name: "Name.mp4" becomes "Name (PNG frames)". */
	public static Path folderFor(Path video) {
		String name = video.getFileName().toString();
		if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
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
	public void submit(byte[] frame) throws IOException {
		check();
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
				byte[] frame = queue.take();
				if (frame == END) {
					break;
				}
				write(frame);
				pool.offer(frame);
			}
		} catch (IOException e) {
			failure = e;
			StreamerReplay.LOGGER.error("Could not write PNG frame", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void write(byte[] frame) throws IOException {
		try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false)) {
			long pointer = image.getPointer();
			int row = width * 4;
			java.nio.ByteBuffer target = MemoryUtil.memByteBuffer(pointer, row * height);
			for (int y = 0; y < height; y++) { // GPU rows are bottom-up
				target.put(y * row, frame, (height - 1 - y) * row, row);
			}
			index++;
			image.writeToFile(folder.resolve(String.format(java.util.Locale.ROOT, "frame_%06d.png", index)));
		}
	}

	@Override
	public IOException failure() {
		return failure;
	}

	@Override
	public int finish(long timeoutSeconds) throws IOException {
		try {
			queue.put(END);
			writer.join(TimeUnit.SECONDS.toMillis(timeoutSeconds));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LocalizedIOException("streamerreplay.error.interrupted");
		}
		check();
		return 0;
	}

	@Override
	public void close() {
		writer.interrupt();
	}

	public Path folder() {
		return folder;
	}
}
