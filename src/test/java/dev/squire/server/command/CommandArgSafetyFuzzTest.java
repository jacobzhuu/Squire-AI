package dev.squire.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.squire.common.protocol.ToolCall;
import dev.squire.server.tool.ArgDefinition;
import dev.squire.server.tool.ToolDefinition;
import dev.squire.server.tool.ToolGateway;
import net.minecraft.util.Identifier;

/**
 * Fuzz-lite for command argument safety (spec §47/§83): selector strings, command
 * separators and SNBT must be structurally impossible to smuggle into a compiled
 * command, and hostile inputs may not crash the validator.
 */
class CommandArgSafetyFuzzTest {

	private void expectRejected(String reason, Runnable hostile) {
		try {
			hostile.run();
			throw new AssertionError("hostile input accepted (" + reason + ")");
		} catch (IllegalArgumentException | NullPointerException expected) {
			// typed constructors fail closed
		}
	}

	@Test
	void enchantSpecFailsClosedOnHostileLevelsAndIds() {
		Identifier sharpness = Identifier.of("minecraft", "sharpness");
		expectRejected("level 0",
			() -> new StructuredCommandCompiler.EnchantSpec(sharpness, 0));
		expectRejected("level over max",
			() -> new StructuredCommandCompiler.EnchantSpec(sharpness, 256));
		expectRejected("null id",
			() -> new StructuredCommandCompiler.EnchantSpec(null, 1));
	}

	@Test
	void enchantmentsCannotSmuggleSnbtThroughTheIdentifier() {
		// Identifier 本身就不接受 { } " 这些字符，SNBT 注入在类型层面就不可能发生。
		for (String hostile : new String[] {
				"sharpness\",lvl:99s},{id:\"minecraft:mending",
				"minecraft:sharpness}]},Damage:0,{",
				"minecraft:sharp ness" }) {
			expectRejected("snbt via enchantment id " + hostile,
				() -> new StructuredCommandCompiler.EnchantSpec(
					Identifier.of("minecraft", hostile), 1));
		}
	}

	@Test
	void giveIntentCapsTheNumberOfEnchantments() {
		Identifier sword = Identifier.of("minecraft", "netherite_sword");
		Identifier sharpness = Identifier.of("minecraft", "sharpness");
		java.util.List<StructuredCommandCompiler.EnchantSpec> tooMany =
			new java.util.ArrayList<>();
		for (int i = 0; i <= StructuredCommandCompiler.MAX_ENCHANTMENTS; i++) {
			tooMany.add(new StructuredCommandCompiler.EnchantSpec(sharpness, 1));
		}
		expectRejected("enchantment list over cap",
			() -> new StructuredCommandCompiler.GiveIntent(sword, 1, tooMany));
		// 空列表和 null 都等价于"不附魔"，且不会炸。
		assertTrue(new StructuredCommandCompiler.GiveIntent(sword, 1, null)
			.enchantments().isEmpty());
	}

	@Test
	void giveIntentRejectsOutOfRangeCounts() {
		Identifier stick = Identifier.of("minecraft", "stick");
		expectRejected("count 0", () -> new StructuredCommandCompiler.GiveIntent(stick, 0));
		expectRejected("count over max",
			() -> new StructuredCommandCompiler.GiveIntent(stick, 4097));
		expectRejected("negative count",
			() -> new StructuredCommandCompiler.GiveIntent(stick, -1));
		assertEquals(4096,
			new StructuredCommandCompiler.GiveIntent(stick, 4096).count());
	}

	@Test
	void giveIntentRejectsMalformedItemIds() {
		expectRejected("null id", () -> new StructuredCommandCompiler.GiveIntent(null, 1));
		expectRejected("blank namespace",
			() -> new StructuredCommandCompiler.GiveIntent(Identifier.of("", "x"), 1));
	}

	@Test
	void effectIntentRejectsInjectionShapes() {
		// separators, selectors, SNBT — none can survive the regex gate
		for (String hostile : new String[] {
				"speed;say hacked", "speed && stop", "@a[limit=1]",
				"{Fire:-1000l}", "../../etc/passwd", ""}) {
			expectRejected("effectId '" + hostile + "'",
				() -> new StructuredCommandCompiler.EffectIntent(hostile, 200, 0));
		}
		expectRejected("duration overflow",
			() -> new StructuredCommandCompiler.EffectIntent(
				"minecraft:speed", 72001, 0));
		expectRejected("amplifier overflow",
			() -> new StructuredCommandCompiler.EffectIntent(
				"minecraft:speed", 200, 5));
	}

	@Test
	void safeSummonWhitelistBlocksHostileAndInjectionShapes() {
		// peaceful mobs pass the gate
		assertTrue(StructuredCommandCompiler.isSafeSummon("minecraft:cow"));
		assertTrue(StructuredCommandCompiler.isSafeSummon("minecraft:iron_golem"));
		// hostile mobs and every injection shape are structurally impossible
		for (String hostile : new String[] {
				"minecraft:creeper", "minecraft:wither", "minecraft:ender_dragon",
				"cow", "", null,
				"minecraft:cow @e[type=!player]", "minecraft:cow;say hacked",
				"../../etc/passwd", "MINECRAFT:COW"}) {
			assertFalse(StructuredCommandCompiler.isSafeSummon(hostile),
				"whitelist must reject '" + hostile + "'");
		}
	}

	@Test
	void gatewaySchemaValidationSurvivesHostilePayloads() {
		ToolDefinition def = ToolDefinition.builder("test.fuzz")
			.description("fuzz target")
			.arg(ArgDefinition.intRange("n", 0, 10, "bounded int"))
			.arg(ArgDefinition.required("s", ArgDefinition.ArgType.STRING, "text"))
			.build();
		// type confusion, unknown keys, extreme numbers — rejected, never thrown
		var cases = java.util.List.of(
			Map.<String, Object>of("n", Double.NaN),
			Map.<String, Object>of("n", Long.MAX_VALUE),
			Map.<String, Object>of("s", Map.of("nested", "object")),
			Map.<String, Object>of("s", java.util.List.of(1, 2)),
			Map.<String, Object>of("s", "ok", "injected", true),
			Map.<String, Object>of());
		for (Map<String, Object> args : cases) {
			String verdict = ToolGateway.validateArguments(def, args);
			assertTrue(verdict != null, "expected rejection for " + args);
		}
		// well-formed still passes
		assertTrue(ToolGateway.validateArguments(def,
			Map.of("n", 5, "s", "ok")) == null);
	}

	@Test
	void toolCallToleratesNullArguments() {
		// parser-level robustness: null map must not explode downstream validators
		ToolCall call = new ToolCall(java.util.UUID.randomUUID(), "any.tool", null);
		assertTrue(call.arguments().isEmpty());
	}
}
