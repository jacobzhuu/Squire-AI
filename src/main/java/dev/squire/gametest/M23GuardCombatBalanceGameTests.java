package dev.squire.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.combat.CombatBalanceResult;
import dev.squire.server.combat.CombatBalanceTelemetry;
import dev.squire.server.combat.CombatStyle;
import dev.squire.server.profession.CombatStance;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.mob.AbstractPiglinEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Guard Combat Balance Validation v1.
 *
 * <p>These are measurement tests, not a hidden balance patch.  Every scenario uses
 * the real Guard runtime, real vanilla mobs, real equipment slots, and finite items.
 * Results are written to {@code build/reports/gametest/guard-combat-balance.json}.
 * Only capability facts (bow, shield, Warden avoidance, finite inventory) are hard
 * assertions; combat time and outcome remain observations.</p>
 */
public final class M23GuardCombatBalanceGameTests implements FabricGameTest {

	public static final String FLOOR = M0SpikeGameTests.FLOOR;
	private static final BlockPos OWNER_POS = new BlockPos(4, 2, 4);
	private static final BlockPos GUARD_POS = new BlockPos(4, 2, 5);

	// ------------------------------------------------------------------ registered matrix

	@GameTest(templateName = FLOOR, tickLimit = 5000,
		batchId = "squire-balance-01-level-loadout")
	public void baselineAcrossLevelsAndLoadouts(TestContext context) {
		run(context, List.of(
			scenario("1 Zombie", 1, Loadout.NAKED, CombatStance.BALANCED,
				wave(EntityType.ZOMBIE)),
			scenario("1 Zombie", 4, Loadout.IRON_SURVIVAL, CombatStance.BALANCED,
				wave(EntityType.ZOMBIE)),
			scenario("1 Zombie", 6, Loadout.DIAMOND, CombatStance.BALANCED,
				wave(EntityType.ZOMBIE)),
			scenario("1 Zombie", 8, Loadout.NETHERITE, CombatStance.BALANCED,
				wave(EntityType.ZOMBIE)),
			scenario("1 Zombie", 10, Loadout.ENDGAME_MAX, CombatStance.BALANCED,
				wave(EntityType.ZOMBIE))));
	}

	@GameTest(templateName = FLOOR, tickLimit = 7000,
		batchId = "squire-balance-02-crowds-healing")
	public void zombieCrowdsAndHealingEconomy(TestContext context) {
		ScenarioSpec stress = scenario("10 Zombies / 20-potion stress", 10,
			Loadout.ENDGAME_MAX, CombatStance.BALANCED,
			wave(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
				EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
				EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
				EntityType.ZOMBIE));
		stress.potionOverride = 20;
		stress.maxTicks = 1800;
		run(context, List.of(
			scenario("5 Zombies", 1, Loadout.NAKED, CombatStance.BALANCED,
				wave(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
					EntityType.ZOMBIE, EntityType.ZOMBIE)),
			scenario("5 Zombies", 6, Loadout.IRON_SURVIVAL, CombatStance.BALANCED,
				wave(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
					EntityType.ZOMBIE, EntityType.ZOMBIE)),
			scenario("10 Zombies", 10, Loadout.ENDGAME_MAX, CombatStance.BALANCED,
				wave(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
					EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
					EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
					EntityType.ZOMBIE)),
			stress));
	}

	@GameTest(templateName = FLOOR, tickLimit = 7000,
		batchId = "squire-balance-03-ordinary-ai")
	public void ordinaryRangedCreeperAndMixedPack(TestContext context) {
		ScenarioSpec skeleton = scenario("1 Skeleton", 4, Loadout.IRON_SURVIVAL,
			CombatStance.BALANCED, wave(EntityType.SKELETON));
		skeleton.requireBow = true;
		// Lv.4「弓箭使用」的规则是<b>远的用弓、天上的用弓、近的近战</b>；按敌人种类
		// 挑武器要到 Lv.5。默认 5 格的生成半径正好卡在换弓阈值上，等于在问 Lv.4 会不会
		// 做一件 Lv.5 才会的事——它以前能过，是因为自主反击那条路漏掉了职业闸门，
		// 用的是「什么都会」的判据。把骷髅放到真正的远处，这一条才是在验 Lv.4 自己。
		skeleton.spawnRadius = 10.0;

		ScenarioSpec shield = scenario("Shield timing / single Skeleton / no ammo", 6,
			Loadout.IRON_SURVIVAL, CombatStance.BALANCED,
			wave(EntityType.SKELETON));
		shield.removeArrows = true;
		shield.requireShield = true;
		shield.scriptedShieldArrow = true;
		shield.spawnRadius = 8.0;

		run(context, List.of(
			skeleton,
			scenario("1 Creeper", 6, Loadout.DIAMOND, CombatStance.BALANCED,
				wave(EntityType.CREEPER)),
			shield,
			scenario("3 Skeletons", 6, Loadout.IRON_SURVIVAL,
				CombatStance.BALANCED,
				wave(EntityType.SKELETON, EntityType.SKELETON,
					EntityType.SKELETON)),
			scenario("Mixed Pack (3Z/2S/1C)", 8, Loadout.NETHERITE,
				CombatStance.BALANCED,
				wave(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
					EntityType.SKELETON, EntityType.SKELETON, EntityType.CREEPER))));
	}

