package dev.robb.streamerreplay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.robb.streamerreplay.StreamerReplay;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** User settings, persisted as {@code config/streamerreplay.json}. Access via {@link #get()}. */
public final class ReplayConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ReplayConfig instance = new ReplayConfig();

	// Recording
	public boolean recordSingleplayer = true;
	public boolean recordMultiplayer = true;
	public boolean showRecordingIndicator = true;
	/** Server IPs are hidden by default so a replay file can be shared or shown on stream safely. */
	public boolean storeServerAddress = false;

	// Replay viewer
	public boolean showControlsHint = true;
	public float cameraSmoothing = 0.6F;

	// Video export
	public String ffmpegPath = "ffmpeg";
	public int exportFps = 60;
	public ExportQuality exportQuality = ExportQuality.HIGH;
	public ExportResolution exportResolution = ExportResolution.WINDOW;
	public boolean exportCleanFrames = true;

	public static ReplayConfig get() {
		return instance;
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(StreamerReplay.MOD_ID + ".json");
	}

	public static void load() {
		Path file = file();
		if (!Files.exists(file)) {
			instance = new ReplayConfig();
			instance.save();
			return;
		}
		try {
			ReplayConfig loaded = GSON.fromJson(Files.readString(file), ReplayConfig.class);
			instance = loaded != null ? loaded.sanitized() : new ReplayConfig();
		} catch (IOException | JsonParseException e) {
			StreamerReplay.LOGGER.warn("Could not read {}, using defaults", file, e);
			instance = new ReplayConfig();
		}
	}

	private ReplayConfig sanitized() {
		if (ffmpegPath == null || ffmpegPath.isBlank()) {
			ffmpegPath = "ffmpeg";
		}
		if (exportFps != 24 && exportFps != 30 && exportFps != 60) {
			exportFps = 60;
		}
		if (exportQuality == null) {
			exportQuality = ExportQuality.HIGH;
		}
		if (exportResolution == null) {
			exportResolution = ExportResolution.WINDOW;
		}
		cameraSmoothing = Math.max(0, Math.min(0.95F, cameraSmoothing));
		return this;
	}

	public void save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(this));
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			StreamerReplay.LOGGER.warn("Could not save {}", file, e);
		}
	}

	/** x264 presets: trade encode speed for file size at visually lossless quality. */
	public enum ExportQuality {
		FAST(23, "veryfast"),
		BALANCED(20, "medium"),
		HIGH(17, "slow");

		public final int crf;
		public final String preset;

		ExportQuality(int crf, String preset) {
			this.crf = crf;
			this.preset = preset;
		}
	}

	/** Output resolution. The game renders natively at this size, independent of the window. */
	public enum ExportResolution {
		WINDOW(0, 0),
		HD_720(1280, 720),
		FULL_HD_1080(1920, 1080),
		QHD_1440(2560, 1440),
		UHD_2160(3840, 2160);

		public final int width;
		public final int height;

		ExportResolution(int width, int height) {
			this.width = width;
			this.height = height;
		}
	}
}
