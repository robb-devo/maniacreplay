package dev.robb.streamerreplay.ui;

import com.mojang.blaze3d.Blaze3D;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.ui.widget.ValueSlider;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Settings. Few options, each with a tooltip; changes are saved immediately. */
public final class ReplayConfigScreen extends Screen {
	private static final int ROW = 24;

	private final Screen parent;
	private final List<Section> sections = new ArrayList<>();
	private EditBox ffmpegBox;

	private record Section(Component title, int y) {
	}

	public ReplayConfigScreen(Screen parent) {
		super(Component.translatable("streamerreplay.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		sections.clear();
		ReplayConfig config = ReplayConfig.get();
		int left = width / 2 - 155;
		int right = width / 2 + 5;
		int y = Math.max(34, height / 2 - 110);

		sections.add(new Section(Component.translatable("streamerreplay.config.section.recording"), y));
		y += 12;
		toggle(left, y, "record_singleplayer", config.recordSingleplayer, v -> config.recordSingleplayer = v);
		toggle(right, y, "record_multiplayer", config.recordMultiplayer, v -> config.recordMultiplayer = v);
		y += ROW;
		toggle(left, y, "show_indicator", config.showRecordingIndicator, v -> config.showRecordingIndicator = v);
		toggle(right, y, "store_server_address", config.storeServerAddress, v -> config.storeServerAddress = v);
		y += ROW + 10;

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
		y += ROW + 10;

		sections.add(new Section(Component.translatable("streamerreplay.config.section.export"), y));
		y += 12;
		ffmpegBox = new EditBox(font, left, y, 310, 20, Component.translatable("streamerreplay.export.ffmpeg"));
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
	}

	@Override
	public void onClose() {
		ReplayConfig.get().save();
		minecraft.gui.setScreen(parent);
	}
}