	@GameTest(templateName = FLOOR, tickLimit = 10000,
		batchId = "squire-balance-04-high-danger")
	public void highDangerOrdinaryEnemies(TestContext context) {
		run(context, List.of(
			scenario("3 Blazes", 8, Loadout.NETHERITE, CombatStance.BALANCED,
				wave(EntityType.BLAZE, EntityType.BLAZE, EntityType.BLAZE)),
			scenario("3 Wither Skeletons", 6, Loadout.DIAMOND, CombatStance.BALANCED,
				wave(EntityType.WITHER_SKELETON, EntityType.WITHER_SKELETON,
					EntityType.WITHER_SKELETON)),
			scenario("2 Piglin Brutes", 8, Loadout.NETHERITE, CombatStance.BALANCED,
				wave(EntityType.PIGLIN_BRUTE, EntityType.PIGLIN_BRUTE)),
			scenario("3 Vindicators", 10, Loadout.DIAMOND, CombatStance.BALANCED,
				wave(EntityType.VINDICATOR, EntityType.VINDICATOR, EntityType.VINDICATOR)),
			scenario("Evoker + Vindicator", 10, Loadout.NETHERITE,
				CombatStance.BALANCED, wave(EntityType.EVOKER, EntityType.VINDICATOR)),
			scenario("1 Ravager", 10, Loadout.ENDGAME_MAX, CombatStance.BALANCED,
				wave(EntityType.RAVAGER))));
	}

	@GameTest(templateName = FLOOR, tickLimit = 7500,
		batchId = "squire-balance-05-raid")
	public void fixedRaidWavesDiamondAndEndgame(TestContext context) {
		List<EntityType<? extends MobEntity>> wave1 = wave(EntityType.PILLAGER,
			EntityType.PILLAGER, EntityType.PILLAGER, EntityType.PILLAGER);
		List<EntityType<? extends MobEntity>> wave2 = wave(EntityType.PILLAGER,
			EntityType.PILLAGER, EntityType.PILLAGER, EntityType.PILLAGER,
			EntityType.VINDICATOR, EntityType.VINDICATOR);
		List<EntityType<? extends MobEntity>> wave3 = wave(EntityType.VINDICATOR,
			EntityType.VINDICATOR, EntityType.VINDICATOR, EntityType.EVOKER,
			EntityType.RAVAGER);
		ScenarioSpec diamond = scenario("Fixed Raid Waves", 10, Loadout.DIAMOND,
			CombatStance.BALANCED, wave1);
		diamond.waves = List.of(wave1, wave2, wave3);
		diamond.maxTicks = 3000;
		ScenarioSpec max = scenario("Fixed Raid Waves", 10, Loadout.ENDGAME_MAX,
			CombatStance.BALANCED, wave1);
		max.waves = List.of(wave1, wave2, wave3);
		max.maxTicks = 3000;
		run(context, List.of(diamond, max));
	}

	@GameTest(templateName = FLOOR, tickLimit = 4200,
		batchId = "squire-balance-06-wither")
	public void witherExtremeThreat(TestContext context) {
		ScenarioSpec wither = scenario("Wither", 10, Loadout.ENDGAME_MAX,
			CombatStance.BALANCED, wave(EntityType.WITHER));
		wither.targetOwner = true;
		wither.ownerProvokes = true;
		wither.maxTicks = 3600;
		run(context, List.of(wither));
	}

	@GameTest(templateName = FLOOR, tickLimit = 1400,
		batchId = "squire-balance-07-warden")
	public void wardenAvoidanceAtNineAndTen(TestContext context) {
		ScenarioSpec nine = scenario("Warden avoidance", 9, Loadout.DIAMOND,
			CombatStance.BALANCED, wave(EntityType.WARDEN));
		nine.avoidance = true;
		nine.targetOwner = true;
		nine.moveOwnerAway = true;
		nine.maxTicks = 240;

		ScenarioSpec ten = scenario("Warden after owner attacks", 10,
			Loadout.ENDGAME_MAX, CombatStance.BALANCED, wave(EntityType.WARDEN));
		ten.avoidance = true;
		ten.targetOwner = true;
		ten.ownerProvokes = true;
		ten.moveOwnerAway = true;
		ten.maxTicks = 240;
		run(context, List.of(nine, ten));
	}

