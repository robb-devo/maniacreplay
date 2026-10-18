package dev.robb.streamerreplay.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.robb.streamerreplay.StreamerReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Renders the game at a fixed resolution that does not depend on the window (used for video export).
 *
 * <p>While active, the game is told its framebuffer has the export size, so the world, post effects, shader
 * packs and the GUI all render at that size into the normal main target. Only the very last step changes:
 * the finished image is scaled to the real window before it is shown, letterboxed when the shapes differ (a 9:16
 * export in a 16:9 window shows upright with black bars, not squashed). Mouse coordinates stay consistent
 * because vanilla maps them through the same (virtual) GUI size. Resizing the window during an export no longer
 * matters: the real size is only used for that last scaling step.
 */
public final class RenderResolution {
	private static int width;
	private static int height;
	private static @Nullable TextureTarget presentTarget;
	/** The image scaled to fit the window with the export's aspect ratio, before it is centred on black. */
	private static @Nullable TextureTarget fitTarget;
	private static final Vector4f BLACK = new Vector4f(0, 0, 0, 1);
	/** The graphics backend never changes while the game runs (switching needs a restart). */
	private static @Nullable Boolean vulkan;

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
		if (fitTarget != null) {
			fitTarget.destroyBuffers();
			fitTarget = null;
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
		presentTarget = sized(presentTarget, "ManiacReplay window preview", w, h);
		Rect fit = fit(width, height, w, h);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (fit.width() == w && fit.height() == h) {
			scaleInto(encoder, rendered, presentTarget, null); // same shape as the window: one scaling pass
			return presentTarget.getColorTextureView();
		}
		encoder.clearColorTexture(presentTarget.getColorTexture(), BLACK);
		if (isVulkan()) {
			// Vulkan keeps the full-texture viewport for a render area, but copies with an offset exactly
			fitTarget = sized(fitTarget, "ManiacReplay window preview (fit)", fit.width(), fit.height());
			scaleInto(encoder, rendered, fitTarget, null);
			encoder.copyTextureToTexture(fitTarget.getColorTexture(), presentTarget.getColorTexture(), 0, fit.x(), fit.y(), 0, 0, fit.width(), fit.height());
		} else {
			// OpenGL turns the render area into the viewport, so the image is scaled straight into the rectangle. Its
			// copyTextureToTexture would not do: it treats width and height as end coordinates and squeezes the image.
			scaleInto(encoder, rendered, presentTarget, new RenderPass.RenderArea(fit.x(), fit.y(), fit.width(), fit.height()));
		}
		return presentTarget.getColorTextureView();
	}

	/** Where a {@code width × height} image goes in a {@code w × h} window: as large as fits, centred. */
	public static Rect fit(int width, int height, int w, int h) {
		double scale = Math.min(w / (double) width, h / (double) height);
		int fitW = Math.min(w, Math.max(1, (int) Math.round(width * scale)));
		int fitH = Math.min(h, Math.max(1, (int) Math.round(height * scale)));
		if (Math.abs(fitW - w) <= 1 && Math.abs(fitH - h) <= 1) {
			return new Rect(0, 0, w, h); // a pixel of rounding is not worth a black line
		}
		return new Rect((w - fitW) / 2, (h - fitH) / 2, fitW, fitH);
	}

	public record Rect(int x, int y, int width, int height) {
	}

	private static boolean isVulkan() {
		if (vulkan == null) {
			vulkan = RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan");
		}
		return vulkan;
	}

	private static TextureTarget sized(@Nullable TextureTarget target, String name, int w, int h) {
		if (target == null) {
			return new TextureTarget(name, w, h, GpuFormat.RGBA8_UNORM, null);
		}
		if (target.width != w || target.height != h) {
			target.resize(w, h);
		}
		return target;
	}

	/** Draws {@code source} stretched over {@code area} of {@code target} (all of it if null), with linear filtering. */
	private static void scaleInto(CommandEncoder encoder, GpuTextureView source, TextureTarget target, RenderPass.@Nullable RenderArea area) {
		try (RenderPass pass = area == null
			? encoder.createRenderPass(() -> "ManiacReplay preview scale", target.getColorTextureView(), Optional.empty())
			: encoder.createRenderPass(() -> "ManiacReplay preview scale", target.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty(), area)) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			pass.setUniform("InSampler", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
	}
}
