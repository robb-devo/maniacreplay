package dev.robb.streamerreplay.render;

/**
 * "Clean frames" for video export: while active, everything that is about the viewer rather than the recording
 * is left out of the image: name tags, the block selection outline, the hand and 3D crosshair, and debug
 * renderers such as hitboxes and chunk borders (F3+B / F3+G). The 2D HUD and menus are never in exported
 * frames anyway, because frames are read before the GUI is drawn.
 */
public final class CleanFrames {
	private CleanFrames() {
	}

	public static boolean active() {
		VideoExporter exporter = VideoExporter.current();
		return VideoExporter.isExporting() && exporter != null && exporter.cleanFrames();
	}
}
