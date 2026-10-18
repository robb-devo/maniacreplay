package dev.robb.streamerreplay.ui.widget;

import dev.robb.streamerreplay.format.Marker;
import dev.robb.streamerreplay.path.Keyframe;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.Theme;
import dev.robb.streamerreplay.util.TimeFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The replay timeline: progress, markers, camera keyframes and the playhead.
 *
 * <p>Click or drag to scrub; the seek happens on release so that dragging backwards does not rebuild the world on
 * every mouse move. Clicking a keyframe diamond selects it, clicking a marker jumps exactly to it.
 */
public final class TimelineWidget extends AbstractWidget {
	private static final int KEYFRAME_ROW = 7;
	private static final int TRACK_HEIGHT = 5;

	private final ReplaySession session;
	private final IntConsumer onKeyframeClicked;
	private int selectedKeyframe = -1;
	private boolean dragging;
	private double dragTime;

	public TimelineWidget(int x, int y, int width, ReplaySession session, IntConsumer onKeyframeClicked) {
		super(x, y, width, KEYFRAME_ROW + TRACK_HEIGHT + 4, Component.translatable("streamerreplay.editor.timeline"));
		this.session = session;
		this.onKeyframeClicked = onKeyframeClicked;
	}

	public void setSelectedKeyframe(int index) {
		selectedKeyframe = index;
	}

	private long duration() {
		return Math.max(1, session.clock().durationMs());
	}

	private int xOf(double timeMs) {
		return getX() + (int) Math.round(Math.max(0, Math.min(1, timeMs / duration())) * (getWidth() - 1));
	}

	private double timeAt(double mouseX) {
		double t = (mouseX - getX()) / (double) Math.max(1, getWidth() - 1);
		return Math.max(0, Math.min(1, t)) * duration();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x0 = getX();
		int x1 = getX() + getWidth();
		int trackY = getY() + KEYFRAME_ROW;
		double now = dragging ? dragTime : session.clock().timeMs();

		// track
		Theme.progressBar(g, x0, trackY, getWidth(), TRACK_HEIGHT, (float) (now / duration()), Theme.TRACK_PLAYED);

		// camera path range + keyframes
		List<Keyframe> keyframes = session.path().keyframes();
		if (keyframes.size() >= 2) {
			int from = xOf(keyframes.getFirst().replayTimeMs());
			int to = xOf(keyframes.getLast().replayTimeMs());
			g.fill(Math.min(from, to), getY() + 3, Math.max(from, to) + 1, getY() + 4, Theme.PATH_RANGE);
		}
		int hoveredKeyframe = keyframeAt(mouseX, mouseY);
		for (int i = 0; i < keyframes.size(); i++) {
			int color = i == selectedKeyframe || i == hoveredKeyframe ? Theme.KEYFRAME_SELECTED : Theme.KEYFRAME;
			int kx = xOf(keyframes.get(i).replayTimeMs());
			Theme.diamond(g, kx, getY() + 3, 4, 0x90000000); // outline: crisp on the track and on the world behind
			Theme.diamond(g, kx, getY() + 3, 3, color);
		}

		// markers
		Marker hoveredMarker = null;
		for (Marker marker : session.markers()) {
			int mx = xOf(marker.timeMs());
			boolean hot = isHovered() && Math.abs(mouseX - mx) <= 2 && mouseY >= trackY - 1;
			if (hot) {
				hoveredMarker = marker;
			}
			Theme.flag(g, mx, trackY - 3, trackY + TRACK_HEIGHT + 1, hot ? Theme.TEXT : Theme.MARKER);
		}

		// where a click would jump to
		if (isHovered() && !dragging && hoveredMarker == null && hoveredKeyframe < 0) {
			int hx = Math.max(x0, Math.min(x1 - 1, mouseX));
			g.fill(hx, trackY, hx + 1, trackY + TRACK_HEIGHT, 0x90FFFFFF);
		}

		// playhead, with a dark outline so it stays visible on every colour
		int px = xOf(now);
		g.fill(px - 2, trackY - 3, px + 2, trackY + TRACK_HEIGHT + 3, 0x90000000);
		g.fill(px - 1, trackY - 2, px + 1, trackY + TRACK_HEIGHT + 2, Theme.PLAYHEAD);

		if (dragging || isHovered()) {
			double hoverTime = dragging ? dragTime : timeAt(mouseX);
			Component tip = Component.literal(TimeFormat.clock((long) hoverTime));
			if (hoveredMarker != null && !dragging) {
				tip = Component.literal(hoveredMarker.name() + "  ").append(Component.literal(TimeFormat.clock(hoveredMarker.timeMs())).withColor(Theme.TEXT_DIM));
			} else if (hoveredKeyframe >= 0 && !dragging) {
				tip = Component.translatable("streamerreplay.editor.keyframe_tooltip", hoveredKeyframe + 1,
					TimeFormat.clock(keyframes.get(hoveredKeyframe).replayTimeMs()));
			}
			g.setTooltipForNextFrame(tip, mouseX, getY() - 4);
		}
	}

