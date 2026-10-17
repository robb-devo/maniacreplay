package dev.robb.streamerreplay.ui;

import com.mojang.blaze3d.platform.Window;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.render.ExportSettings;
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
		y += 34;

		ffmpegBox = new EditBox(font, cx - w - 4, y, 2 * w + 8 - 64, 20, Component.translatable("streamerreplay.export.ffmpeg"));
		ffmpegBox.setMaxLength(1024);
		ffmpegBox.setValue(config.ffmpegPath);
		ffmpegBox.setTooltip(Tooltip.create(Component.translatable("streamerreplay.export.ffmpeg.tooltip")));
		addRenderableWidget(ffmpegBox);
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.export.check"), b -> startProbe(true))
			.bounds(cx + w + 4 - 60, y, 60, 20).build());
		y += 40;

		startButton = addRenderableWidget(Button.builder(Component.translatable("streamerreplay.export.start"), b -> startExport())
			.bounds(cx - w - 4, y, w, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(cx + 4, y, w, 20).build());
		startProbe(false);
	}

	private Component resolutionLabel(ReplayConfig.ExportResolution r) {
		if (r == ReplayConfig.ExportResolution.WINDOW) {
			Window window = minecraft.getWindow();
			return Component.translatable("streamerreplay.export.resolution.window", window.getWidth(), window.getHeight());
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
		if (probe != null && probe.isDone()) {
			probeResult = probe.join();
			probe = null;
		}
		String path = ffmpegBox.getValue().trim();
		if (!path.equals(probedPath) && !ffmpegBox.isFocused()) {
			startProbe(false);
		}
		startButton.active = session.path().isPlayable() && probeResult != null && probeResult.ok() && !nameBox.getValue().isBlank();
	}

	private void startExport() {
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
			config.exportResolution.width, config.exportResolution.height);
		VideoExporter.start(session, settings, probeResult != null && probeResult.hasX264());
		minecraft.gui.setScreen(new ExportProgressScreen(parent));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int cx = width / 2;
		int top = Math.max(52, height / 2 - 80);
		g.centeredText(font, title, cx, top - 44, Theme.TEXT);
		long frames = (long) Math.floor(session.path().durationMs() * ReplayConfig.get().exportFps / 1000.0) + 1;
		g.centeredText(font, Component.translatable("streamerreplay.export.summary", session.path().size(),
			TimeFormat.clock(session.path().durationMs()), frames), cx, top - 31, Theme.TEXT_DIM);
		g.text(font, Component.translatable("streamerreplay.export.file_name"), cx - 154, top - 11, Theme.TEXT_FAINT, false);

		int ffmpegY = top + 34 + 24 + 34;
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
		if (ReplayConfig.get().exportResolution != ReplayConfig.ExportResolution.WINDOW) {
			Window window = minecraft.getWindow();
			if (window.getWidth() < ReplayConfig.get().exportResolution.width) {
				g.centeredText(font, Component.translatable("streamerreplay.export.upscale_warning", window.getWidth(), window.getHeight()),
					cx, ffmpegY + 66, Theme.MARKER);
			}
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
