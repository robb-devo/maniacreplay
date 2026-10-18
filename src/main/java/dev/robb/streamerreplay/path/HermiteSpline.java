package dev.robb.streamerreplay.path;

/**
 * Non-uniform cubic Hermite spline with Catmull-Rom style tangents.
 *
 * <p>Tangents are the time-weighted average of the neighbouring secant slopes, which gives smooth (C1)
 * motion even when keyframes are unevenly spaced in time. End points use one-sided slopes. Optionally the
 * tangents are limited (Fritsch-Carlson) to avoid overshoot, which is what we want for FOV.
 */
public final class HermiteSpline {
	private final double[] times;
	private final double[] values;
	private final double[] tangents;

	public HermiteSpline(double[] times, double[] values, boolean monotone) {
		if (times.length != values.length || times.length == 0) {
			throw new IllegalArgumentException("times and values must be non-empty and of equal length");
		}
		this.times = times.clone();
		this.values = values.clone();
		this.tangents = computeTangents(this.times, this.values, monotone);
	}

	private static double[] computeTangents(double[] t, double[] v, boolean monotone) {
		int n = t.length;
		double[] m = new double[n];
		if (n == 1) {
			return m;
		}
		double[] secant = new double[n - 1];
		for (int i = 0; i < n - 1; i++) {
			double dt = t[i + 1] - t[i];
			secant[i] = dt <= 0 ? 0 : (v[i + 1] - v[i]) / dt;
		}
		m[0] = secant[0];
		m[n - 1] = secant[n - 2];
		for (int i = 1; i < n - 1; i++) {
			double dtPrev = t[i] - t[i - 1];
			double dtNext = t[i + 1] - t[i];
			double total = dtPrev + dtNext;
			m[i] = total <= 0 ? 0 : (secant[i - 1] * dtNext + secant[i] * dtPrev) / total;
		}
		if (monotone) {
			// Fritsch-Carlson: flat at local extrema, then limit the slopes so that no segment overshoots.
			for (int i = 1; i < n - 1; i++) {
				if (secant[i - 1] * secant[i] <= 0) {
					m[i] = 0;
				}
			}
			for (int i = 0; i < n - 1; i++) {
				if (secant[i] == 0) {
					m[i] = 0;
					m[i + 1] = 0;
				} else {
					double a = m[i] / secant[i];
					double b = m[i + 1] / secant[i];
					double s = a * a + b * b;
					if (s > 9) {
						double tau = 3 / Math.sqrt(s);
						m[i] = tau * a * secant[i];
						m[i + 1] = tau * b * secant[i];
					}
				}
			}
		}
		return m;
	}

	/** Evaluates the spline; times outside the key range are clamped to the first/last value. */
	public double evaluate(double time) {
		int n = times.length;
		if (n == 1 || time <= times[0]) {
			return values[0];
		}
		if (time >= times[n - 1]) {
			return values[n - 1];
		}
		int i = segment(time);
		double t0 = times[i];
		double dt = times[i + 1] - t0;
		if (dt <= 0) {
			return values[i + 1];
		}
		double s = (time - t0) / dt;
		double s2 = s * s;
		double s3 = s2 * s;
		double h00 = 2 * s3 - 3 * s2 + 1;
		double h10 = s3 - 2 * s2 + s;
		double h01 = -2 * s3 + 3 * s2;
		double h11 = s3 - s2;
		return h00 * values[i] + h10 * dt * tangents[i] + h01 * values[i + 1] + h11 * dt * tangents[i + 1];
	}

	/** Binary search for the segment index i with times[i] <= time < times[i+1]. */
	private int segment(double time) {
		int lo = 0;
		int hi = times.length - 2;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (times[mid] <= time) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return lo;
	}
}
