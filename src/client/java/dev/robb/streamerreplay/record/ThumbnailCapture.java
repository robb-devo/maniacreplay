package dev.robb.streamerreplay.record;

import com.mojang.blaze3d.platform.NativeImage;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.render.FrameHooks;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Grabs one clean (HUD-free) world frame and stores it as a 16:9 PNG preview. */
public final class ThumbnailCapture {
	public static final int WIDTH = 384;
	public static final int HEIGHT = 216;

	private ThumbnailCapture() {
	}

	public static void request(Consumer<byte[]> onPng) {
		FrameHooks.requestOnce(target -> Screenshot.takeScreenshot(target, image -> Util.ioPool().execute(() -> {
			try {
				onPng.accept(toPng(image));
			} catch (IOException e) {
				StreamerReplay.LOGGER.debug("Thumbnail capture failed", e);
			} finally {
				image.close();
			}
		})));
	}

	private static byte[] toPng(NativeImage image) throws IOException {
		int width = image.getWidth();
		int height = image.getHeight();
		int cropWidth = width;
		int cropHeight = width * HEIGHT / WIDTH;
		if (cropHeight > height) {
			cropHeight = height;
			cropWidth = height * WIDTH / HEIGHT;
		}
		int x = (width - cropWidth) / 2;
		int y = (height - cropHeight) / 2;
		Path temp = Files.createTempFile("streamerreplay-thumb", ".png");
		try (NativeImage scaled = new NativeImage(WIDTH, HEIGHT, false)) {
			image.resizeSubRectTo(x, y, cropWidth, cropHeight, scaled);
			scaled.writeToFile(temp);
			return Files.readAllBytes(temp);
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
