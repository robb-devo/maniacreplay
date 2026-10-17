package dev.robb.streamerreplay.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the ffmpeg program when the user left the setting at plain {@code ffmpeg}.
 *
 * <p>Why this exists: the Minecraft launcher often does not see the PATH a user's terminal sees. On macOS a
 * launcher started from the Dock never gets Homebrew's {@code /opt/homebrew/bin}; on Windows a launcher that was
 * already open when ffmpeg was installed with WinGet keeps the old PATH. So besides PATH we look in the places the
 * common installers put ffmpeg. Nothing is downloaded and nothing is run here; this only checks which files exist.
 *
 * <p>Pure logic over {@link Os}, an environment map and a tiny {@link Fs} view so it can be unit-tested for every
 * operating system on any machine.
 */
public final class FfmpegLocator {
	/** The plain name that means "find it for me". */
	public static final String DEFAULT = "ffmpeg";

	public enum Os {
		WINDOWS, MAC, LINUX;

		public static Os current() {
			String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			if (name.startsWith("windows")) {
				return WINDOWS;
			}
			return name.startsWith("mac") || name.contains("darwin") ? MAC : LINUX;
		}

		char separator() {
			return this == WINDOWS ? '\\' : '/';
		}

		char pathListSeparator() {
			return this == WINDOWS ? ';' : ':';
		}
	}

	/** Minimal file-system view: does a program file exist, and which sub-folders does a folder have. */
	public interface Fs {
		boolean isFile(String path);

		List<String> subdirectories(String dir);
	}

