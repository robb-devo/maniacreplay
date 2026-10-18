package dev.robb.streamerreplay.ui.hud;

import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.format.Marker;
import dev.robb.streamerreplay.input.ReplayKeys;
import dev.robb.streamerreplay.render.VideoExporter;
import dev.robb.streamerreplay.replay.ReplayCamera;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.GameLocale;
import dev.robb.streamerreplay.ui.RewindOverlay;
import dev.robb.streamerreplay.ui.Theme;
import dev.robb.streamerreplay.ui.widget.TimelineWidget;
import dev.robb.streamerreplay.util.TimeFormat;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** Minimal in-world overlay while watching a replay without the editor open. */
public final class ReplayHud implements HudElement {
	private static final long HINT_VISIBLE_MS = 10_000;
	private static final long HINT_FADE_MS = 1_500;

	private static final long MARKER_POPUP_MS = 2500;
	private static volatile Marker popupMarker;
	private static long popupAt;

	private long hintShownAt = -1;
	private ReplaySession hintSession;
	private double lastTimeMs = -1;

	/** Shows a marker's name above the timeline for a moment, like a chapter title in a video player. */
	public static void showMarker(Marker marker) {
		popupMarker = marker;
		popupAt = System.currentTimeMillis();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		ReplaySession session = ReplayManager.session();
		if (session == null || !session.isInWorld(mc) || VideoExporter.isExporting()) {
			return;
		}
		int width = g.guiWidth();
		int height = g.guiHeight();
		detectPassedMarkers(session);
		RewindOverlay.draw(g, width, height, session.catchUpProgress()); // also under the editor panel
		if (mc.gui.screen() != null) {
			return;
		}
		Font font = mc.font;

		if (session.isFastForwarding() && !RewindOverlay.isShown()) {
			Component seeking = Component.translatable("streamerreplay.hud.seeking", Math.round(session.catchUpProgress() * 100));
			g.centeredText(font, seeking, width / 2, height / 2 - 20, Theme.TEXT);
			Theme.progressBar(g, width / 2 - 60, height / 2 - 8, 120, 3, session.catchUpProgress(), Theme.TRACK_PLAYED);
		}

		Component banner = banner(mc, session);
		if (banner != null) {
			int bw = font.width(banner) + 12;
			Theme.panel(g, width / 2 - bw / 2, 4, width / 2 + bw / 2, 18);
			g.centeredText(font, banner, width / 2, 7, Theme.TEXT);
		}

		// bottom bar
		int barTop = height - 18;
		Theme.panel(g, 0, barTop, width, height);
		String state = session.clock().isPlaying() || session.isPlayingPath() ? "\u25B6" : "||";
		String time = TimeFormat.clock((long) session.clock().timeMs()) + " / " + TimeFormat.clock(session.clock().durationMs());
		g.text(font, state + "  " + time, 6, barTop + 5, Theme.TEXT, false);
		String speed = formatSpeed(session.clock().speed());
		g.text(font, speed, width - 6 - font.width(speed), barTop + 5, Theme.TEXT_DIM, false);
		int timelineX = 12 + font.width(state + "  " + time) + 6;
		int timelineEnd = width - 18 - font.width(speed);
		if (timelineEnd - timelineX > 40) {
			TimelineWidget.drawCompact(g, session, timelineX, barTop + 8, timelineEnd - timelineX);
		}

		int hintTop = drawHint(g, font, session, width, barTop);
		drawMarkerPopup(g, font, session, timelineX, timelineEnd, hintTop);
	}

	/** Normal playback just passed a marker (jumps and seeks do not count). */
	private void detectPassedMarkers(ReplaySession session) {
		double now = session.clock().timeMs();
		if (lastTimeMs >= 0 && now > lastTimeMs && now - lastTimeMs < 1000) {
			for (Marker marker : session.markers()) {
				if (marker.timeMs() > lastTimeMs && marker.timeMs() <= now) {
					showMarker(marker);
				}
			}
		}
		lastTimeMs = now;
	}

