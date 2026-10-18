package dev.robb.streamerreplay.util;

import dev.robb.streamerreplay.util.FfmpegLocator.Os;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegLocatorTest {
	/** A fake disk: a set of files; folders are implied by the file paths. */
	private static final class FakeFs implements FfmpegLocator.Fs {
		final Set<String> files = new HashSet<>();
		final char sep;

		FakeFs(char sep, String... files) {
			this.sep = sep;
			this.files.addAll(List.of(files));
		}

		@Override
		public boolean isFile(String path) {
			return files.contains(path);
		}

		@Override
		public List<String> subdirectories(String dir) {
			Set<String> out = new HashSet<>();
			String prefix = dir + sep;
			for (String f : files) {
				if (f.startsWith(prefix)) {
					String rest = f.substring(prefix.length());
					int i = rest.indexOf(sep);
					if (i > 0) {
						out.add(prefix + rest.substring(0, i));
					}
				}
			}
			List<String> list = new ArrayList<>(out);
			list.sort(null);
			return list;
		}
	}

	private static final Map<String, String> WIN_ENV = Map.of(
		"Path", "C:\\Windows\\system32;C:\\Windows",
		"LOCALAPPDATA", "C:\\Users\\Sam\\AppData\\Local",
		"USERPROFILE", "C:\\Users\\Sam",
		"ProgramData", "C:\\ProgramData",
		"ProgramFiles", "C:\\Program Files");
	private static final String WIN_GAME = "C:\\Users\\Sam\\AppData\\Roaming\\.minecraft";

	private static String win(String... files) {
		return FfmpegLocator.resolve("ffmpeg", Os.WINDOWS, WIN_ENV, WIN_GAME, new FakeFs('\\', files));
	}

	@Test
	void windowsFindsFfmpegOnPathCaseInsensitiveVariable() {
		Map<String, String> env = Map.of("Path", "C:\\Tools;\"C:\\My Apps\\ffmpeg\\bin\"");
		assertEquals("C:\\My Apps\\ffmpeg\\bin\\ffmpeg.exe",
			FfmpegLocator.resolve("ffmpeg", Os.WINDOWS, env, WIN_GAME, new FakeFs('\\', "C:\\My Apps\\ffmpeg\\bin\\ffmpeg.exe")));
	}

	@Test
	void windowsFindsWingetLinkAndPackage() {
		assertEquals("C:\\Users\\Sam\\AppData\\Local\\Microsoft\\WinGet\\Links\\ffmpeg.exe",
			win("C:\\Users\\Sam\\AppData\\Local\\Microsoft\\WinGet\\Links\\ffmpeg.exe"));
		String pkg = "C:\\Users\\Sam\\AppData\\Local\\Microsoft\\WinGet\\Packages\\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\\ffmpeg-7.1-full_build\\bin\\ffmpeg.exe";
		assertEquals(pkg, win(pkg, "C:\\Users\\Sam\\AppData\\Local\\Microsoft\\WinGet\\Packages\\Other.Tool\\bin\\tool.exe"));
	}

	@Test
	void windowsFindsScoopChocolateyAndClassicFolders() {
		assertEquals("C:\\Users\\Sam\\scoop\\shims\\ffmpeg.exe", win("C:\\Users\\Sam\\scoop\\shims\\ffmpeg.exe"));
		assertEquals("C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe", win("C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe"));
		assertEquals("C:\\ffmpeg\\bin\\ffmpeg.exe", win("C:\\ffmpeg\\bin\\ffmpeg.exe"));
		assertEquals("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe", win("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe"));
	}

	@Test
	void windowsFindsCopyInGameFolderAndPrefersPath() {
		assertEquals(WIN_GAME + "\\ffmpeg\\bin\\ffmpeg.exe", win(WIN_GAME + "\\ffmpeg\\bin\\ffmpeg.exe", "C:\\ffmpeg\\bin\\ffmpeg.exe"));
		assertEquals("C:\\Windows\\ffmpeg.exe", win("C:\\Windows\\ffmpeg.exe", WIN_GAME + "\\ffmpeg.exe"));
	}

	@Test
	void windowsFindsZipUnpackedIntoGameFolder() {
		String exe = WIN_GAME + "\\ffmpeg\\ffmpeg-7.1-full_build\\bin\\ffmpeg.exe";
		assertEquals(exe, win(exe));
	}

	@Test
	void windowsCustomPathsAreRespected() {
		FakeFs fs = new FakeFs('\\', "D:\\tools\\ffmpeg.exe");
		assertEquals("D:\\tools\\ffmpeg.exe", FfmpegLocator.resolve("\"D:\\tools\\ffmpeg.exe\"", Os.WINDOWS, WIN_ENV, WIN_GAME, fs));
		assertEquals("D:\\tools\\ffmpeg.exe", FfmpegLocator.resolve("D:\\tools\\ffmpeg", Os.WINDOWS, WIN_ENV, WIN_GAME, fs));
		assertEquals("E:\\gone\\ffmpeg.exe", FfmpegLocator.resolve("E:\\gone\\ffmpeg.exe", Os.WINDOWS, WIN_ENV, WIN_GAME, fs));
		assertEquals("C:\\ffmpeg\\bin\\ffmpeg.exe", FfmpegLocator.resolve("FFMPEG.EXE", Os.WINDOWS, WIN_ENV, WIN_GAME, new FakeFs('\\', "C:\\ffmpeg\\bin\\ffmpeg.exe")));
	}

	@Test
	void nothingFoundKeepsPlainName() {
		assertEquals("ffmpeg", win());
		assertEquals("ffmpeg", FfmpegLocator.resolve("  ", Os.MAC, Map.of(), "/Users/sam/Library/Application Support/minecraft", new FakeFs('/')));
	}

	private static String mac(Map<String, String> env, String... files) {
		return FfmpegLocator.resolve("ffmpeg", Os.MAC, env, "/Users/sam/Library/Application Support/minecraft", new FakeFs('/', files));
	}

	@Test
	void macFindsHomebrewEvenWithoutShellPath() {
		Map<String, String> launcherEnv = Map.of("PATH", "/usr/bin:/bin:/usr/sbin:/sbin", "HOME", "/Users/sam");
		assertEquals("/opt/homebrew/bin/ffmpeg", mac(launcherEnv, "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"));
		assertEquals("/usr/local/bin/ffmpeg", mac(launcherEnv, "/usr/local/bin/ffmpeg"));
		assertEquals("/opt/local/bin/ffmpeg", mac(launcherEnv, "/opt/local/bin/ffmpeg"));
		assertEquals("/Users/sam/bin/ffmpeg", mac(launcherEnv, "/Users/sam/bin/ffmpeg"));
		assertEquals("/Users/sam/Library/Application Support/minecraft/ffmpeg", mac(launcherEnv, "/Users/sam/Library/Application Support/minecraft/ffmpeg"));
	}

	@Test
	void macPathWinsAndUsesColonSeparator() {
		assertEquals("/Users/sam/tools/ffmpeg", mac(Map.of("PATH", "/usr/bin:/Users/sam/tools"), "/Users/sam/tools/ffmpeg", "/opt/homebrew/bin/ffmpeg"));
	}

	@Test
	void linuxFindsUsualPlaces() {
		FakeFs fs = new FakeFs('/', "/snap/bin/ffmpeg");
		assertEquals("/snap/bin/ffmpeg", FfmpegLocator.resolve("ffmpeg", Os.LINUX, Map.of("PATH", "/usr/bin"), "/home/sam/.minecraft", fs));
		assertEquals("/home/sam/.local/bin/ffmpeg", FfmpegLocator.resolve("ffmpeg", Os.LINUX, Map.of("HOME", "/home/sam"), "/home/sam/.minecraft",
			new FakeFs('/', "/home/sam/.local/bin/ffmpeg")));
	}

	@Test
	void candidateListHasNoDuplicatesAndStartsWithPath() {
		List<String> c = FfmpegLocator.candidates(Os.MAC, Map.of("PATH", "/usr/local/bin:/usr/local/bin"), "/game", new FakeFs('/'));
		assertEquals("/usr/local/bin/ffmpeg", c.getFirst());
		assertEquals(c.size(), new HashSet<>(c).size());
		assertTrue(c.contains("/opt/homebrew/bin/ffmpeg"));
	}
}
