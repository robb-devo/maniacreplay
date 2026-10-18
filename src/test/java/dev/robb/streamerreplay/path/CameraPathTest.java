package dev.robb.streamerreplay.path;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CameraPathTest {
	private static CameraState at(double x, float yaw) {
		return new CameraState(x, 64, 0, yaw, 0, 0, 70);
	}

	@Test
	void splinePassesThroughKeysAndIsSmooth() {
		double[] t = {0, 1000, 1500, 4000};
		double[] v = {0, 10, 5, 20};
		HermiteSpline spline = new HermiteSpline(t, v);
		for (int i = 0; i < t.length; i++) {
			assertEquals(v[i], spline.evaluate(t[i]), 1e-9);
		}
		// C1 continuity: left and right derivative at an interior key match.
		double eps = 1e-3;
		double left = (spline.evaluate(1000) - spline.evaluate(1000 - eps)) / eps;
		double right = (spline.evaluate(1000 + eps) - spline.evaluate(1000)) / eps;
		assertEquals(left, right, 1e-3);
		assertEquals(0, spline.evaluate(-50));
		assertEquals(20, spline.evaluate(99999));
	}

	@Test
	void monotoneSplineDoesNotOvershoot() {
		double[] t = {0, 100, 200, 300};
		double[] v = {70, 70, 30, 30};
		HermiteSpline spline = new HermiteSpline(t, v);
		for (int ms = 0; ms <= 300; ms++) {
			double value = spline.evaluate(ms);
			assertTrue(value <= 70 + 1e-9 && value >= 30 - 1e-9, "overshoot at " + ms + ": " + value);
		}
	}

	@Test
	void unevenlySpacedKeyframesDoNotSwingPastTheirPositions() {
		// A real path from a 46 min recording: a 2 s move set while paused, then keys minutes apart. The camera used to
		// swing about 75 blocks past keyframe 3 on the z axis.
		CameraPath path = new CameraPath();
		path.setAll(List.of(
			new Keyframe(0, 21_200, new CameraState(-574.5, 75.9, 662.4, 126, 24, 0, 70)),
			new Keyframe(2_000, 21_200, new CameraState(-572.8, 75.9, 658.6, 101, 23, 0, 70)),
			new Keyframe(291_200, 310_400, new CameraState(-582.7, 76.9, 649.2, -9, 35, 0, 70)),
			new Keyframe(1_898_600, 1_917_800, new CameraState(-585.1, 81.6, 691.8, -30, 19, 0, 70))));
		for (int i = 0; i + 1 < path.size(); i++) {
			CameraState a = path.get(i).camera();
			CameraState b = path.get(i + 1).camera();
			long from = path.get(i).pathTimeMs();
			long to = path.get(i + 1).pathTimeMs();
			for (int step = 0; step <= 400; step++) {
				CameraState c = path.sample(from + (to - from) * step / 400.0).camera();
				assertWithin(c.x(), a.x(), b.x(), "x", i);
				assertWithin(c.y(), a.y(), b.y(), "y", i);
				assertWithin(c.z(), a.z(), b.z(), "z", i);
			}
		}
	}

	private static void assertWithin(double value, double a, double b, String axis, int segment) {
		assertTrue(value >= Math.min(a, b) - 1e-6 && value <= Math.max(a, b) + 1e-6,
			axis + " left the range of keyframes " + (segment + 1) + "-" + (segment + 2) + ": " + value);
	}

	@Test
	void pathTimeAtInvertsTheReplayTimeline() {
		CameraPath path = new CameraPath();
		path.addAt(10_000, at(0, 0));   // path 0
		path.addAt(20_000, at(10, 0));  // path 10 000, follows the replay 1:1
		path.addAt(20_000, at(20, 0));  // path 12 000, a move through frozen time
		path.addAt(30_000, at(30, 0));  // path 22 000
		assertEquals(0, path.pathTimeAt(5_000), 1e-9, "before the path: its start");
		assertEquals(5_000, path.pathTimeAt(15_000), 1e-9);
		assertEquals(10_000, path.pathTimeAt(20_000), 1e-9, "a frozen moment maps to the start of its move");
		assertEquals(17_000, path.pathTimeAt(25_000), 1e-9);
		assertEquals(22_000, path.pathTimeAt(99_000), 1e-9, "after the path: its end");
		for (double t : new double[] {12_345, 24_000, 29_999}) {
			assertEquals(t, path.sample(path.pathTimeAt(t)).replayTimeMs(), 1e-6, "round trip at " + t);
		}
	}

	@Test
	void monotoneSplineStaysFlatAtAPeak() {
		// Uneven spacing around a peak made the averaged tangent point the wrong way (FOV 70 -> 90 -> 30 went to ~94).
		double[] t = {0, 1000, 1100};
		double[] v = {70, 90, 30};
		HermiteSpline spline = new HermiteSpline(t, v);
		for (int ms = 0; ms <= 1100; ms++) {
			double value = spline.evaluate(ms);
			assertTrue(value <= 90 + 1e-9 && value >= 30 - 1e-9, "overshoot at " + ms + ": " + value);
		}
	}

	@Test
	void yawTakesShortestWayAroundTheCircle() {
		CameraPath path = new CameraPath();
		path.addAt(0, at(0, 170));
		path.addAt(1000, at(0, -170));
		float middle = path.sample(500).camera().yaw();
		assertEquals(180, Math.abs(middle), 0.5);
	}

	@Test
	void runningTimeKeyframesFollowReplayTime() {
		CameraPath path = new CameraPath();
		path.addAt(5000, at(0, 0));
		path.addAt(8000, at(30, 0));
		assertEquals(0, path.get(0).pathTimeMs());
		assertEquals(3000, path.get(1).pathTimeMs());
		CameraPath.Sample sample = path.sample(1500);
		assertEquals(6500, sample.replayTimeMs(), 1e-6);
	}

	@Test
	void pausedTimeKeyframesCreateFrozenMove() {
		CameraPath path = new CameraPath();
		path.addAt(5000, at(0, 0));
		path.addAt(5000, at(10, 0));
		assertEquals(CameraPath.FROZEN_MOVE_MS, path.get(1).pathTimeMs());
		CameraPath.Sample sample = path.sample(1000);
		assertEquals(5000, sample.replayTimeMs(), 1e-6);
		assertEquals(5, sample.camera().x(), 1e-6);
	}

	@Test
	void insertingEarlierKeyframesKeepsBothTimelinesSorted() {
		CameraPath path = new CameraPath();
		path.addAt(5000, at(0, 0));
		path.addAt(9000, at(40, 0));
		path.addAt(7000, at(20, 0)); // between
		path.addAt(1000, at(-40, 0)); // before the first
		long lastPath = Long.MIN_VALUE;
		long lastReplay = Long.MIN_VALUE;
		for (Keyframe k : path.keyframes()) {
			assertTrue(k.pathTimeMs() > lastPath);
			assertTrue(k.replayTimeMs() >= lastReplay);
			lastPath = k.pathTimeMs();
			lastReplay = k.replayTimeMs();
		}
		assertEquals(4, path.size());
		assertEquals(1000, path.get(0).replayTimeMs());
		assertEquals(8000, path.durationMs());
	}

	@Test
	void jsonRoundTrip() {
		Gson gson = new Gson();
		CameraPath path = new CameraPath();
		path.addAt(0, new CameraState(1, 2, 3, 45, -10, 5, 80));
		path.addAt(1000, new CameraState(4, 5, 6, 90, 10, 0, 60));
		String json = CameraPathJson.write(gson, path);
		CameraPath read = new CameraPath();
		CameraPathJson.read(gson, json, read);
		assertEquals(path.keyframes(), read.keyframes());
	}

	@Test
	void angleMath() {
		assertEquals(-170, AngleMath.wrapDegrees(190), 1e-9);
		assertEquals(190, AngleMath.unwrapNear(170, -170), 1e-9);
	}
}