	/** The real file system. */
	public static final Fs REAL = new Fs() {
		@Override
		public boolean isFile(String path) {
			try {
				return Files.isRegularFile(Path.of(path)); // follows symlinks (Homebrew, WinGet links)
			} catch (RuntimeException e) {
				return false;
			}
		}

		@Override
		public List<String> subdirectories(String dir) {
			List<String> out = new ArrayList<>();
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(dir), Files::isDirectory)) {
				stream.forEach(p -> out.add(p.toString()));
			} catch (IOException | RuntimeException e) {
				// missing or unreadable: nothing there
			}
			out.sort(null);
			return out;
		}
	};

	private FfmpegLocator() {
	}

	/** Resolves the configured value on this machine. */
	public static String resolve(String configured, Path gameDir) {
		return resolve(configured, Os.current(), System.getenv(), gameDir.toAbsolutePath().toString(), REAL);
	}

	/**
	 * Returns the program to start. A custom value (anything but plain {@code ffmpeg}) is respected as typed, only
	 * {@code .exe} is added on Windows when that file exists. For the default the first existing candidate wins; if
	 * none exists the plain name is returned so the error message stays "ffmpeg not found".
	 */
	public static String resolve(String configured, Os os, Map<String, String> env, String gameDir, Fs fs) {
		String value = configured == null ? "" : configured.strip();
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1).strip(); // pasted from Explorer's "Copy as path"
		}
		if (value.isEmpty()) {
			value = DEFAULT;
		}
		boolean isDefault = value.equals(DEFAULT) || os == Os.WINDOWS && value.equalsIgnoreCase("ffmpeg.exe");
		if (!isDefault) {
			if (os == Os.WINDOWS && !value.toLowerCase(Locale.ROOT).endsWith(".exe") && !fs.isFile(value) && fs.isFile(value + ".exe")) {
				return value + ".exe";
			}
			return value;
		}
		return candidates(os, env, gameDir, fs).stream().filter(fs::isFile).findFirst().orElse(DEFAULT);
	}

	/** Every place we look, in order (whether or not it exists). */
	public static List<String> candidates(Os os, Map<String, String> env, String gameDir, Fs fs) {
		String exe = os == Os.WINDOWS ? "ffmpeg.exe" : "ffmpeg";
		LinkedHashSet<String> out = new LinkedHashSet<>();
		// 1. PATH, like a terminal would
		env(os, env, "PATH").ifPresent(path -> {
			for (String dir : path.split(String.valueOf(os.pathListSeparator()))) {
				dir = dir.strip();
				if (dir.length() >= 2 && dir.startsWith("\"") && dir.endsWith("\"")) {
					dir = dir.substring(1, dir.length() - 1);
				}
				if (!dir.isEmpty()) {
					out.add(join(os, dir, exe));
				}
			}
		});
		// 2. a copy the user unpacked next to the game (works on every OS, needs no admin rights)
		if (gameDir != null && !gameDir.isBlank()) {
			out.add(join(os, gameDir, "ffmpeg", "bin", exe));
			out.add(join(os, gameDir, "ffmpeg", exe));
			out.add(join(os, gameDir, exe));
		}
		// 3. where the usual installers put it
		switch (os) {
			case WINDOWS -> {
				env(os, env, "LOCALAPPDATA").ifPresent(local -> {
					out.add(join(os, local, "Microsoft", "WinGet", "Links", exe));
					String packages = join(os, local, "Microsoft", "WinGet", "Packages");
					for (String pkg : fs.subdirectories(packages)) {
						if (name(os, pkg).toLowerCase(Locale.ROOT).contains("ffmpeg")) {
							for (String build : fs.subdirectories(pkg)) { // Gyan.FFmpeg_…\ffmpeg-7.1-full_build\bin
								out.add(join(os, build, "bin", exe));
							}
							out.add(join(os, pkg, "bin", exe));
						}
					}
				});
				env(os, env, "SCOOP").ifPresent(scoop -> out.add(join(os, scoop, "shims", exe)));
				env(os, env, "USERPROFILE").ifPresent(home -> out.add(join(os, home, "scoop", "shims", exe)));
				out.add(join(os, env(os, env, "ChocolateyInstall").orElse(join(os, env(os, env, "ProgramData").orElse("C:\\ProgramData"), "chocolatey")), "bin", exe));
				out.add(join(os, env(os, env, "ProgramFiles").orElse("C:\\Program Files"), "ffmpeg", "bin", exe));
				out.add("C:\\ffmpeg\\bin\\" + exe);
			}
			case MAC -> {
				out.add("/opt/homebrew/bin/ffmpeg"); // Homebrew on Apple silicon
				out.add("/usr/local/bin/ffmpeg");    // Homebrew on Intel, manual installs
				out.add("/opt/local/bin/ffmpeg");    // MacPorts
				env(os, env, "HOME").ifPresent(home -> out.add(join(os, home, "bin", "ffmpeg")));
				out.add("/Applications/ffmpeg");
			}
			case LINUX -> {
				out.add("/usr/bin/ffmpeg");
				out.add("/usr/local/bin/ffmpeg");
				out.add("/snap/bin/ffmpeg");
				env(os, env, "HOME").ifPresent(home -> out.add(join(os, home, ".local", "bin", "ffmpeg")));
				out.add("/home/linuxbrew/.linuxbrew/bin/ffmpeg");
			}
		}
		return List.copyOf(out);
	}

	/** Environment lookup; on Windows names are case-insensitive ("Path" is common). */
	private static Optional<String> env(Os os, Map<String, String> env, String key) {
		String value = env.get(key);
		if (value == null && os == Os.WINDOWS) {
			for (Map.Entry<String, String> e : env.entrySet()) {
				if (e.getKey().equalsIgnoreCase(key)) {
					value = e.getValue();
					break;
				}
			}
		}
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
	}

	private static String join(Os os, String first, String... more) {
		StringBuilder b = new StringBuilder(first);
		for (String part : more) {
			char last = b.isEmpty() ? 0 : b.charAt(b.length() - 1);
			if (last != os.separator() && last != '/' && !b.isEmpty()) {
				b.append(os.separator());
			}
			b.append(part);
		}
		return b.toString();
	}

	private static String name(Os os, String path) {
		int i = Math.max(path.lastIndexOf(os.separator()), path.lastIndexOf('/'));
		return path.substring(i + 1);
	}
}
