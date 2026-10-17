package dev.robb.streamerreplay.util;

import java.util.Locale;

/** Human friendly formatting of millisecond durations ("1:05", "1:02:03", "0:05.250"). */
public final class TimeFormat {
	private TimeFormat() {
	}

	/** Formats as {@code m:ss} or {@code h:mm:ss}. Negative values are clamped to zero. */
	public static String clock(long millis) {
		long totalSeconds = Math.max(0, millis) / 1000;
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds / 60) % 60;
		long seconds = totalSeconds % 60;
		if (hours > 0) {
			return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
	}

	/** Like {@link #clock(long)} but with tenths of a second, for precise timeline read-outs. */
	public static String precise(long millis) {
		long clamped = Math.max(0, millis);
		return clock(clamped) + "." + (clamped % 1000) / 100;
	}

	/** Formats a byte count as "12.3 MB". */
	public static String bytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		String[] units = {"KB", "MB", "GB", "TB"};
		double value = bytes;
		int unit = -1;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
	}
}
