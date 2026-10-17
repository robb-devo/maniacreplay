package dev.robb.streamerreplay.format;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * Sequential reader for a packet stream (see {@link ReplayFormat}). Reuses a single buffer, so the data
 * returned by {@link #data()} is only valid until the next call to {@link #next()}.
 *
 * <p>A truncated stream (crashed recording) simply ends early; {@link #wasTruncated()} reports it.
 */
public final class PacketStreamReader implements Closeable {
	private final DataInputStream in;
	private byte[] buffer = new byte[64 * 1024];
	private int time;
	private int length;
	private boolean truncated;
	private boolean finished;

	public PacketStreamReader(InputStream raw) throws IOException {
		InputStream buffered = raw instanceof BufferedInputStream ? raw : new BufferedInputStream(raw, 64 * 1024);
		byte[] magic = buffered.readNBytes(ReplayFormat.STREAM_MAGIC.length);
		if (!Arrays.equals(magic, ReplayFormat.STREAM_MAGIC)) {
			throw new IOException("Not a Streamer Replay packet stream");
		}
		this.in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(buffered), 64 * 1024));
	}

	/** Advances to the next frame. Returns {@code false} at the end of the stream. */
	public boolean next() throws IOException {
		if (finished) {
			return false;
		}
		boolean midFrame = false;
		try {
			int t = in.readInt();
			midFrame = true;
			int len = in.readInt();
			if (len < 0 || len > ReplayFormat.MAX_FRAME_LENGTH) {
				throw new IOException("Corrupt frame length " + len);
			}
			if (buffer.length < len) {
				buffer = new byte[Math.max(len, buffer.length * 2)];
			}
			in.readFully(buffer, 0, len);
			time = t;
			length = len;
			return true;
		} catch (EOFException e) {
			// A clean end happens exactly at a frame boundary after the final deflate block.
			// "Unexpected end of ZLIB input stream" means the recording was never closed.
			finished = true;
			String message = e.getMessage();
			truncated = midFrame || (message != null && message.contains("ZLIB"));
			return false;
		} catch (ZipException e) {
			// The deflate stream ended abruptly: the recording was not closed properly.
			finished = true;
			truncated = true;
			return false;
		}
	}

	public int time() {
		return time;
	}

	public int length() {
		return length;
	}

	public byte[] data() {
		return buffer;
	}

	/** True if the stream ended in the middle of data, which happens for crashed recordings. */
	public boolean wasTruncated() {
		return truncated;
	}

	@Override
	public void close() throws IOException {
		in.close();
	}
}
