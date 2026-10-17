package dev.robb.streamerreplay.ui.hud;

import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.record.Recorder;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.ui.Theme;
import dev.robb.streamerreplay.util.TimeFormat;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small "● REC 12:34" badge in the top left corner while recording. Costs nothing when not recording. */
public final class RecordingIndicatorHud implements HudElement {
	// Cached so that recording does not allocate a new string every frame.
	private long cachedSecond = -1;
	private int cachedMarkers = -1;
	private String cachedText = "";

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Recorder recorder = RecordingManager.active();
		if (recorder == null || !ReplayConfig.get().showRecordingIndicator) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.getDebugOverlay().showDebugScreen()) {
			return; // the debug screen owns the top left corner
		}
		long second = recorder.elapsedMs() / 1000;
		int markers = recorder.markerCount();
		if (second != cachedSecond || markers != cachedMarkers) {
			cachedSecond = second;
			cachedMarkers = markers;
			cachedText = "REC " + TimeFormat.clock(second * 1000) + (markers > 0 ? "  \u2691" + markers : "");
		}
		String text = cachedText;
		int w = 14 + mc.font.width(text) + 6;
		g.fill(4, 4, 4 + w, 18, Theme.PANEL);
		boolean blink = (System.currentTimeMillis() / 600) % 2 == 0;
		int dot = blink ? Theme.RECORDING : Theme.withAlpha(Theme.RECORDING, 0.35F);
		g.fill(9, 8, 13, 14, dot);
		g.fill(8, 9, 14, 13, dot);
		g.text(mc.font, text, 18, 7, Theme.TEXT, false);
	}
}
