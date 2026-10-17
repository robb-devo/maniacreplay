package dev.robb.streamerreplay.render;

import dev.robb.streamerreplay.util.FfmpegLocator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;

/** Checks asynchronously whether ffmpeg can be started and which H.264 encoder it offers. */
public final class FfmpegProbe {
	/** Result of a probe; {@code version} is null when ffmpeg could not be run. */
	public record Result(String executable, String version, boolean hasX264, String error) {
		public boolean ok() {
			return version != null;
		}
	}

	private FfmpegProbe() {
	}

	/** Probes the configured value; plain "ffmpeg" is first looked up in the usual install folders. */
	public static CompletableFuture<Result> probe(String configured) {
		return CompletableFuture.supplyAsync(() -> run(resolve(configured)));
	}

	/** The program that will really be started for a configured value (see {@link FfmpegLocator}). */
	public static String resolve(String configured) {
		return FfmpegLocator.resolve(configured, FabricLoader.getInstance().getGameDir());
	}

	static Result run(String executable) {
		try {
			String versionOutput = exec(executable, "-hide_banner", "-version");
			String firstLine = versionOutput.lines().findFirst().orElse("ffmpeg");
			String version = firstLine.startsWith("ffmpeg version ") ? firstLine.substring(15).split(" ")[0] : firstLine;
			String encoders = exec(executable, "-hide_banner", "-encoders");
			return new Result(executable, version, encoders.contains("libx264"), null);
		} catch (IOException e) {
			return new Result(executable, null, false, e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result(executable, null, false, "interrupted");
		}
	}

	private static String exec(String... command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		process.getOutputStream().close();
		byte[] out;
		try (InputStream in = process.getInputStream()) {
			out = in.readNBytes(1 << 20);
		}
		if (!process.waitFor(10, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("ffmpeg did not respond");
		}
		if (process.exitValue() != 0) {
			throw new IOException("ffmpeg exited with code " + process.exitValue());
		}
		return new String(out, StandardCharsets.UTF_8);
	}
}
