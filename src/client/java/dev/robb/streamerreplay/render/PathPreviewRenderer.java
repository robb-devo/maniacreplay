package dev.robb.streamerreplay.render;

import dev.robb.streamerreplay.path.CameraPath;
import dev.robb.streamerreplay.path.CameraState;
import dev.robb.streamerreplay.path.Keyframe;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.ReplayEditorScreen;
import dev.robb.streamerreplay.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the camera path into the world while the editor is open: the curve, a dot plus view direction per
 * keyframe and its number. The sampled curve is cached until the path changes.
 */
public final class PathPreviewRenderer {
	private static final int MAX_SEGMENTS = 400;
	private static final int CURVE_COLOR = Theme.KEYFRAME;
	private static final int DIRECTION_COLOR = 0xFFFFFFFF;

	private static Vec3[] curve = new Vec3[0];
	private static int cachedVersion = -1;
	private static CameraPath cachedPath;

	private PathPreviewRenderer() {
	}

	/** Called from Fabric's level extraction event (render thread). */
	public static void extract() {
		ReplaySession session = ReplayManager.session();
		Minecraft mc = Minecraft.getInstance();
		if (session == null || VideoExporter.isExporting() || session.isPlayingPath() || !(mc.gui.screen() instanceof ReplayEditorScreen)) {
			return;
		}
		CameraPath path = session.path();
		if (path.isEmpty()) {
			return;
		}
		if (path != cachedPath || path.version() != cachedVersion) {
			rebuild(path);
		}
		for (int i = 1; i < curve.length; i++) {
			Gizmos.line(curve[i - 1], curve[i], CURVE_COLOR, 2.0F);
		}
		for (int i = 0; i < path.size(); i++) {
			Keyframe keyframe = path.get(i);
			CameraState c = keyframe.camera();
			Vec3 pos = new Vec3(c.x(), c.y(), c.z());
			Gizmos.point(pos, CURVE_COLOR, 6.0F);
			Gizmos.arrow(pos, pos.add(Vec3.directionFromRotation(c.pitch(), c.yaw()).scale(0.8)), DIRECTION_COLOR, 1.5F);
			Gizmos.billboardText(Integer.toString(i + 1), pos.add(0, 0.35, 0), TextGizmo.Style.whiteAndCentered());
		}
	}

	private static void rebuild(CameraPath path) {
		cachedPath = path;
		cachedVersion = path.version();
		if (path.size() < 2) {
			curve = new Vec3[0];
			return;
		}
		long duration = path.durationMs();
		int segments = (int) Math.min(MAX_SEGMENTS, Math.max(8, duration / 50));
		Vec3[] points = new Vec3[segments + 1];
		long start = path.startPathTime();
		for (int i = 0; i <= segments; i++) {
			CameraState c = path.sample(start + duration * (double) i / segments).camera();
			points[i] = new Vec3(c.x(), c.y(), c.z());
		}
		curve = points;
	}
}
