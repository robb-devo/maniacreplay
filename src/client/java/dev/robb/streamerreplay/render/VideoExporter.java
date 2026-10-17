package dev.robb.streamerreplay.render;

import net.minecraft.network.chat.Component;

import dev.robb.streamerreplay.ui.LocalizedIOException;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.replay.PathPlayer;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;

/**
 * Renders the camera path frame by frame and streams the frames to ffmpeg.
 *
 * <p>Per frame: {@link #beforeFrame} advances the path by exactly 1/fps seconds (the session then delivers every
 * packet up to that moment), the world renders, and {@link #capture} copies the world image (no GUI) into a pooled
 * GPU buffer. When the copy completes the bytes are handed to the ffmpeg writer thread. If chunks are still
 * compiling or too many copies are in flight, the same moment is simply rendered again instead of advancing.
 */
public final class VideoExporter {
	public enum State { PREPARING, RENDERING, ENCODING, DONE, FAILED, CANCELLED }

	private static final int MAX_IN_FLIGHT = 3;
	private static final long FIRST_FRAME_SETTLE_MS = 8000;
	private static final int FIRST_FRAME_MIN_FRAMES = 30;
	private static final long FRAME_SETTLE_MS = 250;

	private static @Nullable VideoExporter active;
	private static @Nullable VideoExporter last;

	private final ReplaySession session;
	private final ExportSettings settings;
	private final PathPlayer player;
	private final int totalFrames;
	private final double frameMs;
	private final ArrayDeque<GpuBuffer> freeBuffers = new ArrayDeque<>();
	private final long startedAt = System.currentTimeMillis();

	private @Nullable FfmpegEncoder encoder;
	private final boolean x264;
	private volatile State state = State.PREPARING;
	private volatile @Nullable Component error;
	private int width;
	private int height;
	private int framesCaptured;
	private int framesDelivered;
	private int inFlight;
	private boolean needsStep;
	private boolean captureThisFrame;
	private long settleStart;
	private int settleFrames;

	private VideoExporter(ReplaySession session, ExportSettings settings, boolean x264) {
		this.session = session;
		this.settings = settings;
		this.x264 = x264;
		this.frameMs = 1000.0 / settings.fps();
		this.totalFrames = (int) Math.floor(session.path().durationMs() / frameMs) + 1;
		this.player = session.playPathForExport();
	}

	// ------------------------------------------------------------------------------------------------ static API

	/** Starts an export of the session's camera path. Render thread. */
	public static VideoExporter start(ReplaySession session, ExportSettings settings, boolean x264) {
		cancel();
		VideoExporter exporter = new VideoExporter(session, settings, x264);
		active = exporter;
		last = exporter;
		FrameHooks.setContinuous(exporter::capture);
		return exporter;
	}

	public static boolean isExporting() {
		return active != null;
	}

	/** The running export, or the last finished one (for the result screen). */
	public static @Nullable VideoExporter current() {
		return active != null ? active : last;
	}

	public static void cancel() {
		VideoExporter exporter = active;
		if (exporter != null) {
			exporter.stop(State.CANCELLED, null);
		}
	}

	/** Called by {@link ReplayManager} every frame before the session delivers packets. */
	public static void beforeFrame(Minecraft mc) {
		VideoExporter exporter = active;
		if (exporter != null) {
			exporter.frame(mc);
		}
	}

	// ------------------------------------------------------------------------------------------------ frame loop

	private void frame(Minecraft mc) {
		captureThisFrame = false;
		if (state != State.PREPARING && state != State.RENDERING) {
			return;
		}
		if (session.pathPlayer() != player) {
			stop(State.CANCELLED, null); // something else took over the camera
			return;
		}
		if (encoder != null && encoder.failure() != null) {
			fail(Component.translatable("streamerreplay.error.ffmpeg", LocalizedIOException.describe(encoder.failure())));
			return;
		}
		if (player.isWaitingForSeek()) {
			return; // the session is still fast-forwarding to the first keyframe
		}
		if (state == State.PREPARING) {
			state = State.RENDERING;
			settleStart = System.currentTimeMillis();
		}
		if (framesCaptured >= totalFrames) {
			if (inFlight == 0) {
				finishEncoding();
			}
			return;
		}
		if (needsStep) {
			player.step(session, frameMs);
			needsStep = false;
			settleStart = System.currentTimeMillis();
		}
		boolean settled;
		long waited = System.currentTimeMillis() - settleStart;
		if (framesCaptured == 0) {
			// The first frame comes right after a jump: give chunk meshes and fog time to settle completely.
			settleFrames++;
			boolean ready = settleFrames > FIRST_FRAME_MIN_FRAMES && mc.levelExtractor.countRenderedSections() > 10
				&& mc.levelRenderer.hasRenderedAllSections();
			settled = ready || waited > FIRST_FRAME_SETTLE_MS;
		} else {
			settled = mc.levelRenderer.hasRenderedAllSections() || waited > FRAME_SETTLE_MS;
		}
		if (settled && inFlight < MAX_IN_FLIGHT) {
			captureThisFrame = true;
		}
	}

