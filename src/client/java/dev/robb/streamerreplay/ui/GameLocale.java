package dev.robb.streamerreplay.ui;

import dev.robb.streamerreplay.util.TimeFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Number and date formatting that follows the language selected in Minecraft (not the operating system's).
 * The results are cached per language, so the formatting helpers allocate nothing in the steady state.
 */
public final class GameLocale {
	private static String cachedCode = "";
	private static Locale locale = Locale.ROOT;
	private static DateTimeFormatter dateFormat = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	private GameLocale() {
	}

	/** The Java locale matching the game language, e.g. de_de -> de-DE. */
	public static Locale locale() {
		refresh();
		return locale;
	}

	/** Date and time format of the game language (pattern from the lang file key {@code streamerreplay.date_format}). */
	public static DateTimeFormatter dateFormat() {
		refresh();
		return dateFormat;
	}

	private static void refresh() {
		String code = Minecraft.getInstance().getLanguageManager().getSelected();
		if (code.equals(cachedCode)) {
			return;
		}
		cachedCode = code;
		locale = Locale.forLanguageTag(code.replace('_', '-'));
		String pattern = Language.getInstance().getOrDefault("streamerreplay.date_format", "yyyy-MM-dd HH:mm");
		try {
			dateFormat = DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault());
		} catch (IllegalArgumentException e) {
			dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", locale).withZone(ZoneId.systemDefault());
		}
	}

	/** 1 -> "1x", 0.25 -> "0.25x" (English) or "0,25x" (German). */
	public static String speed(double speed) {
		String text = String.format(locale(), "%.2f", speed);
		char sep = text.charAt(text.length() - 3);
		text = text.replaceAll("0+$", "");
		if (!text.isEmpty() && text.charAt(text.length() - 1) == sep) {
			text = text.substring(0, text.length() - 1);
		}
		return text + "x";
	}

	/** "12.3 MB" / "12,3 MB". */
	public static String bytes(long bytes) {
		return TimeFormat.bytes(bytes, locale());
	}
}
