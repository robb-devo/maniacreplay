package dev.robb.streamerreplay.ui;

import com.mojang.blaze3d.platform.Window;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.path.CameraPath;
import dev.robb.streamerreplay.render.ExportSettings;
import dev.robb.streamerreplay.render.FfmpegInstaller;
import dev.robb.streamerreplay.render.FfmpegProbe;
import dev.robb.streamerreplay.render.VideoExporter;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.util.TimeFormat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Video export settings. Kept to five choices; everything else has sensible defaults. */
public final class ExportScreen extends Screen {
	private static final List<Integer> FPS_CHOICES = List.of(24, 30, 60);

	private final ReplaySession session;
	private final Screen parent;
	private EditBox nameBox;
	private EditBox ffmpegBox;
	private Button startButton;
	private @Nullable CompletableFuture<FfmpegProbe.Result> probe;
	private FfmpegProbe.@Nullable Result probeResult;
	private String probedPath;
	/** Shown under the buttons when Start cannot run right now (never a silently greyed-out button). */
	private @Nullable Component blockedReason;
	private Button helpButton;
	private static final List<Integer> SHOT_SECONDS = List.of(10, 30, 60, 0); // 0 = until the end of the replay
	private int shotSeconds = 10;
	private static boolean helpShown;

	public ExportScreen(ReplaySession session, Screen parent) {
		super(Component.translatable("streamerreplay.export.title"));
		this.session = session;
		this.parent = parent;
	}

	@Override
	protected void init() {
		ReplayConfig config = ReplayConfig.get();
		int cx = width / 2;
		int y = Math.max(52, height / 2 - 80);
		int w = 150;

		nameBox = new EditBox(font, cx - w - 4, y, 2 * w + 8, 20, Component.translatable("streamerreplay.export.file_name"));
		nameBox.setMaxLength(120);
		if (nameBox.getValue().isEmpty()) {
			nameBox.setValue(defaultName());
		}
		addRenderableWidget(nameBox);
		y += 34;

		addRenderableWidget(CycleButton.<ReplayConfig.ExportResolution>builder(r -> resolutionLabel(r), config.exportResolution)
			.withValues(ReplayConfig.ExportResolution.values())
			.withTooltip(r -> Tooltip.create(Component.translatable("streamerreplay.export.resolution.tooltip")))
			.create(cx - w - 4, y, 2 * w + 8, 20, Component.translatable("streamerreplay.export.resolution"), (b, v) -> {
				config.exportResolution = v;
				config.save();
			}));
		y += 24; // full width: "Window (2560 × 1440)" needs the room, especially in German
		addRenderableWidget(CycleButton.<Integer>builder(fps -> Component.literal(fps + " FPS"), FPS_CHOICES.contains(config.exportFps) ? config.exportFps : 60)
			.withValues(FPS_CHOICES)
			.create(cx - w - 4, y, w, 20, Component.translatable("streamerreplay.export.fps"), (b, v) -> {
				config.exportFps = v;
				config.save();
			}));
		addRenderableWidget(CycleButton.<ReplayConfig.ExportQuality>builder(q -> Component.translatable("streamerreplay.export.quality." + q.name().toLowerCase()), config.exportQuality)
			.withValues(ReplayConfig.ExportQuality.values())
			.withTooltip(q -> Tooltip.create(Component.translatable("streamerreplay.export.quality.tooltip")))
			.create(cx + 4, y, w, 20, Component.translatable("streamerreplay.export.quality"), (b, v) -> {
				config.exportQuality = v;
				config.save();
			}));
		y += 24;
		boolean noPath = !session.path().isPlayable();
		if (noPath) {
			// no camera path yet: film a fixed shot from the current camera, for a chosen length
			addRenderableWidget(CycleButton.<Integer>builder(sec -> sec == 0
					? Component.translatable("streamerreplay.export.length.to_end")
					: Component.translatable("streamerreplay.export.length.seconds", sec), shotSeconds)
				.withValues(SHOT_SECONDS)
				.withTooltip(v -> Tooltip.create(Component.translatable("streamerreplay.export.length.tooltip")))
				.create(cx - w - 4, y, w, 20, Component.translatable("streamerreplay.export.length"), (b, v) -> shotSeconds = v));
		}
		addRenderableWidget(CycleButton.onOffBuilder(config.exportCleanFrames)
			.withTooltip(v -> Tooltip.create(Component.translatable("streamerreplay.export.clean_frames.tooltip")))
			.create(noPath ? cx + 4 : cx - w - 4, y, noPath ? w : 2 * w + 8, 20, Component.translatable(noPath ? "streamerreplay.export.clean_frames.short" : "streamerreplay.export.clean_frames"), (b, v) -> {
				config.exportCleanFrames = v;
				config.save();
			}));
		y += 34;

		ffmpegBox = new EditBox(font, cx - w - 4, y, 2 * w + 8 - 128, 20, Component.translatable("streamerreplay.export.ffmpeg"));
		ffmpegBox.setMaxLength(1024);
		ffmpegBox.setValue(config.ffmpegPath);
		ffmpegBox.setTooltip(Tooltip.create(Component.translatable("streamerreplay.export.ffmpeg.tooltip")));
		addRenderableWidget(ffmpegBox);
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.export.check"), b -> startProbe(true))
			.bounds(cx + w + 4 - 124, y, 60, 20).build());
		helpButton = addRenderableWidget(Button.builder(Component.translatable("streamerreplay.export.ffmpeg_help"), b -> minecraft.gui.setScreen(new FfmpegHelpScreen(this)))
			.bounds(cx + w + 4 - 60, y, 60, 20)
			.tooltip(Tooltip.create(Component.translatable("streamerreplay.export.ffmpeg_help.tooltip"))).build());
		y += 40;

