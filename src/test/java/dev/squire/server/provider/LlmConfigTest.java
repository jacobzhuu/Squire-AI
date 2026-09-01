package dev.squire.server.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Config loading is fail-closed: anything off means NO provider (§23). */
class LlmConfigTest {

	@TempDir
	Path dir;

	private static final String VALID = """
		{
		  "type": "openai-compatible",
		  "baseUrl": "https://api.example.com/v1/",
		  "apiKey": "${TEST_KEY_VAR}",
		  "model": "gpt-4o-mini",
		  "temperature": 0.3,
		  "maxTokens": 700,
		  "timeoutMs": 9000,
		  "maxContextTokens": 32000
		}
		""";

	private Path write(String content) throws Exception {
		Path file = dir.resolve("llm.json");
		Files.writeString(file, content);
		return file;
	}

	@Test
	void absentFileMeansNoProvider() {
		assertEquals(Optional.empty(), LlmConfig.load(dir.resolve("missing.json")));
	}

	@Test
	void garbageFileFailsClosed() throws Exception {
		assertEquals(Optional.empty(), LlmConfig.load(write("{not json")));
		assertEquals(Optional.empty(),
			LlmConfig.load(write("[\"an array\"]")));
	}

	@Test
	void wrongTypeFieldRefused() throws Exception {
		assertEquals(Optional.empty(), LlmConfig.load(
			write(VALID.replace("openai-compatible", "telepathy"))));
		assertEquals(Optional.empty(), LlmConfig.load(
			write(VALID.replace("\"type\": \"openai-compatible\",", ""))));
	}

	@Test
	void missingOrBlankRequiredFieldsRefused() throws Exception {
		assertEquals(Optional.empty(),
			LlmConfig.load(write(VALID.replace("\"gpt-4o-mini\"", ""))));
		assertEquals(Optional.empty(),
			LlmConfig.load(write(VALID.replace("https://api.example.com/v1/", "  "))));
	}

	@Test
	void nonHttpBaseUrlRefused() throws Exception {
		assertEquals(Optional.empty(), LlmConfig.load(
			write(VALID.replace("https://", "ftp://"))));
	}

	@Test
	void unsetEnvReferenceFailsClosed() throws Exception {
		// deliberately obscure name — never expected to exist in any environment
		assertEquals(Optional.empty(), LlmConfig.load(
			write(VALID.replace("${TEST_KEY_VAR}", "${SQUIRE_UNSET_VAR_9137}"))));
	}

	@Test
	void literalKeyPassesThroughAndUrlsDerive() throws Exception {
		Optional<LlmConfig> config = LlmConfig.load(
			write(VALID.replace("${TEST_KEY_VAR}", "sk-literal-1234567890")));
		assertTrue(config.isPresent());
		assertEquals("sk-literal-1234567890", config.get().apiKey());
		assertEquals("https://api.example.com/v1", config.get().baseUrl());
		assertEquals("https://api.example.com/v1/chat/completions",
			config.get().chatCompletionsUrl());
		assertEquals("https://api.example.com/v1/models", config.get().modelsUrl());
		assertEquals(0.3d, config.get().temperature());
		assertEquals(700, config.get().maxTokens());
		assertEquals(9000L, config.get().timeoutMs());
		assertEquals(32000L, config.get().maxContextTokens());
		assertEquals(ToolRelevance.DEFAULT_CANDIDATE_LIMIT,
			config.get().toolCandidateLimit());
	}

	@Test
	void defaultsApplyWhenOptionalFieldsAbsent() throws Exception {
		Optional<LlmConfig> config = LlmConfig.load(write("""
			{"type":"openai-compatible","baseUrl":"http://localhost:11434/v1",
			 "apiKey":"k","model":"llama3"}
			"""));
		assertTrue(config.isPresent());
		assertEquals(0.7d, config.get().temperature());
		// 512 太小：复杂请求的 JSON 会被截断成空/非法输出，而空输出会让整个回合
		// 悄无声息地"成功"结束。默认值必须留出容纳一次完整工具调用的余量。
		assertEquals(2048, config.get().maxTokens());
		assertEquals(20_000L, config.get().timeoutMs());
	}

	@Test
	void brokenConfigExplainsItselfInsteadOfLookingLikeNoConfig() throws Exception {
		// 以前"没放文件"和"文件写错了"都退化成同一个空 Optional，服主无从排查。
		// 密钥要给成字面量，否则会先撞上"apiKey 环境变量不存在"那条，测不到 URL 这条。
		assertEquals(Optional.empty(), LlmConfig.load(write(VALID
			.replace("${TEST_KEY_VAR}", "sk-literal-1").replace("https://", "ftp://"))));
		assertTrue(LlmConfig.lastLoadError().isPresent());
		assertTrue(LlmConfig.lastLoadError().orElseThrow().contains("http"));

		assertEquals(Optional.empty(), LlmConfig.load(write("{not json")));
		assertTrue(LlmConfig.lastLoadError().isPresent());
	}

	@Test
	void aGoodLoadClearsTheStaleErrorAndAMissingFileIsNotAnError() throws Exception {
		assertEquals(Optional.empty(), LlmConfig.load(write("{not json")));
		assertTrue(LlmConfig.lastLoadError().isPresent());

		assertTrue(LlmConfig.load(
			write(VALID.replace("${TEST_KEY_VAR}", "sk-literal-1"))).isPresent());
		assertFalse(LlmConfig.lastLoadError().isPresent());

		// 没配置 LLM 是完全正常的运行状态，不该被报成配置错误。
		assertEquals(Optional.empty(), LlmConfig.load(dir.resolve("missing.json")));
		assertFalse(LlmConfig.lastLoadError().isPresent());
	}

	@Test
	void theKeyNeverLeaksIntoTheDiagnosticReason() throws Exception {
		String key = "sk-super-secret-value-999999";
		// baseUrl 坏掉，但 apiKey 是好的：错误原因里绝不能捎带出密钥。
		assertEquals(Optional.empty(), LlmConfig.load(write(
			VALID.replace("${TEST_KEY_VAR}", key).replace("https://", "ftp://"))));
		String reason = LlmConfig.lastLoadError().orElseThrow();
		assertFalse(reason.contains(key), () -> "reason leaked key: " + reason);
	}

	@Test
	void outOfRangeValuesRefused() throws Exception {
		assertEquals(Optional.empty(), LlmConfig.load(
			write(VALID.replace("0.3", "9.9"))));
		assertEquals(Optional.empty(), LlmConfig.load(
			write(VALID.replace("700", "-5"))));
	}

	@Test
	void toStringNeverContainsTheFullKey() throws Exception {
		String key = "sk-super-secret-value-999999";
		Optional<LlmConfig> config = LlmConfig.load(
			write(VALID.replace("${TEST_KEY_VAR}", key)));
		assertTrue(config.isPresent());
		String rendered = config.get().toString();
		assertFalse(rendered.contains(key), () -> "toString leaked key: " + rendered);
		assertTrue(rendered.contains("***"));
	}
}
