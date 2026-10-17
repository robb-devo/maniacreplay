package dev.robb.streamerreplay.ui;

import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.zip.ZipException;

/**
 * An I/O error with a translated, user-facing explanation. {@link #describe(Throwable)} turns any error into
 * text for the UI: our own errors are translated, and common system errors get a translated explanation, with the
 * technical detail only as a suffix.
 */
public final class LocalizedIOException extends IOException {
	private final transient Component text;

	public LocalizedIOException(String key, Object... args) {
		super(key); // the key keeps log output language-independent
		this.text = Component.translatable(key, args);
	}

	public Component text() {
		return text;
	}

	/** User-facing text for any error. */
	public static Component describe(Throwable error) {
		Throwable e = error;
		while (e.getCause() != null && !(e instanceof LocalizedIOException) && !(e instanceof IOException)) {
			e = e.getCause();
		}
		if (e instanceof LocalizedIOException localized) {
			return localized.text();
		}
		if (e instanceof AccessDeniedException) {
			return Component.translatable("streamerreplay.error.access_denied", String.valueOf(((AccessDeniedException) e).getFile()));
		}
		if (e instanceof NoSuchFileException missing) {
			return Component.translatable("streamerreplay.error.file_missing", String.valueOf(missing.getFile()));
		}
		if (e instanceof FileAlreadyExistsException) {
			return Component.translatable("streamerreplay.error.name_taken");
		}
		if (e instanceof ZipException) {
			return Component.translatable("streamerreplay.error.corrupt");
		}
		String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		return Component.translatable("streamerreplay.error.generic", detail);
	}
}
