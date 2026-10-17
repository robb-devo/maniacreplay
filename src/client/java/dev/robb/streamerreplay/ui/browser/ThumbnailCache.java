package dev.robb.streamerreplay.ui.browser;

import com.mojang.blaze3d.platform.NativeImage;
import dev.robb.streamerreplay.StreamerReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** GPU textures for replay thumbnails, owned by one browser screen and released when it closes. */
final class ThumbnailCache implements AutoCloseable {
	private final Map<String, Identifier> textures = new HashMap<>();
	private final List<Identifier> owned = new ArrayList<>();
	private int counter;

	/** Uploads on first use; returns null if the image is missing or broken. Render thread. */
	@Nullable Identifier get(String key, byte @Nullable [] png) {
		if (png == null) {
			return null;
		}
		if (textures.containsKey(key)) {
			return textures.get(key);
		}
		Identifier id = null;
		try {
			NativeImage image = NativeImage.read(png);
			id = StreamerReplay.id("thumbnail/" + (counter++));
			String label = "Replay thumbnail " + key;
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> label, image));
			owned.add(id);
		} catch (IOException | RuntimeException e) {
			StreamerReplay.LOGGER.debug("Broken thumbnail for {}", key, e);
			id = null;
		}
		textures.put(key, id);
		return id;
	}

	@Override
	public void close() {
		for (Identifier id : owned) {
			Minecraft.getInstance().getTextureManager().release(id);
		}
		owned.clear();
		textures.clear();
	}
}
