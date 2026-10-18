package dev.robb.streamerreplay.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * An ordered list of {@link Keyframe}s with smooth interpolation.
 *
 * <p>Both the path time and the replay time are kept non-decreasing, so playing a path never needs to seek
 * backwards in the replay. {@link #addAt(long, CameraState)} implements the simple authoring rule used by the UI:
 * keyframes added while time runs follow the replay at 1:1 speed; keyframes added while time is paused create a
 * camera move through frozen time.
 */
public final class CameraPath {
	/** Duration of a camera move added while replay time is paused. */
	public static final long FROZEN_MOVE_MS = 2000;

	private final List<Keyframe> keyframes = new ArrayList<>();
	private Interpolation cache;
	private int version;

	/** Changes whenever the keyframes change; lets callers cache derived data. */
	public int version() {
		return version;
	}

	public List<Keyframe> keyframes() {
		return Collections.unmodifiableList(keyframes);
	}

	public int size() {
		return keyframes.size();
	}

	public boolean isEmpty() {
		return keyframes.isEmpty();
	}

	/** A path needs at least two keyframes to be played or exported. */
	public boolean isPlayable() {
		return keyframes.size() >= 2;
	}

	/** Replaces the camera of an existing keyframe, keeping its timing. */
	public void replaceCamera(int index, CameraState camera) {
		Keyframe k = keyframes.get(index);
		keyframes.set(index, new Keyframe(k.pathTimeMs(), k.replayTimeMs(), camera));
		cache = null;
		version++;
	}

	public Keyframe get(int index) {
		return keyframes.get(index);
	}

	public long durationMs() {
		return keyframes.isEmpty() ? 0 : keyframes.getLast().pathTimeMs() - keyframes.getFirst().pathTimeMs();
	}

	public void clear() {
		keyframes.clear();
		cache = null;
		version++;
	}

	/** Replaces all keyframes (e.g. when loading from disk). Input is sorted and sanitised. */
	public void setAll(List<Keyframe> list) {
		keyframes.clear();
		List<Keyframe> sorted = new ArrayList<>(list);
		sorted.sort(Comparator.comparingLong(Keyframe::pathTimeMs));
		long lastReplay = Long.MIN_VALUE;
		for (Keyframe k : sorted) {
			long replay = Math.max(lastReplay, k.replayTimeMs());
			keyframes.add(new Keyframe(k.pathTimeMs(), replay, k.camera()));
			lastReplay = replay;
		}
		cache = null;
		version++;
	}

	/**
	 * Adds a keyframe for the given replay time and camera, choosing its path time automatically.
	 *
	 * @return the index of the inserted keyframe
	 */
	public int addAt(long replayTimeMs, CameraState camera) {
		cache = null;
		version++;
		if (keyframes.isEmpty()) {
			keyframes.add(new Keyframe(0, replayTimeMs, camera));
			return 0;
		}
		Keyframe last = keyframes.getLast();
		if (replayTimeMs > last.replayTimeMs()) {
			keyframes.add(new Keyframe(last.pathTimeMs() + (replayTimeMs - last.replayTimeMs()), replayTimeMs, camera));
			return keyframes.size() - 1;
		}
		if (replayTimeMs == last.replayTimeMs()) {
			keyframes.add(new Keyframe(last.pathTimeMs() + FROZEN_MOVE_MS, replayTimeMs, camera));
			return keyframes.size() - 1;
		}
		Keyframe first = keyframes.getFirst();
		if (replayTimeMs < first.replayTimeMs()) {
			long shift = first.replayTimeMs() - replayTimeMs;
			for (int i = 0; i < keyframes.size(); i++) {
				Keyframe k = keyframes.get(i);
				keyframes.set(i, new Keyframe(k.pathTimeMs() + shift, k.replayTimeMs(), k.camera()));
			}
			keyframes.addFirst(new Keyframe(first.pathTimeMs(), replayTimeMs, camera));
			return 0;
		}
		// Insert after the last keyframe whose replay time is <= the new one.
		int index = 0;
		while (index + 1 < keyframes.size() && keyframes.get(index + 1).replayTimeMs() <= replayTimeMs) {
			index++;
		}
		Keyframe a = keyframes.get(index);
		Keyframe b = keyframes.get(index + 1);
		long pathTime;
		if (b.replayTimeMs() == a.replayTimeMs()) {
			pathTime = (a.pathTimeMs() + b.pathTimeMs()) / 2;
		} else {
			double f = (double) (replayTimeMs - a.replayTimeMs()) / (b.replayTimeMs() - a.replayTimeMs());
			pathTime = a.pathTimeMs() + Math.round((b.pathTimeMs() - a.pathTimeMs()) * f);
		}
		if (pathTime <= a.pathTimeMs() || pathTime >= b.pathTimeMs()) {
			pathTime = (a.pathTimeMs() + b.pathTimeMs()) / 2;
		}
		keyframes.add(index + 1, new Keyframe(pathTime, replayTimeMs, camera));
		return index + 1;
	}

	public void remove(int index) {
		keyframes.remove(index);
		cache = null;
		version++;
	}

	/** Index of the keyframe whose replay time is closest to the given one, or -1 if empty. */
	public int nearestByReplayTime(long replayTimeMs) {
		int best = -1;
		long bestDistance = Long.MAX_VALUE;
		for (int i = 0; i < keyframes.size(); i++) {
			long d = Math.abs(keyframes.get(i).replayTimeMs() - replayTimeMs);
			if (d < bestDistance) {
				bestDistance = d;
				best = i;
			}
		}
		return best;
	}

	/** Samples the path at an absolute path time. Requires at least one keyframe. */
	public Sample sample(double pathTimeMs) {
		if (keyframes.isEmpty()) {
			throw new IllegalStateException("Empty camera path");
		}
		if (cache == null) {
			cache = new Interpolation(keyframes);
		}
		return cache.sample(pathTimeMs);
	}

	public long startPathTime() {
		return keyframes.isEmpty() ? 0 : keyframes.getFirst().pathTimeMs();
	}

	/** Interpolated camera plus the replay time to show at that moment. */
	public record Sample(double replayTimeMs, CameraState camera) {
	}

	/** Precomputed splines, rebuilt lazily after edits. */
	private static final class Interpolation {
		private final HermiteSpline x;
		private final HermiteSpline y;
		private final HermiteSpline z;
		private final HermiteSpline yaw;
		private final HermiteSpline pitch;
		private final HermiteSpline roll;
		private final HermiteSpline fov;
		private final double[] times;
		private final double[] replayTimes;

		Interpolation(List<Keyframe> keys) {
			int n = keys.size();
			times = new double[n];
			replayTimes = new double[n];
			double[] xs = new double[n];
			double[] ys = new double[n];
			double[] zs = new double[n];
			double[] yaws = new double[n];
			double[] pitches = new double[n];
			double[] rolls = new double[n];
			double[] fovs = new double[n];
			for (int i = 0; i < n; i++) {
				Keyframe k = keys.get(i);
				CameraState c = k.camera();
				times[i] = k.pathTimeMs();
				replayTimes[i] = k.replayTimeMs();
				xs[i] = c.x();
				ys[i] = c.y();
				zs[i] = c.z();
				yaws[i] = i == 0 ? c.yaw() : AngleMath.unwrapNear(yaws[i - 1], c.yaw());
				pitches[i] = c.pitch();
				rolls[i] = i == 0 ? c.roll() : AngleMath.unwrapNear(rolls[i - 1], c.roll());
				fovs[i] = c.fov();
			}
			x = new HermiteSpline(times, xs, false);
			y = new HermiteSpline(times, ys, false);
			z = new HermiteSpline(times, zs, false);
			yaw = new HermiteSpline(times, yaws, false);
			pitch = new HermiteSpline(times, pitches, true);
			roll = new HermiteSpline(times, rolls, false);
			fov = new HermiteSpline(times, fovs, true);
		}

		Sample sample(double t) {
			CameraState camera = new CameraState(
				x.evaluate(t), y.evaluate(t), z.evaluate(t),
				(float) AngleMath.wrapDegrees(yaw.evaluate(t)),
				(float) Math.max(-90, Math.min(90, pitch.evaluate(t))),
				(float) AngleMath.wrapDegrees(roll.evaluate(t)),
				(float) Math.max(1, Math.min(170, fov.evaluate(t))));
			return new Sample(linearReplayTime(t), camera);
		}

		private double linearReplayTime(double t) {
			int n = times.length;
			if (n == 1 || t <= times[0]) {
				return replayTimes[0];
			}
			if (t >= times[n - 1]) {
				return replayTimes[n - 1];
			}
			int i = 0;
			while (i + 1 < n && times[i + 1] <= t) {
				i++;
			}
			double dt = times[i + 1] - times[i];
			if (dt <= 0) {
				return replayTimes[i + 1];
			}
			return replayTimes[i] + (replayTimes[i + 1] - replayTimes[i]) * ((t - times[i]) / dt);
		}
	}
}