	@GameTest(templateName = FLOOR, tickLimit = 1600,
		batchId = "squire-balance-08-flying-target")
	public void controlledDragonFlyingTargetSubscene(TestContext context) {
		ScenarioSpec proxy = scenario("Ender Dragon flying-target proxy (Phantom)", 8,
			Loadout.ENDGAME_MAX, CombatStance.BALANCED, wave(EntityType.PHANTOM));
		proxy.flyingProxy = true;
		proxy.requireBow = true;
		run(context, List.of(proxy));
	}

	@GameTest(templateName = FLOOR, tickLimit = 7500,
		batchId = "squire-balance-09-owner-protection")
	public void ownerProtectionScenarios(TestContext context) {
		ScenarioSpec zombies = scenario("Owner A: 5 Zombies", 8, Loadout.DIAMOND,
			CombatStance.DEFENSIVE,
			wave(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
				EntityType.ZOMBIE, EntityType.ZOMBIE));
		zombies.targetOwner = true;
		ScenarioSpec skeletons = scenario("Owner B: 3 Skeletons", 8, Loadout.DIAMOND,
			CombatStance.BALANCED,
			wave(EntityType.SKELETON, EntityType.SKELETON, EntityType.SKELETON));
		skeletons.targetOwner = true;
		ScenarioSpec mixed = scenario("Owner C: Mixed Pack", 10, Loadout.ENDGAME_MAX,
			CombatStance.AGGRESSIVE,
			wave(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
				EntityType.SKELETON, EntityType.SKELETON, EntityType.CREEPER));
		mixed.targetOwner = true;
		ScenarioSpec emergency = scenario("Owner D: 25% HP Mixed Pack", 10,
			Loadout.ENDGAME_MAX, CombatStance.AGGRESSIVE,
			wave(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
				EntityType.SKELETON, EntityType.SKELETON, EntityType.CREEPER));
		emergency.targetOwner = true;
		emergency.ownerHealthFraction = 0.25f;
		run(context, List.of(zombies, skeletons, mixed, emergency));
	}

	@GameTest(templateName = FLOOR, tickLimit = 1800,
		batchId = "squire-balance-10-stances")
	public void stancePursuitHasVisibleDifferences(TestContext context) {
		ScenarioSpec defensive = stanceProbe(CombatStance.DEFENSIVE);
		ScenarioSpec balanced = stanceProbe(CombatStance.BALANCED);
		ScenarioSpec aggressive = stanceProbe(CombatStance.AGGRESSIVE);
		run(context, List.of(defensive, balanced, aggressive));
	}

	private static ScenarioSpec stanceProbe(CombatStance stance) {
		ScenarioSpec spec = scenario("Stance pursuit probe", 8, Loadout.NETHERITE,
			stance, wave(EntityType.RAVAGER));
		spec.stanceProbe = true;
		spec.openArena = true;
		spec.radius = 8;
		spec.spawnRadius = 6.0;
		spec.maxTicks = 260;
		return spec;
	}

	// ------------------------------------------------------------------ sequence runner

