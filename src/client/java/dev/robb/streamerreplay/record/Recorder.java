package dev.robb.streamerreplay.record;

import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.format.Marker;
import dev.robb.streamerreplay.format.PacketStreamWriter;
import dev.robb.streamerreplay.format.ReplayArchive;
import dev.robb.streamerreplay.format.ReplayFormat;
import dev.robb.streamerreplay.format.ReplayMeta;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginProtocols;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One recording session attached to one client connection.
 *
 * <p>Thread model: {@link #onInboundFrame} runs on the netty thread; everything else on the render thread.
 * Synthetic packets (our own player, which the server never sends to us) are encoded on the render thread
 * and appended from the channel's event loop so they are correctly ordered relative to protocol switches.
 */
public final class Recorder {
	private static final long FLUSH_INTERVAL_NANOS = 1_000_000_000L;
	private static final int MIN_USEFUL_DURATION_MS = 2000;

	private final Channel channel;
	private final RecordingTap tap;
	private final PacketStreamWriter writer;
	private final RecordingClock clock = new RecordingClock();
	private final ReplayMeta meta;
	private final Path recordingFile;
	private final Path targetFile;
	private final SelfPlayerRecorder selfPlayer = new SelfPlayerRecorder(this);
	private final List<Marker> markers = new ArrayList<>();
	private volatile boolean reachedPlay;
	private volatile boolean stopped;
	private long lastFlush = System.nanoTime();
	private volatile byte[] thumbnail; // set from the screenshot worker thread
	private boolean thumbnailRequested;

	Recorder(Channel channel, RecordingTap tap, ReplayMeta meta, Path recordingFile, Path targetFile) throws IOException {
		this.channel = channel;
		this.tap = tap;
		this.meta = meta;
		this.recordingFile = recordingFile;
		this.targetFile = targetFile;
		this.writer = new PacketStreamWriter(recordingFile);
		writeSidecar(ReplayFormat.META_SIDECAR_EXTENSION, ReplayArchive.toJson(meta));
	}

	/** Writes the login-finished packet as the very first frame; replays start from this point. */
	void writeLoginFinished(ClientboundLoginFinishedPacket packet) {
		ByteBuf buf = Unpooled.buffer(256);
		try {
			LoginProtocols.CLIENTBOUND.codec().encode(buf, packet);
			writer.write(0, buf);
		} finally {
			buf.release();
		}
	}

	void attach() {
		tap.attach(this);
	}

	/** Netty thread: copies a raw inbound frame if we are past the login phase. */
	void onInboundFrame(ByteBuf frame) {
		ProtocolInfo<?> protocol = tap.inboundProtocol();
		if (protocol == null) {
			return;
		}
		ConnectionProtocol id = protocol.id();
		if (id == ConnectionProtocol.PLAY) {
			reachedPlay = true;
			writer.write(clock.now(), frame);
		} else if (id == ConnectionProtocol.CONFIGURATION) {
			writer.write(clock.now(), frame);
		}
	}

	void onConnectionClosed() {
		RecordingManager.stop(this);
	}

	/** True once the inbound side is in the play phase, i.e. synthetic game packets may be written. */
	boolean isPlayPhase() {
		ProtocolInfo<?> protocol = tap.inboundProtocol();
		return protocol != null && protocol.id() == ConnectionProtocol.PLAY;
	}

	/** Render thread: encodes a packet with the current play codec and appends it in stream order. */
	@SuppressWarnings("unchecked")
	void writeSynthetic(Packet<ClientGamePacketListener> packet) {
		ProtocolInfo<?> protocol = tap.inboundProtocol();
		if (stopped || protocol == null || protocol.id() != ConnectionProtocol.PLAY) {
			return;
		}
		ByteBuf buf = Unpooled.buffer(64);
		byte[] bytes;
		try {
			((ProtocolInfo<ClientGamePacketListener>) protocol).codec().encode(buf, packet);
			bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
		} catch (RuntimeException e) {
			StreamerReplay.LOGGER.debug("Could not encode synthetic packet {}", packet, e);
			return;
		} finally {
			buf.release();
		}
		channel.eventLoop().execute(() -> {
			// Drop it if the protocol changed in between or a switch is pending (decoder replaced by inbound_config).
			if (!stopped && tap.inboundProtocol() == protocol && channel.pipeline().get("inbound_config") == null) {
				writer.write(clock.now(), bytes, 0, bytes.length);
			}
		});
	}

	/** Render thread, once per client tick. */
	void tick(boolean gamePaused) {
		clock.setPaused(gamePaused);
		selfPlayer.tick();
		long now = System.nanoTime();
		if (now - lastFlush > FLUSH_INTERVAL_NANOS) {
			lastFlush = now;
			writer.flush();
		}
	}

	IOException failure() {
		return writer.failure();
	}

	public int elapsedMs() {
		return clock.now();
	}

	/** Returns true exactly once, the first time a thumbnail should be taken. Render thread. */
	boolean claimThumbnailRequest() {
		if (thumbnailRequested) {
			return false;
		}
		thumbnailRequested = true;
		return true;
	}

	void setThumbnail(byte[] png) {
		this.thumbnail = png;
		writeSidecar(ReplayFormat.THUMB_SIDECAR_EXTENSION, png);
	}

	Marker addMarker(String name) {
		Marker marker = new Marker(clock.now(), name);
		byte[] json;
		synchronized (markers) {
			markers.add(marker);
			json = ReplayArchive.toJson(markers);
		}
		// Disk I/O off the render thread. The saver is single-threaded, so this always lands before finish().
		RecordingManager.saver().execute(() -> {
			if (!stopped) {
				writeSidecar(ReplayFormat.MARKERS_SIDECAR_EXTENSION, json);
			}
		});
		return marker;
	}

	public int markerCount() {
		synchronized (markers) {
			return markers.size();
		}
	}

	boolean isStopped() {
		return stopped;
	}

	Path recordingFile() {
		return recordingFile;
	}

	private void writeSidecar(String extension, byte[] data) {
		try {
			Files.write(sidecar(recordingFile, extension), data);
		} catch (IOException e) {
			StreamerReplay.LOGGER.debug("Could not write sidecar {}", extension, e);
		}
	}

	/** Sidecar files live next to the {@code .rec} file and carry what is needed for crash recovery. */
	static Path sidecar(Path recordingFile, String extension) {
		String name = recordingFile.getFileName().toString();
		String base = name.substring(0, name.length() - ReplayFormat.RECORDING_EXTENSION.length());
		return recordingFile.resolveSibling(base + extension);
	}

	/**
	 * Detaches from the connection and packages the replay. Blocking I/O; call from a background thread.
	 *
	 * @return the saved replay or {@code null} if the session was too short to be useful and got discarded
	 */
	Path finish() throws IOException {
		stopped = true;
		tap.attach(null);
		int duration = clock.now();
		try {
			writer.close();
		} finally {
			meta.durationMs = Math.min(duration, writer.lastTime());
			meta.packetCount = writer.frameCount();
		}
		if (!reachedPlay || meta.durationMs < MIN_USEFUL_DURATION_MS) {
			deleteAll();
			return null;
		}
		List<Marker> markerCopy;
		synchronized (markers) {
			markerCopy = new ArrayList<>(markers);
		}
		ReplayArchive.finish(recordingFile, targetFile, meta, markerCopy, thumbnail);
		deleteSidecars();
		return targetFile;
	}

	private void deleteAll() throws IOException {
		Files.deleteIfExists(recordingFile);
		deleteSidecars();
	}

	private void deleteSidecars() throws IOException {
		Files.deleteIfExists(sidecar(recordingFile, ReplayFormat.META_SIDECAR_EXTENSION));
		Files.deleteIfExists(sidecar(recordingFile, ReplayFormat.MARKERS_SIDECAR_EXTENSION));
		Files.deleteIfExists(sidecar(recordingFile, ReplayFormat.THUMB_SIDECAR_EXTENSION));
	}
}