	private static void drawMarkerPopup(GuiGraphicsExtractor g, Font font, ReplaySession session, int x0, int x1, int bottom) {
		Marker marker = popupMarker;
		if (marker == null) {
			return;
		}
		long age = System.currentTimeMillis() - popupAt;
		if (age > MARKER_POPUP_MS) {
			popupMarker = null;
			return;
		}
		float alpha = age < MARKER_POPUP_MS - 400 ? 1 : (MARKER_POPUP_MS - age) / 400F;
		String text = "\u2691 " + marker.name();
		int w = font.width(text) + 10;
		long duration = Math.max(1, session.clock().durationMs());
		int flagX = x0 + (int) ((x1 - x0 - 1) * Math.min(1, marker.timeMs() / (double) duration));
		int left = Math.max(2, Math.min(g.guiWidth() - w - 2, flagX - w / 2));
		int top = bottom - 16;
		g.fill(left, top, left + w, top + 13, Theme.withAlpha(Theme.PANEL, alpha));
		g.fill(left, top + 12, left + w, top + 13, Theme.withAlpha(Theme.MARKER, alpha)); // gold underline
		if (alpha > 0.05F) {
			g.text(font, text, left + 5, top + 3, Theme.withAlpha(Theme.MARKER, Math.max(0.1F, alpha)), false);
		}
	}

	private static Component banner(Minecraft mc, ReplaySession session) {
		if (session.isPlayingPath()) {
			return Component.translatable("streamerreplay.hud.path_preview", ReplayKeys.PLAY_PAUSE.getTranslatedKeyMessage());
		}
		if (ReplayCamera.isSpectatingOther(mc)) {
			Entity target = mc.getCameraEntity();
			return Component.translatable("streamerreplay.hud.following", target == null ? "" : target.getDisplayName(),
				mc.options.keyShift.getTranslatedKeyMessage());
		}
		return null;
	}

	/** Draws the fading controls hint above the bar; returns its top edge (or the bar's when not shown). */
	private int drawHint(GuiGraphicsExtractor g, Font font, ReplaySession session, int width, int barTop) {
		if (!ReplayConfig.get().showControlsHint) {
			return barTop;
		}
		long now = System.currentTimeMillis();
		if (hintSession != session) {
			hintSession = session;
			hintShownAt = now;
		}
		long age = now - hintShownAt;
		if (age > HINT_VISIBLE_MS + HINT_FADE_MS) {
			return barTop;
		}
		float alpha = age < HINT_VISIBLE_MS ? 1 : 1 - (age - HINT_VISIBLE_MS) / (float) HINT_FADE_MS;
		Component hint = Component.translatable("streamerreplay.hud.hint",
			ReplayKeys.PLAY_PAUSE.getTranslatedKeyMessage(),
			ReplayKeys.EDITOR.getTranslatedKeyMessage(),
			ReplayKeys.SEEK_BACK.getTranslatedKeyMessage(),
			ReplayKeys.SEEK_FORWARD.getTranslatedKeyMessage());
		// long key names ("Left Arrow") or translations wrap onto a second line instead of running off screen
		List<FormattedCharSequence> lines = font.split(hint, width - 24);
		int hw = 0;
		for (FormattedCharSequence line : lines) {
			hw = Math.max(hw, font.width(line));
		}
		hw += 12;
		int top = barTop - 6 - lines.size() * 10;
		g.fill(width / 2 - hw / 2, top, width / 2 + hw / 2, barTop - 3, Theme.withAlpha(Theme.PANEL, alpha));
		if (alpha > 0.05F) {
			for (int i = 0; i < lines.size(); i++) {
				g.centeredText(font, lines.get(i), width / 2, top + 3 + i * 10, Theme.withAlpha(Theme.TEXT, Math.max(0.1F, alpha)));
			}
		}
		return top;
	}

	/** Speed label in the game language, see {@link GameLocale#speed(double)}. */
	public static String formatSpeed(double speed) {
		return GameLocale.speed(speed);
	}
}
