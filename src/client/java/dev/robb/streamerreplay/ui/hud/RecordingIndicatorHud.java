package dev.robb.streamerreplay.ui.hud;

import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.record.Recorder;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.ui.Theme;
import dev.robb.streamerreplay.util.TimeFormat;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small "● REC 12:34 ⚑3" badge in the top left corner while recording. A new marker makes it light up gold for a
 * moment, so the streamer gets feedback right where they look, without a toast on stream. Costs nothing when not
 * recording.
 */
public final class RecordingIndicatorHud implements HudElement {
	private static final long MARKER_FLASH_MS = 1400;
	private static final int REC_RED = 0xFFFF6B6B;
	private static volatile long markerFlashAt = -1;

	// Cached so that recording does not allocate new strings every frame.
	private long cachedSecond = -1;
	private int cachedMarkers = -1;
	private String time = "";
	private String flags = "";

	/** A marker was just added: light up the badge. */
	public static void flashMarker() {
		markerFlashAt = System.currentTimeMillis();
	}

	/** True if the badge is on screen, so a new marker needs no extra toast. */
	public static boolean isVisible() {
		return RecordingManager.active() != null && ReplayConfig.get().showRecordingIndicator
			&& !Minecraft.getInstance().getDebugOverlay().showDebugScreen();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Recorder recorder = RecordingManager.active();
		if (recorder == null || !isVisible()) {
			return; // the debug screen owns the top left corner
		}
		Font font = Minecraft.getInstance().font;
		long second = recorder.elapsedMs() / 1000;
		int markers = recorder.markerCount();
		if (second != cachedSecond || markers != cachedMarkers) {
			cachedSecond = second;
			cachedMarkers = markers;
			time = TimeFormat.clock(second * 1000);
			flags = markers > 0 ? "⚑ " + markers : "";
		}
		int w = 18 + font.width("REC") + 4 + font.width(time) + (flags.isEmpty() ? 0 : 8 + font.width(flags)) + 6;
		long now = System.currentTimeMillis();
		long sinceMarker = now - markerFlashAt;
		float flash = markerFlashAt >= 0 && sinceMarker < MARKER_FLASH_MS ? 1 - sinceMarker / (float) MARKER_FLASH_MS : 0;

		Theme.panel(g, 4, 4, 4 + w, 18);
		if (flash > 0) { // gold outline fading out after a marker
			int gold = Theme.withAlpha(Theme.MARKER, flash);
			g.fill(3, 3, 5 + w, 4, gold);
			g.fill(3, 18, 5 + w, 19, gold);
			g.fill(3, 4, 4, 18, gold);
			g.fill(4 + w, 4, 5 + w, 18, gold);
		}
		// a calm pulse instead of a hard blink: easy to notice, not distracting on stream
		double phase = (now % 1600) / 1600.0 * Math.PI * 2;
		int dot = Theme.withAlpha(Theme.RECORDING, (float) (0.6 + 0.4 * Math.cos(phase)));
		g.fill(9, 8, 13, 14, dot);
		g.fill(8, 9, 14, 13, dot);
		int x = 18;
		g.text(font, "REC", x, 7, REC_RED, false);
		x += font.width("REC") + 4;
		g.text(font, time, x, 7, Theme.TEXT, false);
		if (!flags.isEmpty()) {
			x += font.width(time) + 8;
			g.text(font, flags, x, 7, flash > 0.5F ? Theme.TEXT : Theme.MARKER, false);
		}
	}
}
