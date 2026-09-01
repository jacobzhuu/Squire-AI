package dev.squire.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Enforces ADR-016 for {@code dev.squire.api}: the stable contract layer must be
 * Minecraft-free so alternative bodies/providers can implement it without the game.
 */
class ApiModuleBoundaryTest {
	private static final Pattern FORBIDDEN_IMPORT = Pattern.compile(
		"^\\s*import\\s+(?:static\\s+)?"
			+ "(?:"
			+ "net\\.minecraft\\..*"
			+ "|net\\.fabricmc\\..*"
			+ "|dev\\.squire\\.server\\..*"
			+ "|dev\\.squire\\.client\\..*"
			+ "|dev\\.squire\\.mcp\\..*"
			+ "|dev\\.squire\\.gametest\\..*"
			+ ")\\s*;",
		Pattern.MULTILINE);

	@Test
	void apiPackageMustNotImportMinecraftOrUpperLayers() throws IOException {
		List<String> violations = new ArrayList<>();
		List<Path> files = listApiSources();
		assertFalse(files.isEmpty(), "no api sources found - test misconfigured");

		for (Path file : files) {
			String source = Files.readString(file);
			var matcher = FORBIDDEN_IMPORT.matcher(source);
			while (matcher.find()) {
				violations.add(file.getFileName() + ": " + matcher.group().trim());
			}
		}
		if (!violations.isEmpty()) {
			fail("ADR-016 boundary violations in dev.squire.api:\n  "
				+ String.join("\n  ", violations));
		}
	}

	/** api.body types must stay constructible without Minecraft on the classpath. */
	@Test
	void bodyValueTypesArePureRecords() throws IOException {
		Path root = locateProjectRoot();
		for (String name : List.of("TargetPosition", "MoveOptions", "AgentPhysicalState",
				"BodyCapabilities", "InteractionResult")) {
			Path file = root.resolve("src/main/java/dev/squire/api/body/" + name + ".java");
			assertTrue(Files.exists(file), name + " must exist under api.body");
			String source = Files.readString(file);
			assertFalse(source.contains("net.minecraft"), name + " leaked a MC reference");
		}
	}

	private static List<Path> listApiSources() throws IOException {
		Path root = locateProjectRoot();
		Path apiDir = root.resolve("src/main/java/dev/squire/api");
		try (Stream<Path> stream = Files.walk(apiDir)) {
			return stream.filter(p -> p.toString().endsWith(".java")).toList();
		}
	}

	private static Path locateProjectRoot() {
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
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
		return fail("could not locate project root from " + current);
	}
}
