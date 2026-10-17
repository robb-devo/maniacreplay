package dev.robb.streamerreplay.lang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the German translation complete and consistent with English and with the keys used in code. */
class TranslationsTest {
	private static final Path LANG = Path.of("src/client/resources/assets/streamerreplay/lang");
	private static final Pattern ARG = Pattern.compile("%(?:\\d+\\$)?s");
	private static final Pattern KEY_IN_CODE = Pattern.compile("\"((?:streamerreplay|key\\.streamerreplay)\\.[a-z0-9_.]+)\"");

	private static JsonObject load(String name) throws IOException {
		return JsonParser.parseString(Files.readString(LANG.resolve(name), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	@Test
	void germanHasEveryEnglishKey() throws IOException {
		JsonObject en = load("en_us.json");
		JsonObject de = load("de_de.json");
		TreeSet<String> missing = new TreeSet<>(en.keySet());
		missing.removeAll(de.keySet());
		assertTrue(missing.isEmpty(), "Missing in de_de.json: " + missing);
		TreeSet<String> extra = new TreeSet<>(de.keySet());
		extra.removeAll(en.keySet());
		assertTrue(extra.isEmpty(), "Only in de_de.json (stale?): " + extra);
	}

	@Test
	void placeholdersMatchAndNothingIsEmpty() throws IOException {
		JsonObject en = load("en_us.json");
		JsonObject de = load("de_de.json");
		List<String> problems = new ArrayList<>();
		for (String key : en.keySet()) {
			String e = en.get(key).getAsString();
			String d = de.has(key) ? de.get(key).getAsString() : "";
			if (d.isBlank()) {
				problems.add(key + ": empty");
			} else if (count(ARG, e) != count(ARG, d)) {
				problems.add(key + ": placeholder count differs (" + e + " | " + d + ")");
			} else if (count(Pattern.compile("%%"), e) != count(Pattern.compile("%%"), d)) {
				problems.add(key + ": %% count differs");
			}
		}
		assertTrue(problems.isEmpty(), String.join("\n", problems));
	}

	@Test
	void everyKeyUsedInCodeExists() throws IOException {
		JsonObject en = load("en_us.json");
		TreeSet<String> missing = new TreeSet<>();
		try (Stream<Path> files = Files.walk(Path.of("src/client/java"))) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				Matcher m = KEY_IN_CODE.matcher(Files.readString(file, StandardCharsets.UTF_8));
				while (m.find()) {
					String key = m.group(1);
					if (!key.endsWith(".") && !en.has(key) && !isPrefix(en, key)) {
						missing.add(key + " (" + file.getFileName() + ")");
					}
				}
			}
		}
		assertTrue(missing.isEmpty(), "Keys used in code but missing in en_us.json: " + missing);
	}

	/** Some keys are built from a prefix plus a suffix (e.g. quality names); accept a prefix of existing keys. */
	private static boolean isPrefix(JsonObject en, String key) {
		for (String k : en.keySet()) {
			if (k.startsWith(key + ".")) {
				return true;
			}
		}
		return false;
	}

	private static int count(Pattern pattern, String text) {
		Matcher m = pattern.matcher(text);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}
}
