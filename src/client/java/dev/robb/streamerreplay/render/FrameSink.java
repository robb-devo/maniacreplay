package dev.robb.streamerreplay.render;

import java.io.IOException;

/** Where exported frames go: an ffmpeg process ({@link FfmpegEncoder}) or PNG files ({@link PngSequenceWriter}). */
public interface FrameSink extends AutoCloseable {
	int frameBytes();

	/** A buffer of exactly {@link #frameBytes()} bytes to fill with one RGBA frame (bottom-up rows). */
	byte[] borrow();

	/** Queues a filled frame; blocks when the writer is behind. */
	void submit(byte[] frame) throws IOException;

	IOException failure();

	/** Writes everything still queued and returns 0 on success. */
	int finish(long timeoutSeconds) throws IOException;

	@Override
	void close();
}
