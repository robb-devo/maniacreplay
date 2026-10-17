package dev.robb.streamerreplay.render;

import dev.robb.streamerreplay.StreamerReplay;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Feeds raw RGBA frames into an ffmpeg process on a dedicated writer thread.
 *
 * <p>Frame buffers are pooled: {@link #borrow()} hands out a recycled array and the writer thread returns it after
 * writing, so a long export allocates only a handful of frame-sized arrays.
 */
public final class FfmpegEncoder implements AutoCloseable {
	private static final byte[] END = new byte[0];
	private static final int QUEUE = 4;

	private final int frameBytes;
	private final Process process;
	private final OutputStream stdin;
	private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE);
	private final BlockingQueue<byte[]> pool = new ArrayBlockingQueue<>(QUEUE + 4);
	private final Thread writer;
	private volatile IOException failure;
	private volatile long framesWritten;

	private FfmpegEncoder(int frameBytes, Process process) {
		this.frameBytes = frameBytes;
		this.process = process;
		this.stdin = new BufferedOutputStream(process.getOutputStream(), 1 << 20);
		this.writer = Thread.ofPlatform().name("StreamerReplay-ffmpeg-writer").daemon().start(this::writeLoop);
	}

	/** Starts ffmpeg. ffmpeg's own log goes to {@code logFile}. */
	public static FfmpegEncoder start(ExportSettings settings, int width, int height, boolean x264, Path logFile) throws IOException {
		List<String> cmd = command(settings, width, height, x264);
		StreamerReplay.LOGGER.info("Starting video export: {}", String.join(" ", cmd));
		Process process = new ProcessBuilder(cmd)
			.redirectErrorStream(true)
			.redirectOutput(logFile.toFile())
			.start();
		return new FfmpegEncoder(width * height * 4, process);
	}

	static List<String> command(ExportSettings settings, int width, int height, boolean x264) {
		List<String> cmd = new ArrayList<>(List.of(settings.ffmpeg(), "-hide_banner", "-loglevel", "warning", "-y",
			"-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r", Integer.toString(settings.fps()), "-i", "-"));
		cmd.add("-vf");
		cmd.add(filters(settings, width, height));
		if (x264) {
			cmd.addAll(List.of("-c:v", "libx264", "-preset", settings.quality().preset, "-crf", Integer.toString(settings.quality().crf)));
		} else {
			cmd.addAll(List.of("-c:v", "mpeg4", "-q:v", "2"));
		}
		cmd.addAll(List.of("-pix_fmt", "yuv420p", "-movflags", "+faststart", settings.output().toAbsolutePath().toString()));
		return cmd;
	}

	/** GPU rows are bottom-up; flip, then scale/letterbox to the target size; yuv420p needs even dimensions. */
	static String filters(ExportSettings settings, int width, int height) {
		StringBuilder vf = new StringBuilder("vflip");
		int w = settings.outputWidth();
		int h = settings.outputHeight();
		if (w > 0 && h > 0 && (w != width || h != height)) {
			vf.append(",scale=").append(w).append(':').append(h).append(":force_original_aspect_ratio=decrease:flags=lanczos")
				.append(",pad=").append(w).append(':').append(h).append(":(ow-iw)/2:(oh-ih)/2");
		} else if (width % 2 != 0 || height % 2 != 0) {
			vf.append(",crop=trunc(iw/2)*2:trunc(ih/2)*2");
		}
		return vf.toString();
	}

	public int frameBytes() {
		return frameBytes;
	}

	/** A buffer of exactly {@link #frameBytes()} bytes to fill with one frame. */
	public byte[] borrow() {
		byte[] buffer = pool.poll();
		return buffer != null ? buffer : new byte[frameBytes];
	}

	/** Queues a filled frame. Blocks if ffmpeg is behind (natural backpressure for the render loop). */
	public void submit(byte[] frame) throws IOException {
		checkFailure();
		try {
			while (!queue.offer(frame, 100, TimeUnit.MILLISECONDS)) {
				checkFailure();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		}
	}

	public long framesWritten() {
		return framesWritten;
	}

	public IOException failure() {
		return failure;
	}

	private void checkFailure() throws IOException {
		if (failure != null) {
			throw failure;
		}
		if (!process.isAlive()) {
			throw new IOException("ffmpeg stopped unexpectedly (exit code " + process.exitValue() + ")");
		}
	}

	private void writeLoop() {
		try {
			while (true) {
				byte[] frame = queue.take();
				if (frame == END) {
					break;
				}
				stdin.write(frame);
				framesWritten++;
				pool.offer(frame);
			}
			stdin.close();
		} catch (IOException e) {
			failure = e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Closes the input and waits for ffmpeg to finish writing the file. Returns ffmpeg's exit code. */
	public int finish(long timeoutSeconds) throws IOException {
		try {
			queue.put(END);
			writer.join(TimeUnit.SECONDS.toMillis(timeoutSeconds));
			if (failure != null) {
				throw failure;
			}
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("ffmpeg did not finish in time");
			}
			return process.exitValue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		}
	}

	/** Aborts the export immediately. */
	@Override
	public void close() {
		writer.interrupt();
		process.destroyForcibly();
		try {
			stdin.close();
		} catch (IOException ignored) {
			// ffmpeg is gone anyway
		}
	}
}
