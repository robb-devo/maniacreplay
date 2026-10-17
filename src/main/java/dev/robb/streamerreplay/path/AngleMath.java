package dev.robb.streamerreplay.path;

/** Angle helpers that keep interpolation on the shortest way around the circle. */
public final class AngleMath {
	private AngleMath() {
	}

	/** Wraps degrees into [-180, 180). */
	public static double wrapDegrees(double degrees) {
		double wrapped = degrees % 360.0;
		if (wrapped >= 180.0) {
			wrapped -= 360.0;
		}
		if (wrapped < -180.0) {
			wrapped += 360.0;
		}
		return wrapped;
	}

	/** Returns {@code target} shifted by full turns so it is within 180° of {@code reference}. */
	public static double unwrapNear(double reference, double target) {
		return reference + wrapDegrees(target - reference);
	}
}