	/** World-rendered hook (render thread). */
	private void capture(RenderTarget target) {
		if (!captureThisFrame || state != State.RENDERING) {
			return;
		}
		captureThisFrame = false;
		GpuTexture texture = target.getColorTexture();
		if (texture == null) {
			return;
		}
		if (encoder == null) {
			if (!startEncoder(target.width, target.height)) {
				return;
			}
		} else if (target.width != width || target.height != height) {
			fail(Component.translatable("streamerreplay.error.window_resized"));
			return;
		}
		int frameBytes = width * height * texture.getFormat().blockSize();
		if (frameBytes != encoder.frameBytes()) {
			fail(Component.translatable("streamerreplay.error.framebuffer_format", String.valueOf(texture.getFormat())));
			return;
		}
		GpuBuffer buffer = freeBuffers.poll();
		if (buffer == null) {
			buffer = RenderSystem.getDevice().createBuffer(() -> "Streamer Replay export frame", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, frameBytes);
		}
		GpuBuffer target0 = buffer;
		inFlight++;
		framesCaptured++;
		needsStep = true;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> onCopied(target0), 0);
	}

	private void onCopied(GpuBuffer buffer) {
		inFlight--;
		try {
			if (encoder != null && (state == State.RENDERING || state == State.ENCODING)) {
				byte[] frame = encoder.borrow();
				try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
					ByteBuffer data = view.data();
					data.get(0, frame, 0, frame.length);
				}
				encoder.submit(frame);
				framesDelivered++;
			}
		} catch (IOException | RuntimeException e) {
			fail(Component.translatable("streamerreplay.error.ffmpeg", LocalizedIOException.describe(e)));
		}
		if (state == State.RENDERING || state == State.PREPARING) {
			freeBuffers.push(buffer);
		} else {
			buffer.close();
		}
	}

	private boolean startEncoder(int w, int h) {
		width = w;
		height = h;
		try {
			Files.createDirectories(settings.output().getParent());
			Path log = settings.output().resolveSibling(settings.output().getFileName() + ".log");
			encoder = FfmpegEncoder.start(settings, w, h, x264, log);
			return true;
		} catch (IOException e) {
			fail(Component.translatable("streamerreplay.error.ffmpeg_start", LocalizedIOException.describe(e)));
			return false;
		}
	}

	private void finishEncoding() {
		state = State.ENCODING;
		FrameHooks.setContinuous(null);
		session.stopPath();
		releaseBuffers();
		FfmpegEncoder enc = encoder;
		active = null;
		if (enc == null) {
			fail(Component.translatable("streamerreplay.error.no_frames"));
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				int exit = enc.finish(120);
				if (exit != 0) {
					finishAsync(State.FAILED, Component.translatable("streamerreplay.error.ffmpeg_exit", exit));
				} else {
					deleteLogIfEmpty();
					finishAsync(State.DONE, null);
				}
			} catch (IOException e) {
				finishAsync(State.FAILED, Component.translatable("streamerreplay.error.ffmpeg", LocalizedIOException.describe(e)));
			}
		});
	}

	private void deleteLogIfEmpty() {
		try {
			Path log = settings.output().resolveSibling(settings.output().getFileName() + ".log");
			if (Files.exists(log) && Files.size(log) == 0) {
				Files.delete(log);
			}
		} catch (IOException ignored) {
			// cosmetic only
		}
	}

	/** Final state set from the encoding thread; nothing render-thread related is left to clean up. */
	private void finishAsync(State finalState, @Nullable Component message) {
		if (message != null) {
			StreamerReplay.LOGGER.error("Video export failed: {}", message == null ? null : message.getString());
		} else {
			StreamerReplay.LOGGER.info("Video exported to {}", settings.output());
		}
		error = message;
		state = finalState;
	}

	private void fail(Component message) {
		StreamerReplay.LOGGER.error("Video export failed: {}", message == null ? null : message.getString());
		stop(State.FAILED, message);
	}

	private void stop(State finalState, @Nullable Component message) {
		if (state == State.DONE || state == State.FAILED || state == State.CANCELLED) {
			return;
		}
		error = message;
		state = finalState;
		if (active == this) {
			active = null;
		}
		FrameHooks.setContinuous(null);
		if (encoder != null) {
			encoder.close();
		}
		releaseBuffers();
		if (session.pathPlayer() == player) {
			session.stopPath();
		}
		if (finalState == State.CANCELLED) {
			try {
				Files.deleteIfExists(settings.output());
			} catch (IOException ignored) {
				// partial file stays; harmless
			}
		}
	}

	private void releaseBuffers() {
		GpuBuffer buffer;
		while ((buffer = freeBuffers.poll()) != null) {
			buffer.close();
		}
	}

	// ------------------------------------------------------------------------------------------------ progress

	public State state() {
		return state;
	}

	public @Nullable Component error() {
		return error;
	}

	public Path output() {
		return settings.output();
	}

	public int totalFrames() {
		return totalFrames;
	}

	public int framesDone() {
		return framesDelivered;
	}

	public float progress() {
		return totalFrames == 0 ? 0 : Math.min(1, framesDelivered / (float) totalFrames);
	}

	public long elapsedMs() {
		return System.currentTimeMillis() - startedAt;
	}

	public boolean usesX264() {
		return x264;
	}

	public int renderWidth() {
		return width;
	}

	public int renderHeight() {
		return height;
	}
}