	private int keyframeAt(double mouseX, double mouseY) {
		if (mouseY < getY() - 1 || mouseY > getY() + KEYFRAME_ROW || mouseX < getX() - 4 || mouseX > getX() + getWidth() + 4) {
			return -1;
		}
		List<Keyframe> keyframes = session.path().keyframes();
		int best = -1;
		double bestDistance = 4;
		for (int i = 0; i < keyframes.size(); i++) {
			double d = Math.abs(mouseX - xOf(keyframes.get(i).replayTimeMs()));
			if (d <= bestDistance) {
				best = i;
				bestDistance = d;
			}
		}
		return best;
	}

	private @Nullable Marker markerAt(double mouseX, double mouseY) {
		int trackY = getY() + KEYFRAME_ROW;
		if (mouseY < trackY - 1 || mouseY > trackY + TRACK_HEIGHT + 2) {
			return null;
		}
		for (Marker marker : session.markers()) {
			if (Math.abs(mouseX - xOf(marker.timeMs())) <= 2) {
				return marker;
			}
		}
		return null;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		int keyframe = keyframeAt(event.x(), event.y());
		if (keyframe >= 0) {
			onKeyframeClicked.accept(keyframe);
			return;
		}
		Marker marker = markerAt(event.x(), event.y());
		if (marker != null) {
			session.seek(marker.timeMs()); // snap: hitting a 1 px line exactly is hard
			return;
		}
		dragging = true;
		dragTime = timeAt(event.x());
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			dragTime = timeAt(event.x());
		}
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		if (dragging) {
			dragging = false;
			session.scrub(timeAt(event.x()));
		}
	}

	@Override
	public void playDownSound(SoundManager soundManager) {
		// scrubbing should be silent
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.translatable("streamerreplay.editor.timeline_narration",
			TimeFormat.clock((long) session.clock().timeMs()), TimeFormat.clock(session.clock().durationMs())));
	}

	/** Draws a compact, non-interactive version of the timeline (used by the in-world HUD). */
	public static void drawCompact(GuiGraphicsExtractor g, ReplaySession session, int x, int y, int width) {
		long duration = Math.max(1, session.clock().durationMs());
		double now = session.clock().timeMs();
		Theme.progressBar(g, x, y, width, 3, (float) Math.min(1, now / duration), Theme.TRACK_PLAYED);
		for (Marker marker : session.markers()) {
			int mx = x + (int) ((width - 1) * Math.min(1, marker.timeMs() / (double) duration));
			Theme.flag(g, mx, y - 3, y + 4, Theme.MARKER);
		}
		for (Keyframe keyframe : session.path().keyframes()) {
			int kx = x + (int) ((width - 1) * Math.min(1, keyframe.replayTimeMs() / (double) duration));
			g.fill(kx, y - 2, kx + 1, y, Theme.KEYFRAME);
		}
		int px = x + (int) ((width - 1) * Math.min(1, now / duration));
		g.fill(px - 1, y - 2, px + 2, y + 5, 0x90000000);
		g.fill(px, y - 1, px + 1, y + 4, Theme.PLAYHEAD);
	}

	static Minecraft mc() {
		return Minecraft.getInstance();
	}
}
