package dev.robb.streamerreplay.format;

import io.netty.buffer.ByteBuf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Appends packet frames to a recording file without blocking the calling (network/render) threads.
 *
 * <p>Callers copy frames into a pooled chunk buffer under a short lock; full chunks are handed to a single
 * background thread which compresses and writes them. No allocation happens per frame in the steady state.
 * The deflate stream is sync-flushed on {@link #flush()} so that the file is always readable up to the
 * last flush, which makes crash recovery possible.
 *
 * <p>If the disk cannot keep up, at most {@link #MAX_PENDING_CHUNKS} chunks wait in memory. Beyond that the writer
 * stops accepting frames ({@link #overflowed()}) instead of growing without limit; what was accepted stays valid.
 */
public final class PacketStreamWriter implements AutoCloseable {
	public static final int DEFAULT_CHUNK_SIZE = 256 * 1024;
	private static final int POOL_SIZE = 8;
	/** 256 chunks of 256 KB: 64 MB of unwritten data means the disk is far too slow, not briefly busy. */
	static final int MAX_PENDING_CHUNKS = 256;
	private static final int FRAME_HEADER = 8;
	private static final Chunk POISON = new Chunk(0);

	private final int chunkSize;
	private final BlockingQueue<Chunk> pending = new LinkedBlockingQueue<>();
	private final BlockingQueue<Chunk> pool = new ArrayBlockingQueue<>(POOL_SIZE);
	private final Thread thread;
	private final OutputStream fileOut;
	private final DeflaterOutputStream deflaterOut;
	private final Deflater deflater;

	private Chunk current;
	private int lastTime;
	private long frames;
	private long rawBytes;
	private boolean closed;
	private volatile boolean overflowed;
	private volatile IOException failure;

	public PacketStreamWriter(Path file) throws IOException {
		this(file, DEFAULT_CHUNK_SIZE, Deflater.BEST_SPEED + 2);
	}

	public PacketStreamWriter(Path file, int chunkSize, int compressionLevel) throws IOException {
		this.chunkSize = chunkSize;
		this.fileOut = new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024);
		this.fileOut.write(ReplayFormat.STREAM_MAGIC);
		this.deflater = new Deflater(compressionLevel);
		this.deflaterOut = new DeflaterOutputStream(fileOut, deflater, 64 * 1024, true);
		this.current = new Chunk(chunkSize);
		this.thread = new Thread(this::runWriter, "ManiacReplay-Writer");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.NORM_PRIORITY - 1);
		this.thread.start();
	}

	/** Appends a frame whose payload is the readable region of {@code buf}. The buffer is not modified. */
	public void write(int timeMs, ByteBuf buf) {
		int length = buf.readableBytes();
		synchronized (this) {
			Chunk chunk = reserve(timeMs, length);
			if (chunk == null) {
				return;
			}
			buf.getBytes(buf.readerIndex(), chunk.data, chunk.length, length);
			chunk.length += length;
		}
	}

	/** Appends a frame from a byte array region. */
	public void write(int timeMs, byte[] data, int offset, int length) {
		synchronized (this) {
			Chunk chunk = reserve(timeMs, length);
			if (chunk == null) {
				return;
			}
			System.arraycopy(data, offset, chunk.data, chunk.length, length);
			chunk.length += length;
		}
	}

	/** Writes the frame header and returns a chunk with room for {@code length} payload bytes. Caller holds the lock. */
	private Chunk reserve(int timeMs, int length) {
		if (closed || overflowed) {
			return null;
		}
		int time = Math.max(timeMs, lastTime); // timestamps never go backwards
		lastTime = time;
		int needed = FRAME_HEADER + length;
		if (current.data.length - current.length < needed) {
			submit(false, false);
			if (overflowed) {
				return null;
			}
			if (needed > chunkSize) {
				current = new Chunk(needed); // rare oversized frame, gets dropped after use
			}
		}
		Chunk chunk = current;
		writeInt(chunk, time);
		writeInt(chunk, length);
		frames++;
		rawBytes += needed;
		return chunk;
	}

	private static void writeInt(Chunk chunk, int value) {
		byte[] d = chunk.data;
		int p = chunk.length;
		d[p] = (byte) (value >>> 24);
		d[p + 1] = (byte) (value >>> 16);
		d[p + 2] = (byte) (value >>> 8);
		d[p + 3] = (byte) value;
		chunk.length = p + 4;
	}

	/** Hands everything buffered so far to the writer thread and asks it to sync-flush to disk. */
	public void flush() {
		synchronized (this) {
			if (!closed) {
				submit(true, false);
			}
		}
	}

	/** Queues the current chunk. Unless {@code force}d (closing), a full queue drops it and stops the writer. */
	private void submit(boolean flush, boolean force) {
		Chunk chunk = current;
		if (!force && pending.size() >= MAX_PENDING_CHUNKS) {
			overflowed = true;
			chunk.length = 0;
			return;
		}
		chunk.flush = flush;
		if (chunk.length > 0 || flush) {
			pending.add(chunk);
			Chunk next = pool.poll();
			current = next != null ? next : new Chunk(chunkSize);
		}
	}

	private void runWriter() {
		try {
			while (true) {
				Chunk chunk = pending.take();
				if (chunk == POISON) {
					break;
				}
				if (failure == null) {
					try {
						if (chunk.length > 0) {
							deflaterOut.write(chunk.data, 0, chunk.length);
						}
						if (chunk.flush) {
							deflaterOut.flush();
						}
					} catch (IOException e) {
						failure = e;
					}
				}
				recycle(chunk);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void recycle(Chunk chunk) {
		chunk.length = 0;
		chunk.flush = false;
		if (chunk.data.length == chunkSize) {
			pool.offer(chunk);
		}
	}

	/** True once frames were dropped because the disk could not keep up. Everything before stays readable. */
	public boolean overflowed() {
		return overflowed;
	}

	/** First I/O error that occurred on the writer thread, or {@code null}. */
	public IOException failure() {
		return failure;
	}

	public synchronized long frameCount() {
		return frames;
	}

	public synchronized long rawBytes() {
		return rawBytes;
	}

	public synchronized int lastTime() {
		return lastTime;
	}

	/** Flushes all data, finishes the deflate stream and closes the file. Blocks until the writer thread is done. */
	@Override
	public void close() throws IOException {
		synchronized (this) {
			if (closed) {
				return;
			}
			submit(true, true);
			closed = true;
			pending.add(POISON);
		}
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		try {
			deflaterOut.finish();
			deflaterOut.close();
		} finally {
			deflater.end();
		}
		if (failure != null) {
			throw failure;
		}
	}

	private static final class Chunk {
		final byte[] data;
		int length;
		boolean flush;

		Chunk(int size) {
			this.data = new byte[size];
		}
	}
}
