package dev.robb.streamerreplay.ui;

import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.replay.ReplayManager;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The recording switch in the top left corner of the vanilla pause menu ("Recording: ON/OFF"), so that turning
 * recording off for a session never needs the settings screen. It is the same switch as in the settings.
 */
public final class PauseMenuRecordingButton {
	private PauseMenuRecordingButton() {
	}

	public static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
		if (!(screen instanceof PauseScreen pause) || !pause.showsPauseMenu() || ReplayManager.isActive()) {
			return;
		}
		Component label = Component.translatable("streamerreplay.pause_menu.recording");
		if (RecordingManager.isRecording()) {
			label = Component.empty().append(Component.literal("\u25CF ").withColor(Theme.RECORDING)).append(label); // red dot: recording right now
		}
		int buttonWidth = Math.max(110, client.font.width(label) + client.font.width(": OFF") + 16);
		CycleButton<Boolean> button = CycleButton.onOffBuilder(ReplayConfig.get().recordingEnabled)
			.withTooltip(on -> Tooltip.create(Component.translatable(tooltipKey(on))))
			.create(6, 6, buttonWidth, 20, label, (b, on) -> RecordingManager.setRecordingEnabled(on));
		Screens.getWidgets(screen).add(button);
	}

	private static String tooltipKey(boolean on) {
		if (!on) {
			return "streamerreplay.pause_menu.recording.off.tooltip";
		}
		return RecordingManager.isRecording() ? "streamerreplay.pause_menu.recording.on.tooltip" : "streamerreplay.pause_menu.recording.next_join.tooltip";
	}
}
