package dev.squire.server.combat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 D1：护卫是一条长期 Policy，不是一次性任务。它必须能原样往返存档，
 * 并且保守迁移工作包 B 期间写下的旧格式——玩家已经下达的长期指令不能因为
 * 一次升级就消失。
 */
class GuardPolicyTest {

	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID AGENT = UUID.randomUUID();

	@Test
	void serializeParseRoundTripKeepsEveryField() {
		GuardPolicy original = new GuardPolicy(OWNER, AGENT, true, 20,
			Set.of(GuardPolicy.Rule.HOSTILES, GuardPolicy.Rule.PROJECTILES), 12345L);
		GuardPolicy parsed = GuardPolicy.parse(original.serialize(), OWNER, AGENT);

		assertEquals(20, parsed.radius());
		assertTrue(parsed.enabled());
		assertEquals(12345L, parsed.createdAt());
		assertTrue(parsed.covers(GuardPolicy.Rule.HOSTILES));
		assertTrue(parsed.covers(GuardPolicy.Rule.PROJECTILES));
		assertFalse(parsed.covers(GuardPolicy.Rule.OWNER_ATTACKERS));
	}

	@Test
	void disabledPolicyStaysDisabledAcrossRestart() {
		GuardPolicy off = GuardPolicy.enabled(OWNER, AGENT, 16, 1L).disable();
		assertFalse(GuardPolicy.parse(off.serialize(), OWNER, AGENT).enabled());
	}

	@Test
	void legacyRadiusOnlyFormMigratesToAnEnabledFullRulePolicy() {
		GuardPolicy migrated = GuardPolicy.parse("guard:16", OWNER, AGENT);
		assertTrue(migrated.enabled(), "an existing guard order must survive the upgrade");
		assertEquals(16, migrated.radius());
		assertEquals(GuardPolicy.ALL_RULES, migrated.rules());
	}

	@Test
	void radiusIsClampedIntoTheSupportedRange() {
		assertEquals(GuardPolicy.MAX_RADIUS,
			GuardPolicy.enabled(OWNER, AGENT, 999, 0L).radius());
		assertEquals(GuardPolicy.MIN_RADIUS,
			GuardPolicy.enabled(OWNER, AGENT, 1, 0L).radius());
	}

	@Test
	void unknownRuleNamesFromANewerVersionDoNotVoidThePolicy() {
		GuardPolicy parsed = GuardPolicy.parse(
			"guard:true:12:HOSTILES+TIME_TRAVEL:7", OWNER, AGENT);
		assertTrue(parsed.enabled());
		assertEquals(12, parsed.radius());
		assertTrue(parsed.covers(GuardPolicy.Rule.HOSTILES));
	}

	@Test
	void corruptOrForeignEntriesParseToNullInsteadOfThrowing() {
		assertNull(GuardPolicy.parse(null, OWNER, AGENT));
		assertNull(GuardPolicy.parse("lights:on", OWNER, AGENT));
		assertNull(GuardPolicy.parse("guard:true:not-a-number::0", OWNER, AGENT));
	}
}
