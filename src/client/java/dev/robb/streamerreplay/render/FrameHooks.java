package dev.robb.streamerreplay.render;

import com.mojang.blaze3d.pipeline.RenderTarget;

import java.util.function.Consumer;

/**
 * Callback invoked right after the world (including post effects) was rendered into the main target and before
 * the GUI is drawn on top. Grabbing pixels here yields clean frames without HUD or menus.
 */
public final class FrameHooks {
	private static Consumer<RenderTarget> pendingOneShot;
	private static Consumer<RenderTarget> continuous;

	private FrameHooks() {
	}

	/** Runs the consumer once on the next rendered world frame. */
	public static void requestOnce(Consumer<RenderTarget> consumer) {
		pendingOneShot = consumer;
	}

	/** Sets (or clears with {@code null}) a consumer that runs on every world frame. */
	public static void setContinuous(Consumer<RenderTarget> consumer) {
		continuous = consumer;
	}

	/** Called from the GameRenderer mixin on the render thread. */
	public static void onWorldRendered(RenderTarget target) {
		Consumer<RenderTarget> once = pendingOneShot;
		if (once != null) {
			pendingOneShot = null;
			once.accept(target);
		}
		Consumer<RenderTarget> every = continuous;
		if (every != null) {
			every.accept(target);
		}
	}
}
