package dev.robb.streamerreplay.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.robb.streamerreplay.StreamerReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Renders the game at a fixed resolution that does not depend on the window (used for video export).
 *
 * <p>While active, the game is told its framebuffer has the export size, so the world, post effects, shader
 * packs and the GUI all render at that size into the normal main target. Only the very last step changes:
 * the finished image is scaled down to the real window before it is shown. Mouse coordinates stay consistent
 * because vanilla maps them through the same (virtual) GUI size. Resizing the window during an export no longer
 * matters: the real size is only used for that last scaling step.
 */
public final class RenderResolution {
	private static int width;
	private static int height;
	private static @Nullable TextureTarget presentTarget;

	private RenderResolution() {
	}

	public static boolean isActive() {
		return width > 0;
	}

	public static int width() {
		return width;
	}

	public static int height() {
		return height;
	}

	/** Largest size the GPU can render to; requests above it are clamped (ffmpeg then scales up). */
	public static int maxSize() {
		return RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
	}

	/** Starts rendering at {@code w × h}. Render thread. */
	public static void begin(int w, int h) {
		RenderSystem.assertOnRenderThread();
		width = w;
		height = h;
		StreamerReplay.LOGGER.info("Rendering at {}x{} independent of the window", w, h);
		apply(Minecraft.getInstance());
	}

	/** Returns to normal rendering at window size. Render thread; safe to call when not active. */
	public static void end() {
		if (!isActive()) {
			return;
		}
		RenderSystem.assertOnRenderThread();
		width = 0;
		height = 0;
		Minecraft mc = Minecraft.getInstance();
		Window window = mc.getWindow();
		Window.FramebufferSize real = window.queryFramebufferSize();
		window.setWidth(Math.max(1, real.width()));
		window.setHeight(Math.max(1, real.height()));
		mc.resizeGui();
		if (presentTarget != null) {
			presentTarget.destroyBuffers();
			presentTarget = null;
		}
	}

	/**
	 * From the {@code Minecraft.resizeGui} mixin: vanilla wants to re-layout (window resized, GUI scale changed).
	 * Returns true if this class handled it by re-applying the virtual size.
	 */
	public static boolean onResizeGui(Minecraft mc) {
		if (!isActive()) {
			return false;
		}
		apply(mc);
		return true;
	}

	private static void apply(Minecraft mc) {
		Window window = mc.getWindow();
		Window.FramebufferSize real = window.queryFramebufferSize();
		int realWidth = Math.max(1, real.width());
		int realHeight = Math.max(1, real.height());
		// the GUI scale the player would get for the real window, then enlarged by the same factor as the image
		window.setWidth(realWidth);
		window.setHeight(realHeight);
		int base = window.calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
		window.setWidth(width);
		window.setHeight(height);
		double factor = Math.min(width / (double) realWidth, height / (double) realHeight);
		window.setGuiScale(Math.max(1, (int) Math.floor(base * factor + 1e-6)));
		if (mc.gui.screen() != null) {
			mc.gui.screen().resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
		}
		mc.mouseHandler.resyncMousePosition();
		mc.mouseHandler.setIgnoreFirstMove();
	}

	/**
	 * From the {@code Minecraft.renderFrame} mixin, right before the frame goes to the window: scales the
	 * export-sized image to the real window size.
	 */
	public static GpuTextureView presentView(GpuTextureView rendered) {
		if (!isActive()) {
			return rendered;
		}
		Window.FramebufferSize real = Minecraft.getInstance().getWindow().queryFramebufferSize();
		int w = Math.max(1, real.width());
		int h = Math.max(1, real.height());
		if (presentTarget == null) {
			presentTarget = new TextureTarget("Streamer Replay window preview", w, h, GpuFormat.RGBA8_UNORM, null);
		} else if (presentTarget.width != w || presentTarget.height != h) {
			presentTarget.resize(w, h);
		}
		GpuTextureView output = presentTarget.getColorTextureView();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Streamer Replay preview scale", output, Optional.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			pass.setUniform("InSampler", rendered, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
		return output;
	}
}