		startButton = addRenderableWidget(Button.builder(Component.translatable("streamerreplay.export.start"), b -> startExport())
			.bounds(cx - w - 4, y, w, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(cx + 4, y, w, 20).build());
		startProbe(probeResult != null && !probeResult.ok()); // e.g. back from the help screen after installing ffmpeg
	}

	private Component resolutionLabel(ReplayConfig.ExportResolution r) {
		if (r == ReplayConfig.ExportResolution.WINDOW) {
			Window window = minecraft.getWindow();
			return Component.translatable("streamerreplay.export.resolution.window", window.getWidth(), window.getHeight());
		}
		if (r.isVertical()) {
			return Component.translatable("streamerreplay.export.resolution.vertical", r.width, r.height);
		}
		return Component.literal(r.width + " \u00D7 " + r.height);
	}

	private String defaultName() {
		String base = session.file().getFileName().toString();
		if (base.endsWith(".sreplay")) {
			base = base.substring(0, base.length() - ".sreplay".length());
		}
		return base;
	}

	private void startProbe(boolean force) {
		String path = ffmpegBox.getValue().trim();
		if (!force && probeResult != null && path.equals(probedPath)) {
			return;
		}
		probedPath = path;
		probeResult = null;
		probe = FfmpegProbe.probe(path.isEmpty() ? "ffmpeg" : path);
	}

	@Override
	public void tick() {
		if (FfmpegInstaller.state() == FfmpegInstaller.State.DONE) { // finished in the background
			Notifications.replay(Component.translatable("streamerreplay.toast.ffmpeg_installed", FfmpegInstaller.VERSION), null);
			FfmpegInstaller.acknowledge();
			startProbe(true);
		}
		if (probe != null && probe.isDone()) {
			probeResult = probe.join();
			probe = null;
			blockedReason = null;
			if (!probeResult.ok() && !helpShown) {
				helpShown = true; // impossible to miss: explain once per game session, right away
				minecraft.gui.setScreen(new FfmpegHelpScreen(this));
			}
		}
		String path = ffmpegBox.getValue().trim();
		if (!path.equals(probedPath) && !ffmpegBox.isFocused()) {
			startProbe(false);
		}
		// Start stays clickable; what it will do (video, or PNG images without ffmpeg) is on its label
		startButton.setMessage(Component.translatable(probeResult != null && !probeResult.ok() ? "streamerreplay.export.start_png" : "streamerreplay.export.start"));
		startButton.setTooltip(probeResult != null && !probeResult.ok() ? Tooltip.create(Component.translatable("streamerreplay.export.start_png.tooltip")) : null);
	}

