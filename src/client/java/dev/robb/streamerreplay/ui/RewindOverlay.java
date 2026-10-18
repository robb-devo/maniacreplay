package dev.robb.streamerreplay.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.render.FrameHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Rewinding has to rebuild the world from the start (see {@code ReplaySession}). Instead of flashing loading
 * screens, the last picture before the jump stays on screen with a small "rewinding" badge until the world at the
 * new time is complete, like a video player buffering. Render thread only.
 */
public final class RewindOverlay {
	private static final Identifier TEXTURE = StreamerReplay.id("rewind_frame");
	/** How long to wait for the picture before rewinding without it. */
	private static final long CAPTURE_TIMEOUT_MS = 150;
	/** Safety net: never hide a stuck loading screen (and its Cancel button) for longer than this. */
	private static final long MAX_SHOWN_MS = 6000;

	private static boolean requested;
	private static long requestedAt;
	private static boolean hasImage;
	private static int imageWidth;
	private static int imageHeight;
	private static long shownAt = -1;

	private RewindOverlay() {
	}

	/**
	 * Called every frame while a rewind is due. Asks for a picture of the next world frame and returns true once it
	 * arrived (or did not arrive in time), i.e. when the world may be torn down.
	 */
	public static boolean prepare() {
		long now = System.currentTimeMillis();
		if (!requested) {
			requested = true;
			requestedAt = now;
			FrameHooks.requestOnce(target -> Screenshot.takeScreenshot(target, image -> Minecraft.getInstance().execute(() -> store(image))));
			return false;
		}
		if (hasImage) {
			shownAt = now;
			return true;
		}
		if (now - requestedAt > CAPTURE_TIMEOUT_MS) {
			clear(); // rewind without the picture; a late one is dropped in store()
			return true;
		}
		return false;
	}

	private static void store(NativeImage image) {
		if (!requested) {
			image.close(); // cleared in the meantime
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.getTextureManager().release(TEXTURE);
		imageWidth = image.getWidth();
		imageHeight = image.getHeight();
		mc.getTextureManager().register(TEXTURE, new DynamicTexture(() -> "ManiacReplay rewind frame", image));
		hasImage = true;
	}

	public static boolean isShown() {
		if (shownAt < 0) {
			return false;
		}
		if (System.currentTimeMillis() - shownAt > MAX_SHOWN_MS) {
			clear();
			return false;
		}
		return true;
	}

	/** The world at the new time is complete: back to the live picture. */
	public static void clear() {
		if (hasImage) {
			Minecraft.getInstance().getTextureManager().release(TEXTURE);
		}
		requested = false;
		hasImage = false;
		shownAt = -1;
	}

	/** Draws the frozen picture over the whole screen with a small badge. {@code progress} is 0..1. */
	public static void draw(GuiGraphicsExtractor g, int width, int height, float progress) {
		if (!isShown()) {
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, 0, 0, width, height, imageWidth, imageHeight, imageWidth, imageHeight);
		g.fill(0, 0, width, height, 0x30000000);
		Font font = Minecraft.getInstance().font;
		Component label = Component.translatable("streamerreplay.hud.rewinding");
		int w = font.width(label) + 24;
		int x0 = width / 2 - w / 2;
		int y0 = height / 2 - 12;
		Theme.panel(g, x0, y0, x0 + w, y0 + 24);
		g.centeredText(font, label, width / 2, y0 + 5, Theme.TEXT);
		Theme.progressBar(g, x0 + 8, y0 + 17, w - 16, 2, progress, Theme.TRACK_PLAYED);
	}
}
