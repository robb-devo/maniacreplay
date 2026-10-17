package dev.robb.streamerreplay.ui.hud;

import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.input.ReplayKeys;
import dev.robb.streamerreplay.render.VideoExporter;
import dev.robb.streamerreplay.replay.ReplayCamera;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.Theme;
import dev.robb.streamerreplay.ui.widget.TimelineWidget;
import dev.robb.streamerreplay.util.TimeFormat;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** Minimal in-world overlay while watching a replay without the editor open. */
public final class ReplayHud implements HudElement {
	private static final long HINT_VISIBLE_MS = 10_000;
	private static final long HINT_FADE_MS = 1_500;

	private long hintShownAt = -1;
	private ReplaySession hintSession;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		ReplaySession session = ReplayManager.session();
		if (session == null || !session.isInWorld(mc) || mc.gui.screen() != null || VideoExporter.isExporting()) {
			return;
		}
		Font font = mc.font;
		int width = g.guiWidth();
		int height = g.guiHeight();

		if (session.isFastForwarding()) {
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

		drawHint(g, font, session, width, barTop);
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

	private void drawHint(GuiGraphicsExtractor g, Font font, ReplaySession session, int width, int barTop) {
		if (!ReplayConfig.get().showControlsHint) {
			return;
		}
		long now = System.currentTimeMillis();
		if (hintSession != session) {
			hintSession = session;
			hintShownAt = now;
		}
		long age = now - hintShownAt;
		if (age > HINT_VISIBLE_MS + HINT_FADE_MS) {
			return;
		}
		float alpha = age < HINT_VISIBLE_MS ? 1 : 1 - (age - HINT_VISIBLE_MS) / (float) HINT_FADE_MS;
		Component hint = Component.translatable("streamerreplay.hud.hint",
			ReplayKeys.PLAY_PAUSE.getTranslatedKeyMessage(),
			ReplayKeys.EDITOR.getTranslatedKeyMessage(),
			ReplayKeys.SEEK_BACK.getTranslatedKeyMessage(),
			ReplayKeys.SEEK_FORWARD.getTranslatedKeyMessage());
		int hw = font.width(hint) + 12;
		g.fill(width / 2 - hw / 2, barTop - 16, width / 2 + hw / 2, barTop - 3, Theme.withAlpha(Theme.PANEL, alpha));
		if (alpha > 0.05F) {
			g.centeredText(font, hint, width / 2, barTop - 13, Theme.withAlpha(Theme.TEXT, Math.max(0.1F, alpha)));
		}
	}

	/** 1 -> "1x", 0.25 -> "0.25x", 1.5 -> "1.5x". */
	public static String formatSpeed(double speed) {
		String text = String.format(java.util.Locale.ROOT, "%.2f", speed);
		text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
		return text + "x";
	}
}
