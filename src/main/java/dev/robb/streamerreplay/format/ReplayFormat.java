package dev.robb.streamerreplay.format;

import java.nio.charset.StandardCharsets;

/**
 * Constants describing the on-disk replay format.
 *
 * <p>A finished replay is a regular zip archive with the extension {@value #EXTENSION}:
 * <ul>
 *     <li>{@value #ENTRY_META}: {@link ReplayMeta} as JSON</li>
 *     <li>{@value #ENTRY_PACKETS}: the packet stream (stored, already deflate-compressed, see below)</li>
 *     <li>{@value #ENTRY_MARKERS}: user markers as JSON</li>
 *     <li>{@value #ENTRY_THUMBNAIL}: optional PNG preview image</li>
 *     <li>{@value #ENTRY_PATHS}: camera path as JSON (only in replays from 1.1.x; newer versions keep the path in a
 *     {@value #PATHS_SIDECAR_EXTENSION} file next to the replay, so the large archive is never rewritten)</li>
 * </ul>
 *
 * <p>The packet stream starts with {@link #STREAM_MAGIC} followed by a zlib (deflate) stream of frames.
 * Every frame is {@code int32 timeMillis, int32 length, byte[length] packet}, big endian, where the packet
 * bytes are exactly what the vanilla network decoder receives (packet id varint + payload, decompressed).
 * While recording, the stream is written to a {@code .rec} file next to the final archive and periodically
 * sync-flushed so a crash loses at most about a second of footage.
 */
public final class ReplayFormat {
	public static final String EXTENSION = ".sreplay";
	public static final String RECORDING_EXTENSION = ".sreplay.rec";
	public static final String MARKERS_SIDECAR_EXTENSION = ".sreplay.markers";
	public static final String THUMB_SIDECAR_EXTENSION = ".sreplay.thumb";
	public static final String META_SIDECAR_EXTENSION = ".sreplay.meta";
	/** An archive while it is being written; renamed to {@link #EXTENSION} when complete. */
	public static final String PARTIAL_EXTENSION = ".sreplay.tmp";
	/** Appended to the full replay file name: {@code name.sreplay.paths}. */
	public static final String PATHS_SIDECAR_EXTENSION = ".paths";

	public static final String ENTRY_META = "meta.json";
	public static final String ENTRY_PACKETS = "packets.z";
	public static final String ENTRY_MARKERS = "markers.json";
	public static final String ENTRY_THUMBNAIL = "thumb.png";
	public static final String ENTRY_PATHS = "paths.json";

	public static final int FORMAT_VERSION = 1;
	public static final byte[] STREAM_MAGIC = "SRPKT1\n".getBytes(StandardCharsets.US_ASCII);

	/** Upper bound for a single frame; anything bigger is treated as corruption. */
	public static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

	private ReplayFormat() {
	}
}
