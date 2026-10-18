package dev.robb.streamerreplay.render;

import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.ui.LocalizedIOException;
import dev.robb.streamerreplay.util.FfmpegLocator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One-click ffmpeg for Windows, the only network access of the mod, and only when the player clicks "Install".
 *
 * <p>It downloads exactly one pinned file: Gyan's "essentials" build (the one {@code winget install ffmpeg} installs),
 * from Gyan's GitHub releases. The SHA-256 is fixed here, so a changed or damaged download is never run. Only
 * {@code ffmpeg.exe} is unpacked, to {@code .minecraft/ffmpeg/ffmpeg.exe}, where {@link FfmpegLocator} looks.
 */
public final class FfmpegInstaller {
	public static final String VERSION = "9.0.2";
	static final URI DOWNLOAD = URI.create("https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-essentials_build.zip");
	static final String SHA256 = "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba";
	/** Size of the pinned file; also the upper bound for what is accepted. */
	public static final long DOWNLOAD_BYTES = 114_768_076L;

	public enum State { IDLE, DOWNLOADING, UNPACKING, DONE, FAILED, CANCELLED }

	private static volatile State state = State.IDLE;
	private static volatile long downloaded;
	private static volatile @Nullable Component error;
	private static volatile boolean cancelRequested;

	private FfmpegInstaller() {
	}

	/** The pinned build is for Windows; elsewhere the help screen explains the package manager. */
	public static boolean isSupported() {
		return FfmpegLocator.Os.current() == FfmpegLocator.Os.WINDOWS;
	}

	public static State state() {
		return state;
	}

	public static boolean isRunning() {
		return state == State.DOWNLOADING || state == State.UNPACKING;
	}

	/** 0..1 of the download. */
	public static float progress() {
		return Math.min(1, downloaded / (float) DOWNLOAD_BYTES);
	}

	public static @Nullable Component error() {
		return error;
	}

	public static Path target() {
		return FabricLoader.getInstance().getGameDir().resolve("ffmpeg").resolve("ffmpeg.exe");
	}

	/** Starts the download in the background. Does nothing if one is running. */
	public static synchronized void start() {
		if (isRunning() || !isSupported()) {
			return;
		}
		cancelRequested = false;
		downloaded = 0;
		error = null;
		state = State.DOWNLOADING;
		Thread.ofPlatform().name("ManiacReplay-ffmpeg-install").daemon().start(FfmpegInstaller::run);
	}

	public static void cancel() {
		cancelRequested = true;
	}

	/** The UI has shown the result of a finished install; back to idle. */
	public static void acknowledge() {
		if (!isRunning()) {
			state = State.IDLE;
		}
	}

	private static void run() {
		Path folder = target().getParent();
		Path zip = null;
		try {
			Files.createDirectories(folder);
			zip = Files.createTempFile(folder, "ffmpeg-download-", ".zip.part");
			download(zip);
			state = State.UNPACKING;
			unpack(zip);
			state = State.DONE;
			StreamerReplay.LOGGER.info("Installed ffmpeg {} to {}", VERSION, target());
		} catch (IOException | RuntimeException e) {
			if (cancelRequested) {
				state = State.CANCELLED;
			} else {
				StreamerReplay.LOGGER.warn("Could not install ffmpeg", e);
				error = LocalizedIOException.describe(e);
				state = State.FAILED;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			state = State.CANCELLED;
		} finally {
			if (zip != null) {
				try {
					Files.deleteIfExists(zip);
				} catch (IOException ignored) {
					// a leftover .part file is harmless and replaced next time
				}
			}
		}
	}

	private static void download(Path zip) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL) // GitHub serves release files from its download host
			.connectTimeout(Duration.ofSeconds(20))
			.build();
		HttpRequest request = HttpRequest.newBuilder(DOWNLOAD)
			.timeout(Duration.ofSeconds(60))
			.header("User-Agent", "ManiacReplay/" + StreamerReplay.version())
			.GET()
			.build();
		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		MessageDigest sha256 = sha256();
		try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(zip)) {
			if (response.statusCode() != 200) {
				throw new LocalizedIOException("streamerreplay.error.ffmpeg_download", "HTTP " + response.statusCode());
			}
			byte[] buffer = new byte[256 * 1024];
			long total = 0;
			int read;
			while ((read = in.read(buffer)) > 0) {
				if (cancelRequested) {
					throw new IOException("cancelled");
				}
				total += read;
				if (total > DOWNLOAD_BYTES) {
					throw new LocalizedIOException("streamerreplay.error.ffmpeg_checksum");
				}
				out.write(buffer, 0, read);
				sha256.update(buffer, 0, read);
				downloaded = total;
			}
		}
		if (!HexFormat.of().formatHex(sha256.digest()).equals(SHA256)) {
			throw new LocalizedIOException("streamerreplay.error.ffmpeg_checksum");
		}
	}

	/** Copies {@code …/bin/ffmpeg.exe} out of the verified zip. The target name is fixed, so entry names cannot escape. */
	private static void unpack(Path zip) throws IOException {
		Path target = target();
		Path temp = target.resolveSibling("ffmpeg.exe.part");
		try (ZipFile archive = new ZipFile(zip.toFile())) {
			ZipEntry exe = archive.stream()
				.filter(e -> !e.isDirectory() && e.getName().endsWith("/bin/ffmpeg.exe"))
				.findFirst()
				.orElseThrow(() -> new LocalizedIOException("streamerreplay.error.ffmpeg_checksum"));
			try (InputStream in = archive.getInputStream(exe)) {
				Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
		}
	}
}