	private static void run(TestContext context, List<ScenarioSpec> specs) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		context.getWorld().setTimeOfDay(18000L);
		Sequence sequence = new Sequence(context, SquireRuntime.get(), specs);
		context.runAtEveryTick(sequence::tick);
	}

	private static final class Sequence {
		private final TestContext context;
		private final SquireRuntime runtime;
		private final List<ScenarioSpec> specs;
		private int index;
		private long nextStartTick = 5;
		private ActiveFight fight;

		Sequence(TestContext context, SquireRuntime runtime, List<ScenarioSpec> specs) {
			this.context = context;
			this.runtime = runtime;
			this.specs = List.copyOf(specs);
		}

		void tick() {
			long now = context.getTick();
			if (fight == null) {
				if (index >= specs.size()) {
					context.getWorld().setTimeOfDay(6000L);
					context.complete();
					return;
				}
				if (now >= nextStartTick) {
					fight = new ActiveFight(context, runtime, specs.get(index), index);
				}
				return;
			}
			if (fight.sample(now)) {
				fight.finish(now);
				fight = null;
				index++;
				nextStartTick = now + 10;
			}
		}
	}

	private static final class ActiveFight {
		private final TestContext context;
		private final SquireRuntime runtime;
		private final ServerWorld world;
		private final ScenarioSpec spec;
		private final FakePlayer owner;
		private final AvatarEntity guard;
		private final List<MobEntity> enemies = new ArrayList<>();
		private final Map<UUID, Double> lastEnemyPool = new LinkedHashMap<>();
		private final long startedAt;
		private final int initialArrows;
		private final int initialWeaponDurability;
		private double lastOwnerPool;
		private double damageDealt;
		private double ownerDamageTaken;
		private int nextWave;
		private long nextWaveTick = Long.MAX_VALUE;
		private boolean ownerScripted;
		private boolean probeMoved;
		private boolean shieldArrowFired;
		private String result;

		ActiveFight(TestContext context, SquireRuntime runtime, ScenarioSpec spec,
				int sequenceIndex) {
			this.context = context;
			this.runtime = runtime;
			this.world = context.getWorld();
			this.spec = spec;
			buildArena(context, spec.openArena);
			TestSupport.clearHostilesNear(world, context.getAbsolutePos(OWNER_POS), 48);

			String ownerName = "balance-" + Math.abs(spec.name.hashCode()) + "-"
				+ sequenceIndex + "-" + context.getTick();
			owner = FakePlayer.get(world, new GameProfile(
				UUID.nameUUIDFromBytes(ownerName.getBytes(StandardCharsets.UTF_8)), ownerName));
			world.spawnEntity(owner);
			place(owner, context, OWNER_POS);

			AvatarEntity first = runtime.summonFor(owner);
			ProfessionData profession = runtime.professionOf(first);
			profession.setProfession(SquireProfession.GUARD);
			profession.level = spec.level;
			profession.stance = spec.stance.id();
			guard = runtime.summonFor(owner); // real materialization applies level attributes
			var profile = runtime.profileOf(guard);
			profile.traits.clear();
			profile.equippedAbilities.clear();
			guard.applyTraits(List.of());
			clearInventory(guard);
			place(guard, context, GUARD_POS);
			applyLoadout(guard, spec.loadout, spec.potionOverride);
			if (spec.removeArrows) {
				removeArrows(guard);
			}
			guard.releaseWeaponChoice();
			guard.setCombatStyle(CombatStyle.Style.AUTO);
			guard.setHealth(guard.getMaxHealth());
			owner.setHealth(Math.max(1.0f,
				owner.getMaxHealth() * spec.ownerHealthFraction));
			runtime.startGuard(owner, spec.radius, true);

			initialArrows = count(guard, Items.ARROW);
			initialWeaponDurability = weaponDurabilityRemaining(guard);
			lastOwnerPool = pool(owner);
			startedAt = context.getTick();
			CombatBalanceTelemetry.begin(guard.agentId());
			spawnWave();
		}

		boolean sample(long now) {
			long elapsed = now - startedAt;
			runScripts(elapsed);

			double ownerNow = pool(owner);
			if (ownerNow < lastOwnerPool) {
				ownerDamageTaken += lastOwnerPool - ownerNow;
			}
			lastOwnerPool = ownerNow;

			for (MobEntity enemy : enemies) {
				double current = pool(enemy);
				double previous = lastEnemyPool.getOrDefault(enemy.getUuid(), current);
				if (current < previous) {
					damageDealt += previous - current;
				}
				lastEnemyPool.put(enemy.getUuid(), current);
			}

			boolean waveDead = currentWaveDead();
			if (waveDead && nextWave < spec.waves.size()
					&& nextWaveTick == Long.MAX_VALUE) {
				nextWaveTick = now + 20;
			}
			if (nextWaveTick != Long.MAX_VALUE && now >= nextWaveTick) {
				spawnWave();
				nextWaveTick = Long.MAX_VALUE;
			}

			if (!guard.isAlive()) {
				result = "LOSS";
				return true;
			}
			if (!owner.isAlive()) {
				result = "OWNER_DIED";
				return true;
			}
			if (allWavesDead()) {
				result = spec.avoidance ? "AVOIDED" : "WIN";
				return true;
			}
			if (elapsed >= spec.maxTicks) {
				result = timedResult();
				return true;
			}
			return false;
		}

		private void runScripts(long elapsed) {
			if (spec.scriptedShieldArrow && !shieldArrowFired && elapsed >= 8
					&& guard.blocking() && !enemies.isEmpty()) {
				MobEntity shooter = enemies.get(0);
				if (shooter.isAlive()) {
					ArrowEntity arrow = new ArrowEntity(world, shooter);
					Vec3d from = shooter.getEyePos();
					Vec3d to = guard.getPos().add(0.0, guard.getHeight() * 0.55, 0.0);
					Vec3d velocity = to.subtract(from);
					arrow.setVelocity(velocity.x, velocity.y, velocity.z, 1.6F, 0.0F);
					world.spawnEntity(arrow);
				}
				shieldArrowFired = true;
			}
			if (spec.ownerProvokes && !ownerScripted && elapsed >= 20 && !enemies.isEmpty()) {
				MobEntity target = enemies.get(0);
				if (target.isAlive()) {
					owner.onAttacking(target); // focus-fire signal, without contaminating damage
					target.setTarget(owner);
				}
				ownerScripted = true;
			}
			if (spec.moveOwnerAway && elapsed >= 60 && !probeMoved) {
				Vec3d away = context.getAbsolute(Vec3d.ofBottomCenter(
					new BlockPos(18, 2, 4)));
				owner.refreshPositionAndAngles(away.x, away.y, away.z, 0f, 0f);
				probeMoved = true;
			}
			if (spec.stanceProbe && elapsed >= 30 && !probeMoved && !enemies.isEmpty()) {
				MobEntity target = enemies.get(0);
				Vec3d far = context.getAbsolute(Vec3d.ofBottomCenter(
					new BlockPos(20, 2, 4)));
				target.refreshPositionAndAngles(far.x, far.y, far.z, 0f, 0f);
				probeMoved = true;
			}
		}

		private String timedResult() {
			if (spec.avoidance) {
				return damageDealt <= 0.001 && guard.getTarget() == null
					? "AVOIDED" : "AGGROED";
			}
			if (spec.stanceProbe) {
				return damageDealt > 0.001 || guard.getTarget() != null
					? "ENGAGED" : "NOT_ENGAGED";
			}
			return "TIMEOUT";
		}

		private void spawnWave() {
			List<EntityType<? extends MobEntity>> wave = spec.waves.get(nextWave++);
			for (int i = 0; i < wave.size(); i++) {
				MobEntity mob = wave.get(i).create(world);
				if (mob == null) {
					context.throwGameTestException("could not create " + wave.get(i));
					return;
				}
				double angle = (Math.PI * 2.0 * i) / Math.max(1, wave.size());
				double radius = spec.spawnRadius;
				Vec3d centre = context.getAbsolute(Vec3d.ofBottomCenter(OWNER_POS));
				double y = centre.y;
				if (spec.flyingProxy) {
					y += 5.0;
				}
				mob.refreshPositionAndAngles(centre.x + Math.cos(angle) * radius, y,
					centre.z + Math.sin(angle) * radius, 0f, 0f);
				mob.setPersistent();
				// GameTest batches share a world's clock. A concurrent test may switch it
				// to daytime; a pumpkin has no armour value and prevents that global race
				// from being mistaken for Guard damage against zombies/skeletons.
				if (mob.getType() == EntityType.ZOMBIE
						|| mob.getType() == EntityType.SKELETON) {
					mob.equipStack(EquipmentSlot.HEAD,
						new ItemStack(Items.CARVED_PUMPKIN));
					mob.setEquipmentDropChance(EquipmentSlot.HEAD, 0.0F);
				}
				if (mob instanceof AbstractPiglinEntity piglin) {
					piglin.setImmuneToZombification(true);
				}
				if (spec.stanceProbe || spec.flyingProxy) {
					mob.setAiDisabled(true);
				}
				if (mob instanceof PhantomEntity) {
					mob.setNoGravity(true);
				}
				world.spawnEntity(mob);
				mob.setTarget(spec.stanceProbe ? null
					: (spec.targetOwner ? owner : guard));
				enemies.add(mob);
				lastEnemyPool.put(mob.getUuid(), pool(mob));
			}
		}

		private boolean currentWaveDead() {
			if (nextWave <= 0) {
				return false;
			}
			int prior = 0;
			for (int i = 0; i < nextWave - 1; i++) {
				prior += spec.waves.get(i).size();
			}
			int end = prior + spec.waves.get(nextWave - 1).size();
			for (int i = prior; i < Math.min(end, enemies.size()); i++) {
				if (enemies.get(i).isAlive()) {
					return false;
				}
			}
			return true;
		}

		private boolean allWavesDead() {
			return nextWave >= spec.waves.size() && currentWaveDead();
		}

		void finish(long now) {
			int arrowsLeft = count(guard, Items.ARROW);
			int durabilityNow = weaponDurabilityRemaining(guard);
			Map<String, Integer> resources = remainingResources(guard);
			CombatBalanceResult measured = CombatBalanceTelemetry.finish(guard.agentId(),
				spec.name, spec.level, spec.loadout.id, spec.stance.id(), result,
				now - startedAt, damageDealt, ownerDamageTaken,
				Math.max(0, initialArrows - arrowsLeft),
				Math.max(0, initialWeaponDurability - durabilityNow),
				guard.getHealth(), guard.getAbsorptionAmount(), resources);
			BalanceReport.add(measured);

			// Hard assertions are limited to behaviour and inventory conservation.
			if (spec.requireBow) {
				context.assertTrue(measured.arrowsConsumed() > 0,
					"Lv." + spec.level + " never fired a real arrow in " + spec.name);
			}
			if (spec.requireShield) {
				context.assertTrue(measured.shieldBlocks() > 0,
					"Lv." + spec.level + " never completed a real shield block");
			}
			if (spec.avoidance) {
				context.assertTrue(measured.hostileEngagements() == 0
						&& measured.damageDealt() == 0.0,
					"high-level Guard actively engaged a Warden: " + measured);
			}
			context.assertTrue(arrowsLeft >= 0 && resources.get("healingPotions") >= 0,
				"finite inventory became negative");

			cleanupEntities();
		}

		private void cleanupEntities() {
			CombatBalanceTelemetry.cancel(guard.agentId());
			for (MobEntity enemy : enemies) {
				enemy.discard();
			}
			runtime.stopGuard(owner);
			runtime.resolveAvatarFor(owner.getUuid()).ifPresent(Entity::discard);
			owner.discard();
			Box area = Box.of(context.getAbsolute(Vec3d.ofCenter(OWNER_POS)), 64, 32, 64);
			for (Entity entity : world.getOtherEntities(null, area,
					e -> e instanceof net.minecraft.entity.projectile.ProjectileEntity
						|| e instanceof net.minecraft.entity.ItemEntity)) {
				entity.discard();
			}
			TestSupport.clearHostilesNear(world, context.getAbsolutePos(OWNER_POS), 48);
		}
	}

	// ------------------------------------------------------------------ setup helpers

	private enum Loadout {
		NAKED("Naked"),
		IRON_SURVIVAL("Iron Survival"),
		DIAMOND("Diamond"),
		NETHERITE("Netherite"),
		ENDGAME_MAX("Endgame Max");

		final String id;

		Loadout(String id) {
			this.id = id;
		}
	}

	private static final class ScenarioSpec {
		final String name;
		final int level;
		final Loadout loadout;
		final CombatStance stance;
		List<List<EntityType<? extends MobEntity>>> waves;
		int radius = 12;
		int maxTicks = 1200;
		boolean targetOwner;
		float ownerHealthFraction = 1.0f;
		boolean ownerProvokes;
		boolean avoidance;
		boolean moveOwnerAway;
		boolean stanceProbe;
		boolean openArena;
		boolean flyingProxy;
		boolean removeArrows;
		boolean requireBow;
		boolean requireShield;
		boolean scriptedShieldArrow;
		double spawnRadius = 5.0;
		int potionOverride = -1;

		ScenarioSpec(String name, int level, Loadout loadout, CombatStance stance,
				List<EntityType<? extends MobEntity>> firstWave) {
			this.name = name;
			this.level = level;
			this.loadout = loadout;
			this.stance = stance;
			this.waves = List.of(firstWave);
		}
	}

	private static ScenarioSpec scenario(String name, int level, Loadout loadout,
			CombatStance stance, List<EntityType<? extends MobEntity>> firstWave) {
		return new ScenarioSpec(name, level, loadout, stance, firstWave);
	}

	@SafeVarargs
	private static List<EntityType<? extends MobEntity>> wave(
			EntityType<? extends MobEntity>... types) {
		return List.of(types);
	}

	private static void buildArena(TestContext context, boolean open) {
		int min = open ? -4 : -7;
		int max = open ? 24 : 15;
		for (int x = min; x <= max; x++) {
			for (int z = min; z <= max; z++) {
				context.getWorld().setBlockState(context.getAbsolutePos(
					new BlockPos(x, 1, z)), Blocks.SMOOTH_STONE.getDefaultState());
			}
		}
		if (!open) {
			for (int y = 2; y <= 5; y++) {
				for (int i = min; i <= max; i++) {
					context.getWorld().setBlockState(context.getAbsolutePos(
						new BlockPos(min, y, i)), Blocks.BEDROCK.getDefaultState());
					context.getWorld().setBlockState(context.getAbsolutePos(
						new BlockPos(max, y, i)), Blocks.BEDROCK.getDefaultState());
					context.getWorld().setBlockState(context.getAbsolutePos(
						new BlockPos(i, y, min)), Blocks.BEDROCK.getDefaultState());
					context.getWorld().setBlockState(context.getAbsolutePos(
						new BlockPos(i, y, max)), Blocks.BEDROCK.getDefaultState());
				}
			}
		}
	}

	private static void place(Entity entity, TestContext context, BlockPos relative) {
		Vec3d feet = context.getAbsolute(Vec3d.ofBottomCenter(relative));
		entity.refreshPositionAndAngles(feet.x, feet.y, feet.z, 0f, 0f);
	}

	private static void clearInventory(AvatarEntity guard) {
		for (int i = 0; i < AvatarInventory.MAIN_SIZE; i++) {
			guard.items().mainInventory().setStack(i, ItemStack.EMPTY);
		}
		for (EquipmentSlot slot : AvatarInventory.EQUIPMENT_SLOTS) {
			guard.equipStack(slot, ItemStack.EMPTY);
		}
	}

	private static void applyLoadout(AvatarEntity guard, Loadout loadout,
			int potionOverride) {
		switch (loadout) {
			case NAKED -> {
				give(guard, new ItemStack(Items.IRON_SWORD));
				give(guard, new ItemStack(Items.BREAD, 4));
			}
			case IRON_SURVIVAL -> {
				equipArmor(guard, Items.IRON_HELMET, Items.IRON_CHESTPLATE,
					Items.IRON_LEGGINGS, Items.IRON_BOOTS);
				giveStandardCombatKit(guard, new ItemStack(Items.IRON_SWORD), 32,
					new ItemStack(Items.BREAD, 8), Potions.HEALING,
					potionOverride >= 0 ? potionOverride : 3);
			}
			case DIAMOND -> {
				equipArmor(guard, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
					Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
				giveStandardCombatKit(guard, new ItemStack(Items.DIAMOND_SWORD), 48,
					new ItemStack(Items.COOKED_BEEF, 12), Potions.HEALING,
					potionOverride >= 0 ? potionOverride : 4);
			}
			case NETHERITE -> {
				equipArmor(guard, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
					Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS);
				giveStandardCombatKit(guard, new ItemStack(Items.NETHERITE_SWORD), 48,
					new ItemStack(Items.COOKED_BEEF, 12), Potions.HEALING,
					potionOverride >= 0 ? potionOverride : 4);
			}
			case ENDGAME_MAX -> {
				ItemStack helmet = maxArmor(new ItemStack(Items.NETHERITE_HELMET));
				ItemStack chest = maxArmor(new ItemStack(Items.NETHERITE_CHESTPLATE));
				ItemStack legs = maxArmor(new ItemStack(Items.NETHERITE_LEGGINGS));
				ItemStack boots = maxArmor(new ItemStack(Items.NETHERITE_BOOTS));
				guard.equipStack(EquipmentSlot.HEAD, helmet);
				guard.equipStack(EquipmentSlot.CHEST, chest);
				guard.equipStack(EquipmentSlot.LEGS, legs);
				guard.equipStack(EquipmentSlot.FEET, boots);

				ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
				sword.addEnchantment(Enchantments.SHARPNESS, 5);
				sword.addEnchantment(Enchantments.UNBREAKING, 3);
				sword.addEnchantment(Enchantments.MENDING, 1);
				ItemStack bow = new ItemStack(Items.BOW);
				bow.addEnchantment(Enchantments.POWER, 5);
				bow.addEnchantment(Enchantments.UNBREAKING, 3);
				bow.addEnchantment(Enchantments.MENDING, 1);
				give(guard, sword);
				give(guard, bow);
				give(guard, new ItemStack(Items.SHIELD));
				give(guard, new ItemStack(Items.ARROW, 64));
				give(guard, new ItemStack(Items.COOKED_BEEF, 16));
				givePotions(guard, Potions.STRONG_HEALING,
					potionOverride >= 0 ? potionOverride : 6);
			}
		}
	}

	private static ItemStack maxArmor(ItemStack stack) {
		stack.addEnchantment(Enchantments.PROTECTION, 4);
		stack.addEnchantment(Enchantments.UNBREAKING, 3);
		stack.addEnchantment(Enchantments.MENDING, 1);
		return stack;
	}

	private static void equipArmor(AvatarEntity guard, net.minecraft.item.Item helmet,
			net.minecraft.item.Item chest, net.minecraft.item.Item legs,
			net.minecraft.item.Item boots) {
		guard.equipStack(EquipmentSlot.HEAD, new ItemStack(helmet));
		guard.equipStack(EquipmentSlot.CHEST, new ItemStack(chest));
		guard.equipStack(EquipmentSlot.LEGS, new ItemStack(legs));
		guard.equipStack(EquipmentSlot.FEET, new ItemStack(boots));
	}

	private static void giveStandardCombatKit(AvatarEntity guard, ItemStack sword,
			int arrows, ItemStack food, net.minecraft.potion.Potion potion, int potions) {
		give(guard, sword);
		give(guard, new ItemStack(Items.BOW));
		give(guard, new ItemStack(Items.SHIELD));
		give(guard, new ItemStack(Items.ARROW, arrows));
		give(guard, food);
		givePotions(guard, potion, potions);
	}

	private static void givePotions(AvatarEntity guard,
			net.minecraft.potion.Potion potion, int count) {
		for (int i = 0; i < count; i++) {
			give(guard, PotionUtil.setPotion(new ItemStack(Items.POTION), potion));
		}
	}

	private static void give(AvatarEntity guard, ItemStack stack) {
		guard.items().insert(stack);
	}

	private static void removeArrows(AvatarEntity guard) {
		for (int i = 0; i < AvatarInventory.MAIN_SIZE; i++) {
			ItemStack stack = guard.items().mainInventory().getStack(i);
			if (stack.getItem() instanceof net.minecraft.item.ArrowItem) {
				guard.items().mainInventory().setStack(i, ItemStack.EMPTY);
			}
		}
	}

	private static int count(AvatarEntity guard, net.minecraft.item.Item item) {
		int total = 0;
		for (int i = 0; i < AvatarInventory.MAIN_SIZE; i++) {
			ItemStack stack = guard.items().mainInventory().getStack(i);
			if (stack.isOf(item)) {
				total += stack.getCount();
			}
		}
		for (EquipmentSlot slot : AvatarInventory.EQUIPMENT_SLOTS) {
			ItemStack stack = guard.getEquippedStack(slot);
			if (stack.isOf(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static int countFood(AvatarEntity guard) {
		return countMatching(guard, stack -> stack.getItem().getFoodComponent() != null);
	}

	private static int countMatching(AvatarEntity guard,
			java.util.function.Predicate<ItemStack> predicate) {
		int total = 0;
		for (int i = 0; i < AvatarInventory.MAIN_SIZE; i++) {
			ItemStack stack = guard.items().mainInventory().getStack(i);
			if (!stack.isEmpty() && predicate.test(stack)) {
				total += stack.getCount();
			}
		}
		for (EquipmentSlot slot : AvatarInventory.EQUIPMENT_SLOTS) {
			ItemStack stack = guard.getEquippedStack(slot);
			if (!stack.isEmpty() && predicate.test(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static int weaponDurabilityRemaining(AvatarEntity guard) {
		return countMatchingDurability(guard, stack -> stack.getItem() instanceof SwordItem
			|| stack.getItem() instanceof AxeItem || stack.getItem() instanceof BowItem
			|| stack.getItem() instanceof TridentItem);
	}

	private static int countMatchingDurability(AvatarEntity guard,
			java.util.function.Predicate<ItemStack> predicate) {
		int total = 0;
		for (int i = 0; i < AvatarInventory.MAIN_SIZE; i++) {
			ItemStack stack = guard.items().mainInventory().getStack(i);
			if (!stack.isEmpty() && stack.isDamageable() && predicate.test(stack)) {
				total += stack.getMaxDamage() - stack.getDamage();
			}
		}
		for (EquipmentSlot slot : AvatarInventory.EQUIPMENT_SLOTS) {
			ItemStack stack = guard.getEquippedStack(slot);
			if (!stack.isEmpty() && stack.isDamageable() && predicate.test(stack)) {
				total += stack.getMaxDamage() - stack.getDamage();
			}
		}
		return total;
	}

	private static Map<String, Integer> remainingResources(AvatarEntity guard) {
		Map<String, Integer> resources = new LinkedHashMap<>();
		resources.put("arrows", count(guard, Items.ARROW));
		resources.put("food", countFood(guard));
		resources.put("suppliedFood", count(guard, Items.BREAD)
			+ count(guard, Items.COOKED_BEEF));
		resources.put("lootFood", count(guard, Items.ROTTEN_FLESH));
		resources.put("healingPotions", count(guard, Items.POTION));
		resources.put("glassBottles", count(guard, Items.GLASS_BOTTLE));
		resources.put("shieldDurability", shieldDurability(guard));
		resources.put("armorDurability", armorDurability(guard));
		resources.put("weaponDurability", weaponDurabilityRemaining(guard));
		return resources;
	}

	private static int shieldDurability(AvatarEntity guard) {
		return countMatchingDurability(guard,
			stack -> stack.getItem() instanceof net.minecraft.item.ShieldItem);
	}

	private static int armorDurability(AvatarEntity guard) {
		return countMatchingDurability(guard, stack -> stack.getItem() instanceof ArmorItem);
	}

	private static double pool(LivingEntity entity) {
		return Math.max(0.0, entity.getHealth())
			+ Math.max(0.0, entity.getAbsorptionAmount());
	}

	// ------------------------------------------------------------------ durable JSON report

	private static final class BalanceReport {
		private static final List<CombatBalanceResult> RESULTS = new ArrayList<>();
		private static final Path OUTPUT = FabricLoader.getInstance().getGameDir()
			.resolve("..").resolve("reports").resolve("gametest")
			.resolve("guard-combat-balance.json").normalize();

		private static synchronized void add(CombatBalanceResult result) {
			RESULTS.add(result);
			RESULTS.sort(Comparator.comparing(CombatBalanceResult::scenario)
				.thenComparingInt(CombatBalanceResult::guardLevel)
				.thenComparing(CombatBalanceResult::loadout)
				.thenComparing(CombatBalanceResult::stance));
			try {
				Files.createDirectories(OUTPUT.getParent());
				Files.writeString(OUTPUT,
					new GsonBuilder().setPrettyPrinting().create().toJson(RESULTS),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING);
			} catch (IOException e) {
				throw new IllegalStateException("cannot write combat balance report to "
					+ OUTPUT, e);
			}
		}
	}
}
