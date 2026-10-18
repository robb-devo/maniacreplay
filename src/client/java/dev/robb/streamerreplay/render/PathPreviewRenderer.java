package dev.robb.streamerreplay.render;

import dev.robb.streamerreplay.path.CameraPath;
import dev.robb.streamerreplay.path.CameraState;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.Theme;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Draws the camera path on top of the world while the editor is open: a dotted curve, a marker and number per
 * keyframe, and a short tick showing where each keyframe looks.
 *
 * <p>This is drawn as a 2D overlay. The path points are projected through the current camera, so the overlay
 * does not depend on the world renderer. It therefore looks the same with Sodium, Iris shader packs or any other
 * renderer. The sampled curve is cached until the path changes. Drawing reuses one scratch array and allocates
 * nothing per frame.
 */
public final class PathPreviewRenderer {
	private static final int MAX_SAMPLES = 600;
	private static final double NEAR = 0.15;
	private static final int DOT_COLOR = Theme.KEYFRAME;
	private static final int SHADOW = 0x90000000;

	private static double[] curve = new double[0]; // x,y,z triples
	private static int cachedVersion = -1;
	private static CameraPath cachedPath;
	private static final double[] scratch = new double[2];

	private PathPreviewRenderer() {
	}

	/** Called from the editor screen's background pass (below its panel and widgets), in GUI coordinates. */
	/** {@code selected}: the keyframe chosen in the editor (highlighted), or -1. */
	public static void draw(GuiGraphicsExtractor g, Font font, ReplaySession session, int width, int height, int selected) {
		CameraPath path = session.path();
		if (path.isEmpty() || session.isPlayingPath() || VideoExporter.isExporting()) {
			return;
		}
		if (path != cachedPath || path.version() != cachedVersion) {
			rebuild(path);
		}
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Projector p = PROJECTOR;
		p.setup(camera, width, height);

		// dotted curve: one 2x2 dot per sample that lands on screen (with a dark outline for contrast); the dots get
		// lighter towards the end, so the direction of the flight is visible at a glance
		for (int i = 0; i + 2 < curve.length; i += 3) {
			if (p.project(curve[i], curve[i + 1], curve[i + 2], scratch)) {
				int x = (int) scratch[0];
				int y = (int) scratch[1];
				g.fill(x - 2, y - 2, x + 2, y + 2, SHADOW);
				g.fill(x - 1, y - 1, x + 1, y + 1, towardsWhite(DOT_COLOR, 0.6F * i / Math.max(1, curve.length - 3)));
			}
		}
		for (int i = 0; i < path.size(); i++) {
			CameraState c = path.get(i).camera();
			if (!p.project(c.x(), c.y(), c.z(), scratch)) {
				continue;
			}
			int x = (int) scratch[0];
			int y = (int) scratch[1];
			Vec3 look = Vec3.directionFromRotation(c.pitch(), c.yaw());
			if (p.project(c.x() + look.x * 0.9, c.y() + look.y * 0.9, c.z() + look.z * 0.9, scratch)) {
				drawDottedLine(g, x, y, (int) scratch[0], (int) scratch[1]);
			}
			boolean chosen = i == selected;
			g.fill(x - 5, y - 5, x + 5, y + 5, chosen ? Theme.MARKER : SHADOW);
			g.fill(x - 4, y - 4, x + 4, y + 4, chosen ? Theme.TEXT : DOT_COLOR);
			// the number sits on a small dark plate, readable on sky, sand and snow alike
			String label = Integer.toString(i + 1);
			int lw = font.width(label);
			int ly = y - 17;
			g.fill(x - lw / 2 - 3, ly - 2, x - lw / 2 + lw + 3, ly + 9, 0xA0000000);
			g.text(font, label, x - lw / 2, ly, chosen ? Theme.MARKER : Theme.TEXT, false);
		}
	}

	private static int towardsWhite(int color, float amount) {
		int r = (color >> 16) & 0xFF;
		int gr = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		r += Math.round((255 - r) * amount);
		gr += Math.round((255 - gr) * amount);
		b += Math.round((255 - b) * amount);
		return (color & 0xFF000000) | (r << 16) | (gr << 8) | b;
	}

	/** Diagnostics for tests: where each keyframe lands on a screen of the given size. */
	public static String describeProjection(ReplaySession session, int width, int height) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		PROJECTOR.setup(camera, width, height);
		StringBuilder sb = new StringBuilder("camera=" + camera.position() + " fov=" + camera.getFov() + " fwd=" + camera.forwardVector());
		double[] out = new double[2];
		for (int i = 0; i < session.path().size(); i++) {
			CameraState c = session.path().get(i).camera();
			boolean ok = PROJECTOR.project(c.x(), c.y(), c.z(), out);
			sb.append(" | kf").append(i + 1).append(" (").append((int) c.x()).append(',').append((int) c.y()).append(',').append((int) c.z())
				.append(") -> ").append(ok ? (int) out[0] + "," + (int) out[1] : "off");
		}
		return sb.toString();
	}

	private static void drawDottedLine(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) / 2;
		for (int s = 1; s <= steps; s++) {
			int x = x0 + (x1 - x0) * s / steps;
			int y = y0 + (y1 - y0) * s / steps;
			g.fill(x, y, x + 1, y + 1, 0xFFFFFFFF);
		}
	}

	private static void rebuild(CameraPath path) {
		cachedPath = path;
		cachedVersion = path.version();
		if (path.size() < 2) {
			curve = new double[0];
			return;
		}
		long duration = path.durationMs();
		int samples = (int) Math.min(MAX_SAMPLES, Math.max(16, duration / 25));
		double[] points = new double[(samples + 1) * 3];
		long start = path.startPathTime();
		for (int i = 0; i <= samples; i++) {
			CameraState c = path.sample(start + duration * (double) i / samples).camera();
			points[i * 3] = c.x();
			points[i * 3 + 1] = c.y();
			points[i * 3 + 2] = c.z();
		}
		curve = points;
	}

	private static final Projector PROJECTOR = new Projector();

	/** Perspective projection with the camera's own axes (which already include roll). */
	static final class Projector {
		private double cx, cy, cz, fx, fy, fz, ux, uy, uz, lx, ly, lz, focal, halfW, halfH;

		void setup(Camera camera, int width, int height) {
			Vec3 pos = camera.position();
			cx = pos.x;
			cy = pos.y;
			cz = pos.z;
			Vector3fc f = camera.forwardVector();
			Vector3fc u = camera.upVector();
			Vector3fc l = camera.leftVector();
			fx = f.x();
			fy = f.y();
			fz = f.z();
			ux = u.x();
			uy = u.y();
			uz = u.z();
			lx = l.x();
			ly = l.y();
			lz = l.z();
			halfW = width / 2.0;
			halfH = height / 2.0;
			focal = halfH / Math.tan(Math.toRadians(camera.getFov()) / 2.0);
		}

		/** Writes GUI coordinates into {@code out}; false if the point is behind the camera or far off screen. */
		boolean project(double x, double y, double z, double[] out) {
			double dx = x - cx, dy = y - cy, dz = z - cz;
			double depth = dx * fx + dy * fy + dz * fz;
			if (depth < NEAR) {
				return false;
			}
			double right = -(dx * lx + dy * ly + dz * lz);
			double up = dx * ux + dy * uy + dz * uz;
			double sx = halfW + right / depth * focal;
			double sy = halfH - up / depth * focal;
			if (sx < -50 || sy < -50 || sx > halfW * 2 + 50 || sy > halfH * 2 + 50) {
				return false;
			}
			out[0] = sx;
			out[1] = sy;
			return true;
		}
	}
}
