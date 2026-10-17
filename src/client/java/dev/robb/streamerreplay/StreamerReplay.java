package dev.robb.streamerreplay;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared constants and paths. */
public final class StreamerReplay {
	public static final String MOD_ID = "streamerreplay";
	public static final Logger LOGGER = LoggerFactory.getLogger("Streamer Replay");

	private StreamerReplay() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	/** {@code .minecraft/replays}, created on demand. */
	public static Path replayFolder() {
		return folder("replays");
	}

	/** {@code .minecraft/replay_videos}, created on demand. */
	public static Path videoFolder() {
		return folder("replay_videos");
	}

	private static Path folder(String name) {
		Path dir = FabricLoader.getInstance().getGameDir().resolve(name);
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			LOGGER.warn("Could not create folder {}", dir, e);
		}
		return dir;
	}
}
