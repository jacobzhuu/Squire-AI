package dev.squire.server.cbp;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.util.math.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 H1/H2：物化真实命令方块是这个 mod 唯一会在世界上留下命令方块的动作，
 * 所以进入它的门槛必须是"玩家明确说了"，而且每条命令都只能由白名单模板生成。
 */
class CbpIntentAndPlannerTest {

	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID AGENT = UUID.randomUUID();
	private static final BlockPos ANCHOR = new BlockPos(10, 64, 10);

	// ------------------------------------------------------------------ H1 意图门槛

	@Test
	void explicitCommandBlockRequestsPassTheGate() {
		assertTrue(CbpIntent.classify("做一个真实命令方块昼夜控制器").explicit());
		assertTrue(CbpIntent.classify("给我做一个命令方块教学装置").explicit());
		assertTrue(CbpIntent.classify("build a command block day/night controller")
			.explicit());
	}

	/**
	 * "每天晚上开灯"是自动化需求。把它物化成命令方块是这条门槛存在的全部理由——
	 * 玩家没有要求在世界里多出一堆命令方块。
	 */
	@Test
	void ordinaryAutomationRequestsAreSteeredToTheAutomationGraph() {
		var verdict = CbpIntent.classify("每天晚上在基地开灯");
		assertEquals(CbpIntent.Verdict.PREFER_AUTOMATION, verdict.verdict());
		assertFalse(verdict.explicit());
		assertEquals(CbpIntent.Verdict.PREFER_AUTOMATION,
			CbpIntent.classify("以后都自动收矿").verdict());
	}

	/** 点名了命令方块的自动化请求仍然算明确要求装置。 */
	@Test
	void namingCommandBlocksBeatsTheAutomationHint() {
		assertTrue(CbpIntent.classify("每天晚上用命令方块开灯").explicit(),
			"the player asked for the device by name");
	}

	@Test
	void vagueRequestsAskThePlayerInsteadOfWritingTheWorld() {
		var verdict = CbpIntent.classify("弄个真实一点的东西");
		assertEquals(CbpIntent.Verdict.UNCLEAR, verdict.verdict());
		assertNotNull(verdict.reason());
		assertEquals(CbpIntent.Verdict.UNCLEAR, CbpIntent.classify("").verdict());
		assertEquals(CbpIntent.Verdict.UNCLEAR, CbpIntent.classify(null).verdict());
	}

	// ------------------------------------------------------------------ H2 模板

	@Test
	void dayNightControllerIsAValidMultiBlockDevice() {
		CbpSpec spec = CbpPlanner.plan(CbpPlanner.Template.DAY_NIGHT_CONTROLLER,
			OWNER, AGENT, "day-night", ANCHOR);

		assertTrue(spec.validate().isEmpty(), () -> String.join("; ", spec.validate()));
		assertTrue(spec.entries().size() >= 4, "a real device is more than one block");
		assertEquals(2, spec.commandBlockCount(),
			"day branch and night branch each get a command block");
		assertTrue(spec.entries().stream()
				.anyMatch(e -> e.blockType().equals("minecraft:daylight_detector")),
			"the day/night sensor is what makes it a controller");
		assertTrue(spec.entries().stream().anyMatch(e -> e.signText() != null),
			"a teaching device carries a label");
		assertTrue(spec.entries().stream()
				.anyMatch(e -> e.isCommandBlock() && e.conditional()),
			"the chain block is conditional so the device teaches chaining");
	}

	@Test
	void pulseTeachingDeviceIsWireableAndEditable() {
		CbpSpec spec = CbpPlanner.plan(CbpPlanner.Template.PULSE_TEACHING_DEVICE,
			OWNER, AGENT, "pulse", ANCHOR);

		assertTrue(spec.validate().isEmpty(), () -> String.join("; ", spec.validate()));
		assertTrue(spec.entries().stream()
				.anyMatch(e -> e.blockType().equals("minecraft:lever")),
			"a manual switch makes it something the player can actually drive");
		assertTrue(spec.entries().stream()
				.anyMatch(e -> e.blockType().equals("minecraft:repeating_command_block")),
			"the point of the device is the repeating block");
		for (CbpSpec.Entry entry : spec.entries()) {
			if (entry.isCommandBlock()) {
				assertFalse(entry.auto(),
					"blocks are inert until the player powers them");
			}
		}
	}

