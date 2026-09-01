package dev.squire.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Enforces ADR-016: {@code dev.squire.common} is pure Java.
 *
 * <p>Spec section 5.1: common must not import net.minecraft.*. Additionally it must not depend
 * on upper layers (api/server/client/mcp/gametest), otherwise the dependency direction would
 * invert. Gson and SLF4J are permitted (both shipped by Minecraft).</p>
 */
class CommonModuleBoundaryTest {
	private static final Pattern FORBIDDEN_IMPORT = Pattern.compile(
		"^\\s*import\\s+(?:static\\s+)?"
			+ "(?:"
			+ "net\\.minecraft\\..*"
			+ "|net\\.fabricmc\\..*"
			+ "|dev\\.squire\\.api\\..*"
			+ "|dev\\.squire\\.server\\..*"
			+ "|dev\\.squire\\.client\\..*"
			+ "|dev\\.squire\\.mcp\\..*"
			+ "|dev\\.squire\\.gametest\\..*"
			+ ")\\s*;",
		Pattern.MULTILINE);

	private static final Map<String, String> ALLOWED_THIRD_PARTY_PREFIXES = Map.of(
		"java.", "JDK",
		"javax.", "JDK",
		"org.slf4j.", "SLF4J (shipped by Minecraft)",
		"com.google.gson.", "Gson (shipped by Minecraft)",
		"org.jetbrains.annotations.", "annotations",
		"org.junit.jupiter.", "JUnit (test scope)");

	@Test
	void commonPackageMustNotImportForbiddenNamespaces() throws IOException {
		List<String> violations = new ArrayList<>();
		List<Path> files = listCommonSources();
		assertTrue(files.size() > 0, "no common sources found - test misconfigured");

		for (Path file : files) {
			String source = Files.readString(file);
			var matcher = FORBIDDEN_IMPORT.matcher(source);
			while (matcher.find()) {
				violations.add(file.getFileName() + ": " + matcher.group().trim());
			}
			for (String line : source.split("\\R")) {
				String trimmed = line.trim();
				if (!trimmed.startsWith("import ")) {
					continue;
				}
				String imported = trimmed.substring("import ".length())
					.replace("static ", "")
					.trim()
					.replaceAll(";+$", "");
				if (FORBIDDEN_IMPORT.matcher(trimmed + ";").find()) {
					continue; // already reported above
				}
				boolean allowed = ALLOWED_THIRD_PARTY_PREFIXES.keySet().stream().anyMatch(imported::startsWith)
					|| imported.startsWith("dev.squire.common.");
				if (!allowed && imported.contains(".")) {
					violations.add(file.getFileName() + ": unapproved import '" + imported
						+ "' (add to ALLOWED_THIRD_PARTY_PREFIXES if genuinely pure-Java)");
				}
			}
		}
		if (!violations.isEmpty()) {
			fail("ADR-016 boundary violations in dev.squire.common:\n  "
				+ String.join("\n  ", violations));
		}
	}

	private static List<Path> listCommonSources() throws IOException {
		Path root = locateProjectRoot(Path.of(System.getProperty("user.dir")).toAbsolutePath());
		Path commonDir = root.resolve("src/main/java/dev/squire/common");
		assertTrue(Files.isDirectory(commonDir), "missing " + commonDir);
		try (Stream<Path> stream = Files.walk(commonDir)) {
			return stream.filter(p -> p.toString().endsWith(".java")).toList();
		}
	}

	private static Path locateProjectRoot(Path start) {
		Path current = start;
		for (int i = 0; i < 6; i++) {
			if (Files.isDirectory(current.resolve("src/main/java/dev/squire"))) {
				return current;
			}
			Path parent = current.getParent();
			if (parent == null) {
				break;
			}
			current = parent;
		}
		fail("could not locate project root from " + start);
		return null; // unreachable
	}
}
