package dev.robb.streamerreplay.record;

import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.format.Marker;
import dev.robb.streamerreplay.format.ReplayArchive;
import dev.robb.streamerreplay.format.ReplayFormat;
import dev.robb.streamerreplay.format.ReplayMeta;
import dev.robb.streamerreplay.library.ReplayLibrary;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.ui.Notifications;
import dev.robb.streamerreplay.util.TimeFormat;
import io.netty.channel.Channel;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the (at most one) active {@link Recorder}: starts it when a connection finishes logging in, ticks it,
 * and saves it in the background when the connection ends or the user stops it.
 */
public final class RecordingManager {
	private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final long THUMBNAIL_DELAY_MS = 4000;
	/** Single thread so saves happen in order and never compete for disk bandwidth. */
	private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "StreamerReplay-Saver");
		thread.setDaemon(true);
		return thread;
	});

	private static volatile @Nullable Recorder active;

	private RecordingManager() {
	}

	public static @Nullable Recorder active() {
		return active;
	}

	public static boolean isRecording() {
		Recorder recorder = active;
		return recorder != null && !recorder.isStopped();
	}

	/** Called on the netty thread when the server accepted our login (see mixin). */
	public static void onLoginFinished(Connection connection, ClientboundLoginFinishedPacket packet, @Nullable ServerData serverData, @Nullable Channel channel) {
		if (channel == null || ReplayManager.isReplayChannel(channel)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		boolean singleplayer = connection.isMemoryConnection();
		ReplayConfig config = ReplayConfig.get();
		if (singleplayer ? !config.recordSingleplayer : !config.recordMultiplayer) {
			return;
		}
		if (!(channel.pipeline().get(RecordingTap.NAME) instanceof RecordingTap tap)) {
			StreamerReplay.LOGGER.warn("Recording tap missing from pipeline, cannot record this session");
			return;
		}
		Recorder previous = active;
		if (previous != null) {
			stop(previous); // e.g. a server transfer: close the old session first
		}
		ReplayMeta meta = createMeta(mc, packet, serverData, singleplayer, config);
		String base = FILE_DATE.format(LocalDateTime.now()) + " " + sanitize(meta.worldName);
		Path folder = StreamerReplay.replayFolder();
		Path target = ReplayArchive.uniqueSibling(folder.resolve("x"), base.strip(), ReplayFormat.EXTENSION);
		String targetName = target.getFileName().toString();
		Path recording = folder.resolve(targetName.substring(0, targetName.length() - ReplayFormat.EXTENSION.length()) + ReplayFormat.RECORDING_EXTENSION);
		try {
			Recorder recorder = new Recorder(channel, tap, meta, recording, target);
			recorder.writeLoginFinished(packet);
			recorder.attach();
			active = recorder;
			StreamerReplay.LOGGER.info("Recording started: {}", recording.getFileName());
			if (config.showRecordingIndicator) {
				Notifications.recording(Component.translatable("streamerreplay.toast.recording_started"), Component.literal(meta.worldName));
			}
		} catch (IOException e) {
			StreamerReplay.LOGGER.error("Could not start recording", e);
			Notifications.recording(Component.translatable("streamerreplay.toast.recording_failed"), Component.literal(String.valueOf(e.getMessage())));
		}
	}

	private static ReplayMeta createMeta(Minecraft mc, ClientboundLoginFinishedPacket packet, @Nullable ServerData serverData, boolean singleplayer, ReplayConfig config) {
		ReplayMeta meta = new ReplayMeta();
		meta.modVersion = StreamerReplay.version();
		meta.minecraftVersion = SharedConstants.getCurrentVersion().name();
		meta.protocolVersion = SharedConstants.getCurrentVersion().protocolVersion();
		meta.recordedAt = System.currentTimeMillis();
		meta.singleplayer = singleplayer;
		meta.playerName = packet.gameProfile().name();
		meta.playerUuid = packet.gameProfile().id().toString();
		IntegratedServer server = mc.getSingleplayerServer();
		if (singleplayer && server != null) {
			meta.worldName = server.getWorldData().getLevelName();
		} else if (serverData != null) {
			meta.worldName = serverData.name;
			if (config.storeServerAddress) {
				meta.serverAddress = serverData.ip;
			}
		} else {
			meta.worldName = singleplayer ? "Singleplayer" : "Multiplayer";
		}
		return meta;
	}

	private static String sanitize(String name) {
		String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
		if (cleaned.length() > 48) {
			cleaned = cleaned.substring(0, 48).strip();
		}
		return cleaned.isEmpty() ? "Replay" : cleaned;
	}

	/** Render thread, end of every client tick. */
	public static void tick(Minecraft mc) {
		Recorder recorder = active;
		if (recorder == null || recorder.isStopped()) {
			return;
		}
		if (recorder.failure() != null) {
			StreamerReplay.LOGGER.error("Recording failed", recorder.failure());
			Notifications.recording(Component.translatable("streamerreplay.toast.recording_failed"), Component.literal(String.valueOf(recorder.failure().getMessage())));
			stop(recorder);
			return;
		}
		recorder.tick(mc.isPaused());
		if (recorder.elapsedMs() > THUMBNAIL_DELAY_MS && mc.level != null && mc.gui.screen() == null && recorder.isPlayPhase()
			&& recorder.claimThumbnailRequest()) {
			ThumbnailCapture.request(png -> {
				if (!recorder.isStopped()) {
					recorder.setThumbnail(png);
				}
			});
		}
	}

	/** Stops the active recording (if any) and saves it in the background. */
	public static void stopActive() {
		Recorder recorder = active;
		if (recorder != null) {
			stop(recorder);
		}
	}

	static void stop(Recorder recorder) {
		synchronized (RecordingManager.class) {
			if (recorder.isStopped() || active != recorder) {
				return;
			}
			active = null;
		}
		SAVER.execute(() -> {
			try {
				Path saved = recorder.finish();
				if (saved != null) {
					StreamerReplay.LOGGER.info("Replay saved: {}", saved);
					Notifications.recording(Component.translatable("streamerreplay.toast.replay_saved"), Component.literal(ReplayLibrary.displayName(saved)));
				} else {
					StreamerReplay.LOGGER.info("Discarded empty recording {}", recorder.recordingFile().getFileName());
				}
			} catch (IOException e) {
				StreamerReplay.LOGGER.error("Could not save replay", e);
				Notifications.recording(Component.translatable("streamerreplay.toast.save_failed"), Component.literal(String.valueOf(e.getMessage())));
			}
		});
	}

	/** Adds a marker at the current recording time. */
	public static void addMarker() {
		Recorder recorder = active;
		if (recorder == null || recorder.isStopped()) {
			Notifications.recording(Component.translatable("streamerreplay.toast.not_recording"), null);
			return;
		}
		Marker marker = recorder.addMarker(Component.translatable("streamerreplay.marker.default", recorder.markerCount() + 1).getString());
		Notifications.recording(Component.translatable("streamerreplay.toast.marker_added"), Component.literal(TimeFormat.clock(marker.timeMs())));
	}

	/** Game is closing: finish the active recording and wait (bounded) for it to be saved. */
	public static void shutdown() {
		stopActive();
		SAVER.shutdown();
		try {
			if (!SAVER.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) {
				StreamerReplay.LOGGER.warn("Replay is still being saved; it will be recovered on next start if incomplete");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The {@code .rec} file currently being written, or null. */
	public static Path activeRecordingFile() {
		Recorder recorder = active;
		return recorder == null ? null : recorder.recordingFile();
	}

	/** True while the saver thread still has work queued (used to delay recovery scans). */
	public static ExecutorService saver() {
		return SAVER;
	}
}
