package dev.robb.streamerreplay.ui;

import com.mojang.blaze3d.Blaze3D;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.format.ReplayFormat;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.ui.widget.ValueSlider;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Settings. Few options, each with a tooltip; changes are saved immediately. */
public final class ReplayConfigScreen extends Screen {
	private static final int ROW = 24;
	private static final int SECTION_GAP = 6;

	private final Screen parent;
	private final List<Section> sections = new ArrayList<>();
	/** "1.2 GB in 14 replays", measured in the background; next to the clean-up setting it explains itself. */
	private volatile String storage;

	private record Section(Component title, int y) {
	}

	/** What gets recorded; one control instead of three switches. */
	private enum RecordMode {
		EVERYWHERE, WORLDS, SERVERS, OFF;

		static RecordMode of(ReplayConfig config) {
			if (!config.recordingEnabled || !config.recordSingleplayer && !config.recordMultiplayer) {
				return OFF;
			}
			return config.recordSingleplayer && config.recordMultiplayer ? EVERYWHERE : config.recordSingleplayer ? WORLDS : SERVERS;
		}

		void apply(ReplayConfig config) {
			if (this != OFF) {
				config.recordSingleplayer = this != SERVERS;
				config.recordMultiplayer = this != WORLDS;
			}
			RecordingManager.setRecordingEnabled(this != OFF); // saves; stops a running recording when turned off
		}

		Component label() {
			return Component.translatable("streamerreplay.config.recording." + name().toLowerCase(Locale.ROOT));
		}
	}

	public ReplayConfigScreen(Screen parent) {
		super(Component.translatable("streamerreplay.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		sections.clear();
		ReplayConfig config = ReplayConfig.get();
		if (storage == null) {
			CompletableFuture.supplyAsync(ReplayConfigScreen::storageText).thenAccept(text -> storage = text);
		}
		int left = width / 2 - 155;
		int right = width / 2 + 5;
		int y = Math.max(34, height / 2 - 104);

		sections.add(new Section(Component.translatable("streamerreplay.config.section.recording"), y));
		y += 12;
		addRenderableWidget(CycleButton.<RecordMode>builder(RecordMode::label, RecordMode.of(config))
			.withValues(RecordMode.values())
			.withTooltip(mode -> Tooltip.create(Component.translatable("streamerreplay.config.recording.tooltip")))
			.create(left, y, 150, 20, Component.translatable("streamerreplay.config.recording"), (b, mode) -> mode.apply(config)));
		toggle(right, y, "show_indicator", config.showRecordingIndicator, v -> config.showRecordingIndicator = v);
		y += ROW;
		addRenderableWidget(CycleButton.<Integer>builder(days -> days == 0
				? Component.translatable("streamerreplay.config.cleanup.never")
				: Component.translatable("streamerreplay.config.cleanup.days", days), config.cleanupDays)
			.withValues(ReplayConfig.CLEANUP_CHOICES)
			.withTooltip(days -> Tooltip.create(Component.translatable("streamerreplay.config.cleanup.tooltip")))
			.create(left, y, 150, 20, Component.translatable("streamerreplay.config.cleanup"), (b, days) -> {
				config.cleanupDays = days;
				config.save();
			}));
		toggle(right, y, "store_server_address", config.storeServerAddress, v -> config.storeServerAddress = v);
		y += ROW + SECTION_GAP;

		sections.add(new Section(Component.translatable("streamerreplay.config.section.replay"), y));
		y += 12;
		toggle(left, y, "show_hints", config.showControlsHint, v -> config.showControlsHint = v);
		ValueSlider smoothing = new ValueSlider(right, y, 150, 20, 0, 0.95, 0.05, config.cameraSmoothing,
			v -> Component.translatable("streamerreplay.config.camera_smoothing", Math.round(v * 100)),
			v -> {
				config.cameraSmoothing = (float) v;
				config.save();
			});
		smoothing.setTooltip(Tooltip.create(Component.translatable("streamerreplay.config.camera_smoothing.tooltip")));
		addRenderableWidget(smoothing);
		y += ROW;
		toggle(left, y, "show_chat", config.showChatInReplay, v -> config.showChatInReplay = v);
		y += ROW + SECTION_GAP;

		sections.add(new Section(Component.translatable("streamerreplay.config.section.export"), y));
		y += 12;
		EditBox ffmpegBox = new EditBox(font, left, y, 310, 20, Component.translatable("streamerreplay.export.ffmpeg"));
		ffmpegBox.setMaxLength(1024);
		ffmpegBox.setValue(config.ffmpegPath);
		ffmpegBox.setHint(Component.literal("ffmpeg"));
		ffmpegBox.setTooltip(Tooltip.create(Component.translatable("streamerreplay.export.ffmpeg.tooltip")));
		ffmpegBox.setResponder(value -> {
			config.ffmpegPath = value.isBlank() ? "ffmpeg" : value.trim();
			config.save();
		});
		addRenderableWidget(ffmpegBox);

		int bottom = height - 28;
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.browser.open_folder"), b -> Blaze3D.openPath(StreamerReplay.replayFolder()))
			.bounds(left, bottom, 150, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(right, bottom, 150, 20).build());
	}

	private static String storageText() {
		long bytes = 0;
		int count = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(StreamerReplay.replayFolder(), "*" + ReplayFormat.EXTENSION)) {
			for (Path file : files) {
				bytes += Files.size(file);
				count++;
			}
		} catch (IOException | RuntimeException e) {
			return "";
		}
		return Component.translatable(count == 1 ? "streamerreplay.config.storage.one" : "streamerreplay.config.storage", GameLocale.bytes(bytes), count).getString();
	}

	private void toggle(int x, int y, String key, boolean value, Consumer<Boolean> setter) {
		String full = "streamerreplay.config." + key;
		addRenderableWidget(CycleButton.onOffBuilder(value)
			.withTooltip(v -> Tooltip.create(Component.translatable(full + ".tooltip")))
			.create(x, y, 150, 20, Component.translatable(full), (button, v) -> {
				setter.accept(v);
				ReplayConfig.get().save();
			}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 14, Theme.TEXT);
		for (Section section : sections) {
			g.text(font, section.title(), width / 2 - 155, section.y(), Theme.TEXT_DIM, false);
		}
		String used = storage;
		if (used != null && !used.isEmpty() && !sections.isEmpty()) {
			g.text(font, used, width / 2 + 155 - font.width(used), sections.getFirst().y(), Theme.TEXT_FAINT, false);
		}
	}

	@Override
	public void onClose() {
		ReplayConfig.get().save();
		minecraft.gui.setScreen(parent);
	}
}