	private void startExport() {
		if (nameBox.getValue().isBlank()) {
			blockedReason = Component.translatable("streamerreplay.export.blocked.name");
			setFocused(nameBox);
			return;
		}
		if (probeResult == null) {
			blockedReason = Component.translatable("streamerreplay.export.blocked.checking");
			return;
		}
		boolean png = !probeResult.ok();
		ReplayConfig config = ReplayConfig.get();
		config.ffmpegPath = ffmpegBox.getValue().trim().isEmpty() ? "ffmpeg" : ffmpegBox.getValue().trim();
		config.save();
		String name = nameBox.getValue().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
		Path folder = StreamerReplay.videoFolder();
		Path output = folder.resolve(name + ".mp4");
		for (int i = 2; Files.exists(output); i++) {
			output = folder.resolve(name + " (" + i + ").mp4");
		}
		ExportSettings settings = new ExportSettings(output, config.ffmpegPath, config.exportFps, config.exportQuality,
			config.exportResolution.width, config.exportResolution.height, config.exportCleanFrames);
		CameraPath path = session.path();
		if (!path.isPlayable()) {
			// fixed shot from the current camera: from now for the chosen length
			long from = (long) session.clock().timeMs();
			long total = session.clock().durationMs();
			long to = shotSeconds == 0 ? total : Math.min(total, from + shotSeconds * 1000L);
			if (to - from < 500) { // at the very end: take the last seconds instead
				from = Math.max(0, to - Math.max(1000L, shotSeconds * 1000L));
			}
			var pose = session.camera().pose();
			path = new CameraPath();
			path.addAt(from, pose);
			path.addAt(to, pose);
		}
		StreamerReplay.LOGGER.info("Export {} ({}), path of {} ms", output, png ? "PNG frames, no ffmpeg" : "video", path.durationMs());
		VideoExporter.start(session, settings, probeResult.hasX264(), png, path);
		minecraft.gui.setScreen(new ExportProgressScreen(parent));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int cx = width / 2;
		int top = Math.max(52, height / 2 - 80);
		g.centeredText(font, title, cx, top - 44, Theme.TEXT);
		long frames = (long) Math.floor(session.path().durationMs() * ReplayConfig.get().exportFps / 1000.0) + 1;
		g.centeredText(font, session.path().isPlayable()
			? Component.translatable("streamerreplay.export.summary", session.path().size(), TimeFormat.clock(session.path().durationMs()), frames)
			: Component.translatable("streamerreplay.export.summary_no_path"), cx, top - 31, session.path().isPlayable() ? Theme.TEXT_DIM : Theme.TEXT);
		g.text(font, Component.translatable("streamerreplay.export.file_name"), cx - 154, top - 11, Theme.TEXT_FAINT, false);

		int ffmpegY = top + 34 + 24 + 24 + 34;
		g.text(font, Component.translatable("streamerreplay.export.ffmpeg"), cx - 154, ffmpegY - 10, Theme.TEXT_FAINT, false);
		Component status;
		int color;
		if (probeResult == null) {
			status = Component.translatable("streamerreplay.export.ffmpeg.checking");
			color = Theme.TEXT_DIM;
		} else if (probeResult.ok()) {
			status = Component.translatable(probeResult.hasX264() ? "streamerreplay.export.ffmpeg.found" : "streamerreplay.export.ffmpeg.found_no_x264", probeResult.version());
			color = Theme.SUCCESS;
		} else {
			status = Component.translatable("streamerreplay.export.ffmpeg.missing");
			color = Theme.ERROR;
		}
		g.centeredText(font, status, cx, ffmpegY + 25, color);
		if (blockedReason != null) {
			g.centeredText(font, blockedReason, cx, startButton.getY() + 24, Theme.ERROR);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
