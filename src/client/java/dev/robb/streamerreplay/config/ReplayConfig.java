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
import java.util.List;

/** User settings, persisted as {@code config/streamerreplay.json}. Access via {@link #get()}. */
public final class ReplayConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ReplayConfig instance = new ReplayConfig();

	/** Choices for {@link #cleanupDays}; 0 = keep everything. */
	public static final List<Integer> CLEANUP_CHOICES = List.of(0, 7, 14, 30, 90);
	/** Written with every save; lower values come from older versions and are upgraded on load. */
	private static final int CURRENT_VERSION = 2;

	public int configVersion;

	// Recording
	/** The one switch players care about: record automatically when joining a world (also in the pause menu). */
	public boolean recordingEnabled = true;
	public boolean recordSingleplayer = true;
	public boolean recordMultiplayer = true;
	public boolean showRecordingIndicator = true;
	/** Server IPs are hidden by default so a replay file can be shared or shown on stream safely. */
	public boolean storeServerAddress = false;
	/** Delete untouched replays (no markers, not renamed) after this many days; 0 = never, the default. */
	public int cleanupDays;

	// Replay viewer
	public boolean showControlsHint = true;
	public float cameraSmoothing = 0.6F;
	/** Recorded chat (including private messages) stays hidden in replays unless turned on. */
	public boolean showChatInReplay = false;

	// Video export
	public String ffmpegPath = "ffmpeg";
	public int exportFps = 60;
	public ExportQuality exportQuality = ExportQuality.BALANCED;
	public ExportResolution exportResolution = ExportResolution.FULL_HD_1080;
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
			if (loaded == null) {
				instance = new ReplayConfig();
				return;
			}
			boolean outdated = loaded.configVersion < CURRENT_VERSION;
			instance = loaded.upgraded().sanitized();
			if (outdated) {
				instance.save();
			}
		} catch (IOException | JsonParseException e) {
			StreamerReplay.LOGGER.warn("Could not read {}, using defaults", file, e);
			instance = new ReplayConfig();
		}
	}

	/** Applies default changes to a config written by an older version. */
	private ReplayConfig upgraded() {
		if (configVersion < 2 && cleanupDays == 30) {
			cleanupDays = 0; // 1.2.0 switched the clean-up on for new installs; it deletes recordings, so it is opt-in now
		}
		return this;
	}

	private ReplayConfig sanitized() {
		if (ffmpegPath == null || ffmpegPath.isBlank()) {
			ffmpegPath = "ffmpeg";
		}
		if (exportFps != 24 && exportFps != 30 && exportFps != 60) {
			exportFps = 60;
		}
		if (exportQuality == null) {
			exportQuality = ExportQuality.BALANCED;
		}
		if (exportResolution == null) {
			exportResolution = ExportResolution.FULL_HD_1080;
		}
		if (!CLEANUP_CHOICES.contains(cleanupDays)) {
			cleanupDays = 0;
		}
		cameraSmoothing = Math.max(0, Math.min(0.95F, cameraSmoothing));
		return this;
	}

	public void save() {
		configVersion = CURRENT_VERSION;
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
		UHD_2160(3840, 2160),
		/** 9:16 for Shorts, TikTok and Reels. */
		VERTICAL_1080(1080, 1920);

		public final int width;
		public final int height;

		ExportResolution(int width, int height) {
			this.width = width;
			this.height = height;
		}

		public boolean isVertical() {
			return height > width;
		}
	}
}
