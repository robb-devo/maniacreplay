package dev.robb.streamerreplay.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared colours and tiny drawing helpers so every screen looks the same. */
public final class Theme {
	public static final int PANEL = 0xC80E0F14;
	public static final int PANEL_BORDER = 0x40FFFFFF;
	public static final int TRACK = 0xFF2A2D36;
	public static final int TRACK_PLAYED = 0xFF4FA3FF;
	public static final int TRACK_BUFFERED = 0xFF3A4A66;
	public static final int PLAYHEAD = 0xFFFFFFFF;
	public static final int MARKER = 0xFFFFC23D;
	public static final int KEYFRAME = 0xFF59E3A7;
	public static final int KEYFRAME_SELECTED = 0xFFFFFFFF;
	public static final int PATH_RANGE = 0x8059E3A7;
	public static final int RECORDING = 0xFFFF4040;
	public static final int TEXT = 0xFFFFFFFF;
	public static final int TEXT_DIM = 0xFFA8ADBA;
	public static final int TEXT_FAINT = 0xFF6E7380;
	public static final int ERROR = 0xFFFF6B6B;
	public static final int SUCCESS = 0xFF6BE38A;

	private Theme() {
	}

	/** Translucent panel with a one pixel top highlight. */
	public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		g.fill(x0, y0, x1, y1, PANEL);
		g.horizontalLine(x0, x1 - 1, y0, PANEL_BORDER);
	}

	/** Small diamond, used for keyframes. */
	public static void diamond(GuiGraphicsExtractor g, int cx, int cy, int radius, int color) {
		for (int dy = -radius; dy <= radius; dy++) {
			int w = radius - Math.abs(dy);
			g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
		}
	}

	/** Horizontal progress bar. */
	public static void progressBar(GuiGraphicsExtractor g, int x, int y, int width, int height, float progress, int color) {
		g.fill(x, y, x + width, y + height, TRACK);
		int filled = Math.round(width * Math.max(0, Math.min(1, progress)));
		if (filled > 0) {
			g.fill(x, y, x + filled, y + height, color);
		}
	}

	public static int withAlpha(int color, float alpha) {
		int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
		return (a << 24) | (color & 0xFFFFFF);
	}
}