	/** 每一条命令都必须过 CbpCommandPolicy——模板不是绕过白名单的通道。 */
	@Test
	void everyTemplateCommandSatisfiesTheCommandWhitelist() {
		for (CbpPlanner.Template template : CbpPlanner.Template.values()) {
			CbpSpec spec = CbpPlanner.plan(template, OWNER, AGENT, "t", ANCHOR);
			for (CbpSpec.Entry entry : spec.entries()) {
				if (entry.isCommandBlock()) {
					assertTrue(CbpCommandPolicy.validate(entry.command()).isEmpty(),
						() -> template + " produced a non-whitelisted command: "
							+ entry.command());
				}
			}
		}
	}

	@Test
	void supportBlocksMayNotCarryCommands() {
		CbpSpec sneaky = CbpSpec.builder(OWNER, AGENT, "sneaky")
			.entry(ANCHOR, "minecraft:redstone_lamp", "give @p bread 1", false)
			.build();
		List<String> problems = sneaky.validate();
		assertFalse(problems.isEmpty());
		assertTrue(problems.get(0).contains("must not carry a command"), problems.get(0));
	}

	@Test
	void blocksOutsideBothWhitelistsAreRefused() {
		CbpSpec spec = CbpSpec.builder(OWNER, AGENT, "tnt")
			.support(ANCHOR, "minecraft:tnt", null, null)
			.build();
		List<String> problems = spec.validate();
		assertFalse(problems.isEmpty());
		assertTrue(problems.get(0).contains("neither a command block"), problems.get(0));
	}

	@Test
	void templateNamesResolveFromCommandsAndFromNaturalLanguage() {
		assertEquals(CbpPlanner.Template.DAY_NIGHT_CONTROLLER,
			CbpPlanner.Template.parse("day_night_controller"));
		assertEquals(CbpPlanner.Template.DAY_NIGHT_CONTROLLER,
			CbpPlanner.Template.fromPhrase("做一个真实命令方块昼夜控制器"));
		assertEquals(CbpPlanner.Template.PULSE_TEACHING_DEVICE,
			CbpPlanner.Template.fromPhrase("做一个命令方块脉冲教学装置"));
		assertNull(CbpPlanner.Template.fromPhrase("做一个电梯"),
			"an unknown device must be refused, not silently substituted");
	}

	@Test
	void theEntriesStayInsideACompactFootprint() {
		CbpSpec spec = CbpPlanner.plan(CbpPlanner.Template.DAY_NIGHT_CONTROLLER,
			OWNER, AGENT, "day-night", ANCHOR);
		var footprint = spec.footprint();
		assertTrue(footprint.volume() <= 64,
			"a template device must stay reviewable, was " + footprint.volume());
		assertTrue(footprint.contains(ANCHOR));
	}

	@Test
	void thePreviewNamesEveryBlockAndItsCommand() {
		CbpSpec spec = CbpPlanner.plan(CbpPlanner.Template.DAY_NIGHT_CONTROLLER,
			OWNER, AGENT, "day-night", ANCHOR);
		String preview = CbpPlanner.describe(spec, "minecraft:overworld");
		for (CbpSpec.Entry entry : spec.entries()) {
			assertTrue(preview.contains(String.valueOf(entry.pos().getX())),
				"preview lists each position");
			if (entry.isCommandBlock()) {
				assertTrue(preview.contains(entry.command()),
					"preview shows the command that will be baked in: " + entry.command());
			}
		}
		assertTrue(preview.contains("conditional"), preview);
		assertTrue(preview.contains("惰性"), "the preview says the blocks arrive inert");
	}
}
