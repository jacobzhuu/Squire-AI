package dev.squire.server.body.avatar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.squire.api.body.AgentBody;
import dev.squire.api.body.AgentPhysicalState;
import dev.squire.api.body.BodyCapabilities;
import dev.squire.api.body.EmoteType;
import dev.squire.api.body.InteractionResult;
import dev.squire.api.body.InventoryView;
import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The default agent body (ADR-003): a path-aware avatar with player-like appearance.
 *
 * <p>Implements {@link AgentBody} directly — upper layers must program against the
 * interface and never cast to this class (spec section 8).</p>
 *
 * <p>Movement is runtime-owned: no vanilla wander goal exists, the body moves only when
 * a mode commands it (FOLLOW / requested move). Idle look goals are cosmetic.</p>
 */
public class AvatarEntity extends PathAwareEntity implements AgentBody {
	private long oathDeadline;
    private Vec3d oathAnchor;
    private int nextMeleeAttackTick;
    private Vec3d selfDefenceOrigin;
    public Vec3d selfDefenceOrigin() { return selfDefenceOrigin == null ? getPos() : selfDefenceOrigin; }
    public boolean oathActive() { return oathDeadline > 0; }
    public long oathDeadline() { return oathDeadline; }
    public Vec3d oathAnchor() { return oathAnchor == null ? getPos() : oathAnchor; }
    public void beginOath(long deadline) {
        oathDeadline = deadline;
        oathAnchor = getPos();
        cancelDraw();
        getNavigation().stop();
        setAiDisabled(true);
    }
    public void endOath() { oathDeadline = 0; setAiDisabled(false); }

    @Override
    public boolean tryAttack(net.minecraft.entity.Entity target) {
        if (getWorld().isClient || !isAlive() || target == null || !target.isAlive() || isOwner(target)
                || squaredDistanceTo(target) > MELEE_REACH_SQ || age < nextMeleeAttackTick) return false;
        nextMeleeAttackTick = age + dev.squire.server.combat.ProfessionCombatRules.attackInterval(this);
        swingHand(net.minecraft.util.Hand.MAIN_HAND);
        return super.tryAttack(target);
    }

    private static final String NBT_AGENT_ID = "SquireAgentId";
	private static final String NBT_OWNER = "SquireOwner";
	private static final String NBT_HOME = "SquireHome";
	/** 背包槽。老存档没有这个键，读出来就是空的——不需要迁移代码。 */
	private static final String NBT_BACKPACK = "SquireBackpack";
	/**
	 * 名牌的<b>本名</b>。必须和显示用的 CustomName 分开存：CustomName 是带着
	 * 「[跟随]」这类装饰的成品，拿它当本名会让状态标签一次次叠上去。
	 */
	private static final String NBT_BASE_NAME = "SquireBaseName";

	/**
	 * Runtime movement mode; only FOLLOW/PATROL self-initiate movement (spec section 85).
	 *
	 * <p>PATROL 是给右键菜单用的第三种基础状态：以锚点为中心来回走动，
	 * 适合让伙伴守在某个地方而不是杵着不动。</p>
	 */
	public enum MovementMode {
		IDLE, FOLLOW, STAY, PATROL
	}

	/**
	 * 玩家<b>看得见</b>的忙碌状态。这个字段唯一的用途就是回答"他到底在干嘛"——
	 * 在此之前伙伴思考 60 秒和伙伴宕机在屏幕上完全是一回事，玩家只能干等。
	 *
	 * <p>纯服务端字段：{@code ServerWorld.spawnParticles} 自己会把粒子广播给
	 * 附近客户端，名牌走原版实体同步，所以不需要任何客户端代码或自定义封包。</p>
	 */
	public enum ActivityState {
		/** 空闲：不发粒子，名牌恢复本名。 */
		IDLE("", null),
		/** 正在等 LLM 规划。 */
		THINKING(" 思考中…", ParticleTypes.ENCHANT),
		/** 正在执行任务。 */
		WORKING(" 干活中…", ParticleTypes.HAPPY_VILLAGER),
		/** 刚失败，短暂显示后自动回到 IDLE。 */
		FAILED(" 出错了", ParticleTypes.SMOKE);

		private final String nameSuffix;
		private final net.minecraft.particle.ParticleEffect particle;

		ActivityState(String nameSuffix, net.minecraft.particle.ParticleEffect particle) {
			this.nameSuffix = nameSuffix;
			this.particle = particle;
		}

		public String nameSuffix() {
			return nameSuffix;
		}

		public net.minecraft.particle.ParticleEffect particle() {
			return particle;
		}
	}

	/** 每隔几 tick 冒一次粒子；太密会刷屏，太疏会显得卡住。 */
	private static final int ACTIVITY_PARTICLE_INTERVAL = 8;

	/** FAILED 只是一个瞬时提示，显示这么久之后自动回到 IDLE。 */
	private static final int FAILED_STATE_TICKS = 60;

	/** 自动捡拾的扫描间隔与半径：够近才捡，免得看起来在隔空吸物。 */
	private static final int AMBIENT_PICKUP_INTERVAL = 10;
	private static final double AMBIENT_PICKUP_RADIUS = 2.5;

	/**
	 * 跟随距离带：超过 6 格才起步，进到 3 格就停。
	 *
	 * <p>以前是 4 格起步、三格停，带太窄，伙伴一直在“起步 → 到位 → 再起步”之间
	 * 抽搭，看起来像卡住了。拉开之后他走得连贯，也才有空间做“挡在前面”。</p>
	 */
	private static final double FOLLOW_START_DISTANCE_SQ = 36.0;
	private static final double FOLLOW_STOP_DISTANCE_SQ = 9.0;
	/**
	 * 主人持续走动时不要等到拉开六格才重新起步。Goal 会保持运行，并在两格多一点
	 * 之外立刻续上路径；主人停下后仍回到上面的 6/3 格宽松带，避免贴身挪来挪去。
	 */
	private static final double FOLLOW_MOVING_START_DISTANCE_SQ = 6.25;
	private static final double FOLLOW_MOVING_STOP_DISTANCE_SQ = 5.0625;
	/** 主人停下后仍视作“在移动”的短暂缓冲，吸收网络位置的小抖动。 */
	private static final int FOLLOW_OWNER_MOVEMENT_GRACE_TICKS = 10;

	/**
	 * 超过 12 格直接传送到主人身边——和被驯服的狼是同一个数（144 = 12²）。
	 *
	 * <p>原来是 30 格。那个距离在实际游戏里几乎等于「永远不会传送」：主人骑马跑一段、
	 * 下个矿洞、过条河，伙伴就在后面自己找路，而寻路本来就跨不过去。玩家看到的
	 * 是「跟随根本不跟」。狗的机制之所以让人放心，就是因为它的传送门槛很低。</p>
	 */
	public static final int FOLLOW_TELEPORT_DEFAULT = 12;
	public static final int FOLLOW_TELEPORT_MIN = 6;
	public static final int FOLLOW_TELEPORT_MAX = 64;

	/**
	 * 玩家自己设的传送距离（格）。12 是狗的数，但每个人的玩法不一样：喜欢让伙伴
	 * 老老实实走路的调大，嫌他老掉队的调小。
	 */
	private int followTeleportDistance = FOLLOW_TELEPORT_DEFAULT;

	private static final String NBT_FOLLOW_TELEPORT = "followTeleportDistance";

	/**
	 * 走不动了多少 tick 之后就别再硬走：路已经断了（隔着墙、隔着岩浆湖、在船上），
	 * 继续等只会一直站着。这一条比香草更进一步——香草只按距离传送，于是「近但过不去」
	 * 会永远卡住。
	 */
	private static final int FOLLOW_STUCK_TELEPORT_TICKS = 60;

	private static final int STUCK_CHECK_INTERVAL = 40;
	private static final double STUCK_DISPLACEMENT_SQ = 0.01;

	/** 主背包 36 格（方案 C1/A2：真实玩家规格）。 */
	public static final int MAIN_INVENTORY_SIZE = 36;

	/** Owner uuid synced to the client so the renderer can pick the matching skin. */
	private static final net.minecraft.entity.data.TrackedData<java.util.Optional<UUID>>
		TRACKED_OWNER = net.minecraft.entity.data.DataTracker.registerData(
			AvatarEntity.class,
			net.minecraft.entity.data.TrackedDataHandlerRegistry.OPTIONAL_UUID);

	/**
	 * 背着的那件东西，同步给客户端<b>只为了画出来</b>。
	 *
	 * <p>背包内容不走这条路（那是那个模组自己的事），这里同步的只是背包这件物品本身，
	 * 渲染层要靠它拿到模型和颜色。</p>
	 */
	private static final net.minecraft.entity.data.TrackedData<net.minecraft.item.ItemStack>
		TRACKED_BACKPACK = net.minecraft.entity.data.DataTracker.registerData(
			AvatarEntity.class,
			net.minecraft.entity.data.TrackedDataHandlerRegistry.ITEM_STACK);

	private static final net.minecraft.entity.data.TrackedData<String> TRACKED_PROFESSION =
		net.minecraft.entity.data.DataTracker.registerData(AvatarEntity.class,
			net.minecraft.entity.data.TrackedDataHandlerRegistry.STRING);

	private static final net.minecraft.entity.data.TrackedData<Integer> TRACKED_REPOSITION =
		net.minecraft.entity.data.DataTracker.registerData(AvatarEntity.class,
			net.minecraft.entity.data.TrackedDataHandlerRegistry.INTEGER);
	private final dev.squire.common.animation.LocomotionGait locomotionGait =
		new dev.squire.common.animation.LocomotionGait();

	public dev.squire.common.animation.LocomotionGait locomotionGait() { return locomotionGait; }
	public int repositionRevision() { return dataTracker.get(TRACKED_REPOSITION); }

	/** A position correction is not walking, even when shorter than a normal stride. */
	public void repositionForConstruction(double x, double y, double z) {
		if (!getWorld().isClient && squaredDistanceTo(x, y, z) > 1.0e-8)
			dataTracker.set(TRACKED_REPOSITION, repositionRevision() + 1);
		refreshPositionAndAngles(x, y, z, getYaw(), getPitch());
	}

	public boolean usesGroundGait() {
		return (getPose() == net.minecraft.entity.EntityPose.STANDING || getPose() == net.minecraft.entity.EntityPose.CROUCHING)
			&& isAlive() && isOnGround() && !hasVehicle() && !isTouchingWater() && !isInLava()
			&& !isClimbing() && !isFallFlying() && !isSleeping() && !isInSwimmingPose()
			&& getLeaningPitch(1) == 0;
	}

	/** Per-entity profession for client appearance; never inferred from the owner. */
	public String syncedProfession() {
		return this.dataTracker.get(TRACKED_PROFESSION);
	}

	private void syncProfessionToClients() {
		var current = profile();
		var profession = current == null ? null : current.profession.profession();
		this.dataTracker.set(TRACKED_PROFESSION, profession == null ? "" : profession.id());
	}

	private UUID agentId;
	private UUID ownerUuid;
	private BlockPos homePos;

	/** The avatar's own inventory is the authoritative state for proxy actions (ADR-004). */
	private final net.minecraft.inventory.SimpleInventory inventory =
		new net.minecraft.inventory.SimpleInventory(MAIN_INVENTORY_SIZE);

	/**
	 * 他背上那个背包（「精妙背包」之类可以当容器用的物品），独立于胸甲槽的一格。
	 *
	 * <p>做成独立一格而不是占用胸甲：背包和胸甲是两件都想要的东西，让它们抢一个位置
	 * 等于逼玩家在「能装」和「打得过」之间二选一。真正的容量在物品自己的 NBT 里，
	 * 这里只存那一件物品。</p>
	 */
	private final net.minecraft.inventory.SimpleInventory backpackSlot =
		new net.minecraft.inventory.SimpleInventory(1) {
			@Override
			public void markDirty() {
				super.markDirty();
				// 玩家在面板里直接把背包拖进这一格时走的是 Slot → markDirty，
				// 不经过 setBackpackStack。同步挂在这里，两条路才都能画出来。
				syncBackpackToClients();
			}
		};

	/** 唯一的物品写入口（方案 C1）：主背包 + 背包 + 手持 + 护甲的权威模型。 */
	private final AvatarInventory authoritativeInventory =
		new AvatarInventory(this, inventory, backpackSlot);

	private MovementMode mode = MovementMode.IDLE;
	private BlockPos stayPos;
	/** 维度感知的 STAY 锚点与 HOME（跨维度语义由 Store/运行时解释）。 */
	private GlobalPos stayGlobalPosField;
	private GlobalPos homeGlobalPosField;

	private AvatarMoveHandle activeHandle;
	/** 移动句柄进入终态后再保留几 tick，给执行器读一次结果的机会。 */
	private static final int HANDLE_LINGER_TICKS = 5;
	private int handleLingerTicks = HANDLE_LINGER_TICKS;

	/**
	 * 「现在有任务在跑吗」——由运行时注入，实质是
	 * {@code scheduler.current(agentId).isPresent()}。
	 *
	 * <p>移动类 Goal 在它为真时全部让位。判据用「有没有任务」而不是
	 * 「有没有移动句柄」，是因为施工这类任务是「走一段 → 放几格 → 再走」，
	 * 中间那几拍没有句柄；若按句柄判，跟随 Goal 会在那几拍把他拉走，
	 * 下一格就够不着了。</p>
	 */
	private java.util.function.BooleanSupplier busyCheck;
	/** True only while the scheduler owns an explicit heal.self task for this body. */
	private java.util.function.BooleanSupplier explicitSelfCareCheck;

	public void attachBusyCheck(java.util.function.BooleanSupplier check) {
		this.busyCheck = check;
	}

	public void attachExplicitSelfCareCheck(java.util.function.BooleanSupplier check) {
		this.explicitSelfCareCheck = check;
	}

	/**
	 * 正在打架吗。
	 *
	 * <p>跟随 Goal 必须在这时候让位，否则会出现玩家看到的那种鬼畜：
	 * {@code GuardRuntime} 每 tick 把他派去追一个 20 格外的怪（追击半径是护卫半径
	 * 的 1.5 倍，比传送门槛远得多），而跟随 Goal 一超过传送距离就立刻把他拽回来，
	 * 两边每秒抢好几次。护卫策略不是"任务"，所以 {@link #taskDriven()} 拦不住它。</p>
	 *
	 * <p>脱战后还留一小段冷却：怪刚死那一拍就把他弹回来，看起来同样突兀。</p>
	 */
	public boolean inCombat() {
        if (oathActive()) return true;
		if (getTarget() != null && getTarget().isAlive()) {
			combatGraceUntil = age + COMBAT_GRACE_TICKS;
			return true;
		}
		return age < combatGraceUntil;
	}

	/** 脱战之后还按「在打架」处理多久。 */
	private static final int COMBAT_GRACE_TICKS = 40;
	private int combatGraceUntil;

	/** 任务正在接管身体吗。为真时，所有移动 Goal 让位，但长期命令不丢。 */
	public boolean taskDriven() {
        if (oathActive()) return true;
		// 两种接管都算：调度器里有任务在跑（施工那种「走一段→放几格→再走」，
		// 中间几拍没有句柄），或者运行时直接发起了一段移动（比如回家）。
		// 只看其中一种都会漏：前者漏掉直接移动，后者漏掉施工的间隙。
		return busyCheck != null && busyCheck.getAsBoolean()
			|| activeHandle != null
				&& activeHandle.state() == MoveHandle.State.MOVING;
	}
	private Vec3d moveTarget;
	private Vec3d stuckCheckAnchor;
	private int stuckCheckAge;

	private ActivityState activity = ActivityState.IDLE;
	private int activityAge;
	private int pickupAge;
	/** 已经交给玩家的掉落物 uuid：这些东西伙伴永远不再捡回来。 */
	private final java.util.Set<UUID> handedOff = new java.util.HashSet<>();
	/** 本名（不含状态后缀），用于状态切回 IDLE 时还原名牌。 */
	private net.minecraft.text.Text baseName;

	public AvatarEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		setPersistent();
		// Equipment is snapshotted into the world-level AgentRecord on death, so vanilla
		// must NOT also roll it onto the ground — that would duplicate every tool.
		for (net.minecraft.entity.EquipmentSlot slot : AvatarInventory.EQUIPMENT_SLOTS) {
			setEquipmentDropChance(slot, 0.0f);
		}
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		this.dataTracker.startTracking(TRACKED_OWNER, java.util.Optional.empty());
		this.dataTracker.startTracking(TRACKED_BACKPACK, net.minecraft.item.ItemStack.EMPTY);
		this.dataTracker.startTracking(TRACKED_PROFESSION, "");
		this.dataTracker.startTracking(TRACKED_REPOSITION, 0);
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return createMobAttributes()
			.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
			.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.35)
			.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0)
			.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimSurfaceGoal());
		// 自救不占 Control，优先级只决定它在同一拍里被问到的顺序——它必须能在
		// 跟随/巡逻/战斗<b>同时</b>发生，所以放最前面而不是排队等空档。
		this.goalSelector.add(1, new SelfCareGoal());
		this.goalSelector.add(2, new FollowOwnerGoal());
		this.goalSelector.add(3, new StayAreaGoal());
		this.goalSelector.add(4, new PatrolGoal());
		this.goalSelector.add(5, new HomeRoutineGoal());
		this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f) {
			@Override public boolean canStart() { return idleLookAllowed() && super.canStart(); }
			@Override public boolean shouldContinue() { return idleLookAllowed() && super.shouldContinue(); }
		});
		this.goalSelector.add(8, new LookAroundGoal(this) {
			@Override public boolean canStart() { return idleLookAllowed() && super.canStart(); }
			@Override public boolean shouldContinue() { return idleLookAllowed() && super.shouldContinue(); }
		});
		// deliberately NO wander goal: the body moves only under runtime control
	}

	// ------------------------------------------------------------------ tick: stuck detection
	private boolean idleLookAllowed() { return !taskDriven() && !inCombat() && getNavigation().isIdle(); }

	@Override
	public void tick() {
        if (dev.squire.server.combat.GuardRescue.expireOath(this)) return;
        super.tick();
        if (getWorld().isClient)
            locomotionGait.tick(getX(), getY(), getZ(), usesGroundGait(), repositionRevision());
        if (oathActive()) {
            dev.squire.server.combat.GuardRescue.tickOath(this);
            tickBowDraw();
            return;
        }
		if (getWorld().isClient) {
			return;
		}
		syncProfessionToClients();
		// spec section 34: expected movement vs actual displacement while a handle runs
		if (activeHandle != null && activeHandle.state() == MoveHandle.State.MOVING
				&& ++stuckCheckAge >= STUCK_CHECK_INTERVAL) {
			if (stuckCheckAnchor != null
					&& squaredDistanceTo(stuckCheckAnchor) < STUCK_DISPLACEMENT_SQ) {
				getNavigation().stop();
				activeHandle.markFailed("AGENT_STUCK");
			} else {
				stuckCheckAnchor = getPos();
				stuckCheckAge = 0;
			}
		}
		// arrival: vanilla navigation goes idle on completion AND on giving up —
		// distinguish by proximity to the requested target
		if (activeHandle != null && activeHandle.state() == MoveHandle.State.MOVING
				&& getNavigation().isIdle()) {
			if (moveTarget == null || squaredDistanceTo(moveTarget) <= ARRIVAL_DISTANCE_SQ) {
				activeHandle.markArrivedIfMoving();
			} else {
				getNavigation().stop();
				activeHandle.markFailed("AGENT_STUCK");
			}
		}
		// 一段移动走完就把句柄放掉。以前它永远非空，于是巡逻和回家收工
		// 这两个 Goal（它们用 activeHandle == null 当前提）在任务第一次动身之后
		// 就再也启动不了了。保留它到任务自己读完状态为止：读取发生在下一 tick，
		// 所以这里延迟一拍再清。
		if (activeHandle != null && activeHandle.state() != MoveHandle.State.MOVING) {
			if (handleLingerTicks-- <= 0) {
				activeHandle = null;
				moveTarget = null;
			}
		} else {
			handleLingerTicks = HANDLE_LINGER_TICKS;
		}
		tickActivityFeedback();
		tickAmbientPickup();
		tickSwimmingPose();
		tickBowDraw();
	}

	// ------------------------------------------------------------------ 交付：丢给玩家

	/**
	 * 把一组物品扔到 {@code receiver} 脚边，作为一次看得见的交接动作。
	 *
	 * <p>关键细节：刚扔出去的掉落物必须登记进 {@link #handedOff}，否则下一次自动捡拾
	 * 扫描会立刻把它捡回来——伙伴会当着玩家的面把刚给出去的东西又捞回兜里。</p>
	 *
	 * @return 真正扔出去的掉落物实体，背包里没有该物品时返回 null
	 */
	public net.minecraft.entity.ItemEntity dropForPlayer(ItemStack stack,
			net.minecraft.entity.player.PlayerEntity receiver) {
		if (stack == null || stack.isEmpty() || !(getWorld() instanceof ServerWorld world)) {
			return null;
		}
		net.minecraft.entity.ItemEntity dropped = new net.minecraft.entity.ItemEntity(
			world, getX(), getEyeY() - 0.2, getZ(), stack);
		// 朝玩家轻轻抛过去，而不是直接掉在自己脚下
		Vec3d toward = receiver.getPos().subtract(getPos()).normalize().multiply(0.22);
		dropped.setVelocity(toward.x, 0.12, toward.z);
		dropped.setOwner(receiver.getUuid()); // 这份东西是给他的
		dropped.setPickupDelay(10);
		world.spawnEntity(dropped);
		handedOff.add(dropped.getUuid());
		world.playSound(null, getX(), getY(), getZ(),
			net.minecraft.sound.SoundEvents.ENTITY_ITEM_PICKUP,
			net.minecraft.sound.SoundCategory.NEUTRAL, 0.4f, 0.8f);
		swingHand(net.minecraft.util.Hand.MAIN_HAND);
		return dropped;
	}

	/**
	 * 这个掉落物是不是交给玩家的（交出去的东西<b>永远</b>不许再捡回来）。
	 *
	 * <p>这里刻意不设过期时间。之前用的是"保护 200 tick"，结果玩家没来得及捡，
	 * 保护一到期伙伴就当着他的面把刚送出去的东西又收回兜里——比不送还糟。
	 * 登记表的增长由 {@link #pruneHandedOff()} 按实体是否还存在来回收。</p>
	 */
	public boolean recentlyHandedOff(UUID itemEntityUuid) {
		return handedOff.contains(itemEntityUuid);
	}

	/** 掉落物被捡走或自然消失后，把登记表里对应的条目清掉，避免无限增长。 */
	private void pruneHandedOff() {
		if (handedOff.isEmpty() || !(getWorld() instanceof ServerWorld server)) {
			return;
		}
		handedOff.removeIf(uuid -> server.getEntity(uuid) == null);
	}

	// ------------------------------------------------------------------ 自动捡拾

	/**
	 * 每隔一小段时间把脚边的掉落物收进背包。玩家把东西丢在他面前时，期待的就是
	 * 他弯腰捡起来——在此之前伙伴对地上的物品完全视而不见。
	 *
	 * <p>半径特意取得比原版拾取范围大一点点但仍然很近，免得他隔着老远隔空吸物。
	 * 背包满时 {@code sweep} 会把物品留在世界里，绝不销毁。</p>
	 */
	private void tickAmbientPickup() {
		if (++pickupAge < AMBIENT_PICKUP_INTERVAL || !(getWorld() instanceof ServerWorld)) {
			return;
		}
		pickupAge = 0;
		pruneHandedOff();
		var result = dev.squire.server.task.executors.ContainerExecutors.PickupNearby
			.sweep(this, AMBIENT_PICKUP_RADIUS);
		if (result.picked() > 0 && getWorld() instanceof ServerWorld server) {
			server.playSound(null, getX(), getY(), getZ(),
				net.minecraft.sound.SoundEvents.ENTITY_ITEM_PICKUP,
				net.minecraft.sound.SoundCategory.NEUTRAL, 0.3f, 1.6f);
		}
	}

	/**
	 * PATROL 模式：绕着锚点来回走。任务接管移动（有 activeHandle）时让路，
	 * 否则运行时下发的路径会被这个 goal 每隔几秒打断一次。
	 */
	/**
	 * 回到基地、闲下来之后自己找点事做。
	 *
	 * <p><b>Goal 里不写业务</b>：它只回答「他现在闲着、而且在家门口吗」，然后提交一条
	 * 最低优先级的 {@code home.routine} 任务。真正做什么在
	 * {@code HomeRoutineExecutor} 里。这样这件伙伴自己发起的事，和玩家下的指令走
	 * 同一条管道——会被抢占、有超时、进任务日志。把逻辑写在 Goal 里的话，它就成了
	 * 一条谁都看不见、也停不掉的暗线。</p>
	 *
	 * <p>触发条件刻意苛刻：IDLE、离家 8 格内、已经闲了 {@link #IDLE_TICKS_REQUIRED}。
	 * 一个刚被叫停就自己跑去整理箱子的伙伴，比一个什么都不做的更烦人。</p>
	 */
	private final class HomeRoutineGoal extends net.minecraft.entity.ai.goal.Goal {
		/** 闲这么久才算「没事干」。 */
		private static final int IDLE_TICKS_REQUIRED = 200;
		/** 离家这么近才算「在基地」。 */
		private static final double HOME_RADIUS_SQ = 64.0;
		/** 两趟之间的冷却，免得他围着箱子来回折腾。 */
		private static final int COOLDOWN_TICKS = 1200;

		private int idleAge;
		private int cooldown;

		HomeRoutineGoal() {
			setControls(java.util.EnumSet.noneOf(Control.class)); // 不抢移动控制权
		}

		@Override
		public boolean canStart() {
			if (getWorld().isClient) {
				return false;
			}
			if (cooldown > 0) {
				cooldown--;
				return false;
			}
			var currentProfile = profile();
			boolean allowed = currentProfile != null
				&& (currentProfile.autonomyLevel().atLeast(
					dev.squire.server.profile.AutonomyLevel.PROACTIVE)
					|| currentProfile.hasTrait(dev.squire.server.profile.Trait.HOARDER));
			if (!allowed || mode != MovementMode.IDLE || taskDriven() || homePos == null) {
				idleAge = 0;
				return false;
			}
			double dx = getX() - (homePos.getX() + 0.5);
			double dy = getY() - homePos.getY();
			double dz = getZ() - (homePos.getZ() + 0.5);
			if (dx * dx + dy * dy + dz * dz > HOME_RADIUS_SQ) {
				idleAge = 0;
				return false;
			}
			return ++idleAge >= IDLE_TICKS_REQUIRED;
		}

		@Override
		public boolean shouldContinue() {
			return false; // 提交一次任务就结束，剩下的交给调度器
		}

		@Override
		public void start() {
			idleAge = 0;
			cooldown = COOLDOWN_TICKS;
			dev.squire.server.runtime.SquireRuntime.requestHomeRoutine(AvatarEntity.this);
		}
	}


	/**
	 * 巡逻。两种形态，由档案里有没有巡逻点决定。
	 *
	 * <p><b>没有点位</b>时退回原来的行为：绕着锚点随机转圈。这条向后兼容不是可选的——
	 * 在此之前所有存档里的巡逻都是这个样子，升级不该让他们的伙伴突然站着不动。</p>
	 *
	 * <p><b>有点位</b>时按顺序逐点走，到点停 {@link #DWELL_TICKS} 做一次巡检。
	 * 停这一下是必要的：走过路过地扫一眼，玩家看不出他在「巡查」，只看到他在乱走。</p>
	 */
	private final class PatrolGoal extends net.minecraft.entity.ai.goal.Goal {
		private static final double RADIUS = 8.0;
		private static final int REPATH_INTERVAL = 60;
		/** 到点之后驻留多久（tick）。 */
		private static final int DWELL_TICKS = 40;
		/** 走到多近算「到了这个点」。 */
		private static final double ARRIVE_SQ = 4.0;

		private int cooldown;
		private int dwell;

		PatrolGoal() {
			setControls(java.util.EnumSet.of(Control.MOVE));
		}

		@Override
		public boolean canStart() {
			return mode == MovementMode.PATROL && stayPos != null && !taskDriven();
		}

		@Override
		public boolean shouldContinue() {
			return canStart();
		}

		@Override
		public void tick() {
			BlockPos waypoint = nextWaypoint();
			if (waypoint == null) {
				wander();
				return;
			}
			double dx = getX() - (waypoint.getX() + 0.5);
			double dz = getZ() - (waypoint.getZ() + 0.5);
			if (dx * dx + dz * dz > ARRIVE_SQ) {
				if (!getNavigation().isFollowingPath() && --cooldown <= 0) {
					cooldown = REPATH_INTERVAL;
					getNavigation().startMovingTo(waypoint.getX() + 0.5,
						waypoint.getY(), waypoint.getZ() + 0.5, 1.0);
				}
				return;
			}
			// 到点了：停下来看一圈，然后走向下一个点。
			getNavigation().stop();
			if (++dwell < DWELL_TICKS) {
				return;
			}
			dwell = 0;
			cooldown = 0;
			dev.squire.server.runtime.SquireRuntime.onPatrolCheckpoint(AvatarEntity.this);
			advanceCursor();
		}

		/** 当前该走向的巡逻点；没有配置点位时返回 null。 */
		private BlockPos nextWaypoint() {
			var profile = profile();
			if (profile == null || profile.patrolPoints.isEmpty()
					|| !(getWorld() instanceof ServerWorld world)) {
				return null;
			}
			var here = world.getRegistryKey();
			java.util.List<GlobalPos> usable = profile.patrolPoints.stream()
				.filter(point -> point.getDimension().equals(here))
				.toList();
			if (usable.isEmpty()) {
				return null; // 点位都在别的维度：退回转圈，而不是站着不动
			}
			int cursor = Math.floorMod(profile.patrolCursor, usable.size());
			return usable.get(cursor).getPos();
		}

		private void advanceCursor() {
			var profile = profile();
			if (profile != null && !profile.patrolPoints.isEmpty()) {
				profile.patrolCursor = Math.floorMod(profile.patrolCursor + 1,
					profile.patrolPoints.size());
			}
		}

		/** 老行为：绕着锚点随机转圈。 */
		private void wander() {
			if (--cooldown > 0 || !getNavigation().isIdle()) {
				return;
			}
			cooldown = REPATH_INTERVAL;
			double angle = getRandom().nextDouble() * Math.PI * 2.0;
			double distance = getRandom().nextDouble() * RADIUS;
			double x = stayPos.getX() + 0.5 + Math.cos(angle) * distance;
			double z = stayPos.getZ() + 0.5 + Math.sin(angle) * distance;
			getNavigation().startMovingTo(x, stayPos.getY(), z, 0.9);
		}

		@Override
		public void stop() {
			cooldown = 0;
			dwell = 0;
			// 任务接管移动时不要顺手掐掉它的路径（和 FollowOwnerGoal 同一个坑）。
			if (!taskDriven()) {
				getNavigation().stop();
			}
		}
	}

	// ------------------------------------------------------------------ 状态可见化

	/**
	 * 当前忙碌状态。切换时会立刻更新名牌并放一次提示音；粒子由
	 * {@link #tickActivityFeedback()} 持续补发。
	 */
	public void setActivity(ActivityState next) {
		if (next == null || next == activity) {
			return;
		}
		activity = next;
		activityAge = 0;
		applyActivityName();
		playActivitySound(next);
	}

	public ActivityState activity() {
		return activity;
	}

	/** 记住本名，之后状态后缀都拼在它后面。 */
	/**
	 * 直接挪到某个实体旁边的一格安全落点。
	 *
	 * <p>和跟随 Goal 里的落点搜索是同一套判据，但这条是<b>命令式</b>的：玩家说
	 * 「过来」或者刚被一起传送过来时用它，不必等跟随距离触发。</p>
	 *
	 * @return 真的挪过去了才返回 true
	 */
	public boolean teleportNextTo(net.minecraft.entity.Entity anchor) {
		if (anchor == null || anchor.getWorld() != getWorld()) {
			return false;
		}
		BlockPos base = anchor.getBlockPos();
		// 锚点自己泡在水里时，周围能落的只有水格——不放行就一格都找不到。
		boolean allowWater = anchor.isTouchingWater();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos candidate = base.add(dx, dy, dz);
					if (isSafeStandingCell(candidate, allowWater)) {
						teleport(candidate.getX() + 0.5, candidate.getY(),
							candidate.getZ() + 0.5);
						getNavigation().stop();
						return true;
					}
				}
			}
		}
		// 一格合适的都找不到就留在原地。以前这里无条件和主人重叠；主人创造飞行、
		// 鞘翅滑翔或正在坠落时，那个坐标本身就在半空，侍从会被传上天再摔下来。
		// 等主人落到有安全落脚点的位置，下一次跟随检查自然会成功。
		return false;
	}

	/**
	 * 落点判据。抽出来给跟随 Goal 和「过来」共用，免得两处慢慢长歪。
	 *
	 * <p>水格<b>永远不是</b> {@code WALKABLE}。主人在水里游泳时，7×3×7 的候选格
	 * 会一格不剩地全部失败，最后落到无条件的「传送到主人坐标上」——而陆地寻路
	 * 仍然到不了，{@code idleTicks} 立刻重新累积，于是变成每 tick 传送一次的抖动。
	 * 主人本人就泡在水里时，水格显然是可以落的。</p>
	 */
	private boolean isSafeStandingCell(BlockPos pos, boolean allowWater) {
		var nodeType = net.minecraft.entity.ai.pathing.LandPathNodeMaker.getLandNodeType(
			getWorld(), pos.mutableCopy());
		boolean swimmable = allowWater
			&& nodeType == net.minecraft.entity.ai.pathing.PathNodeType.WATER;
		if (nodeType != net.minecraft.entity.ai.pathing.PathNodeType.WALKABLE
				&& !swimmable) {
			return false;
		}
		if (getWorld().getBlockState(pos.down())
				.getBlock() instanceof net.minecraft.block.LeavesBlock) {
			return false;
		}
		Vec3d delta = Vec3d.ofBottomCenter(pos).subtract(getPos());
		return getWorld().isSpaceEmpty(this,
			getBoundingBox().offset(delta));
	}

	/** A pathfinding-compatible dry standing cell for short-lived work stations. */
	public boolean isSafeWorkPosition(BlockPos pos) {
		if (pos != null && (dev.squire.server.blueprint.ConstructionScaffolding.scaffold(getWorld(), pos.down())
				|| dev.squire.server.blueprint.ConstructionScaffolding.scaffold(getWorld(), pos))
				&& dev.squire.server.blueprint.ConstructionScaffolding.floor(getWorld(), pos.down())
				&& dev.squire.server.blueprint.ConstructionScaffolding.passable(getWorld(), pos)
				&& dev.squire.server.blueprint.ConstructionScaffolding.passable(getWorld(), pos.up()))
			return new dev.squire.server.blueprint.ConstructionBlockView(getWorld(), java.util.Map.of()).safeStanding(pos, false);
		return pos != null && isSafeStandingCell(pos, false);
	}

	/**
	 * 玩家指定的打法偏好。
	 *
	 * <p>默认 AUTO（远了射、近了砍）。但玩家明确说「用弓打」时，这个意图必须能留住
	 * ——否则下一次战斗他又自己换回剑，看起来就是「不听话」。</p>
	 */
	private dev.squire.server.combat.CombatStyle.Style combatStyle =
		dev.squire.server.combat.CombatStyle.Style.AUTO;

	private static final String NBT_COMBAT_STYLE = "combatStyle";

	/**
	 * 主手那件武器是<b>玩家亲手</b>放的吗。
	 *
	 * <p>这个标志是「手里拿的说了算」和「AUTO 按怪种换武器」之间的分界线：玩家插的
	 * 武器谁都不许动；系统自己挑的，系统当然可以再改主意。没有这个区分，两条需求
	 * 必然打架——要么他不听话，要么他永远不会换武器。</p>
	 *
	 * <p>不落盘：换一次身体（死亡、跨维度）之后主手本来就要重新装备，把「这是人放的」
	 * 这条记忆带过去只会让它和实际情况对不上。</p>
	 */
	private boolean weaponChosenByPlayer;

	public boolean weaponChosenByPlayer() {
		return weaponChosenByPlayer;
	}

	/** 玩家亲手换了主手武器（面板拖拽，或者「用弓打」这类明确命令）。 */
	public void markWeaponChosenByPlayer() {
		dev.squire.server.combat.GuardSelfDefense.reset(this);
		this.weaponChosenByPlayer = true;
	}

	/** 交回自动判断（「你自己看着办」，或者手上已经空了）。 */
	public void releaseWeaponChoice() {
		this.weaponChosenByPlayer = false;
	}

	public dev.squire.server.combat.CombatStyle.Style combatStyle() {
		return combatStyle;
	}

	public void setCombatStyle(dev.squire.server.combat.CombatStyle.Style style) {
		this.combatStyle = style == null
			? dev.squire.server.combat.CombatStyle.Style.AUTO : style;
	}

	public int followTeleportDistance() {
		return followTeleportDistance;
	}

	/** 越界的值会被夹到 [6, 64]，返回真正生效的那个数。 */
	public int setFollowTeleportDistance(int blocks) {
		this.followTeleportDistance = Math.max(FOLLOW_TELEPORT_MIN,
			Math.min(FOLLOW_TELEPORT_MAX, blocks));
		return this.followTeleportDistance;
	}

	private double followTeleportDistanceSq() {
		return (double) followTeleportDistance * followTeleportDistance;
	}

	public void setBaseName(net.minecraft.text.Text name) {
		this.baseName = name;
		applyActivityName();
	}

	/** 模式变化后刷新名牌，让"待命/巡逻"这类状态在世界里直接看得见。 */
	public void refreshModeNameplate() {
		applyActivityName();
	}

	/** 名牌上的一句进度（如「施工 3/6」）；null 表示不显示。 */
	private String activityDetail;

	/**
	 * 把一句进度挂到名牌上。
	 *
	 * <p>只在<b>阶段边界</b>改，不逐 tick 刷：名牌是最廉价的进度展示，
	 * 但一个每秒变一次的名牌只会让人眼花。</p>
	 */
	public void setActivityDetail(String detail) {
		this.activityDetail = detail;
		applyActivityName();
	}

	/**
	 * 从一个<b>已经装饰过</b>的名牌里还原本名。
	 *
	 * <p>这是「他名牌上同时挂着两个状态」的病根：名牌是
	 * {@code 本名 + [模式] + [进度] + 活动后缀} 拼出来的，而它会被原版当成 CustomName
	 * 存进实体 NBT；重新加载出来的实例 {@code baseName} 是空的，于是把
	 * <em>已经带着 [跟随] 的那一串</em>当成本名，下一次切模式就变成
	 * 「阿福 [跟随] [巡逻]」，再重启一次还能叠第三个。</p>
	 *
	 * <p>现在本名单独存 NBT，这条只在读老存档时兜底：从尾巴往回剥，
	 * 先剥活动后缀，再把结尾连续的 {@code " [...]"} 全部剥掉。</p>
	 */
	private static String stripNameDecorations(String shown) {
		String text = shown;
		boolean changed = true;
		while (changed) {
			changed = false;
			for (ActivityState state : ActivityState.values()) {
				String suffix = state.nameSuffix();
				if (!suffix.isEmpty() && text.endsWith(suffix)) {
					text = text.substring(0, text.length() - suffix.length());
					changed = true;
				}
			}
			if (text.endsWith("]")) {
				int open = text.lastIndexOf(" [");
				if (open > 0) { // >0：名字开头的「[Squire] 」不是装饰，不许剥
					text = text.substring(0, open);
					changed = true;
				}
			}
		}
		return text;
	}

	/**
	 * 名牌上的职业标签，例如 {@code " [守卫 Lv6]"}。
	 *
	 * <p>没有职业的随从<b>什么都不加</b>：名牌是最贵的一块展示空间，一个写着
	 * 「无职业」的标签对玩家没有任何用处，只会把真正在变的状态挤出视野。</p>
	 *
	 * <p>经验条不上名牌——它每杀一只怪就动一次，而名牌只该显示<b>阶段性</b>的事实。
	 * 想看进度就开面板。可以晋升了是个例外：那是一件需要玩家动手的事，
	 * 所以它值得一个 ★。</p>
	 */
	private String professionTag() {
		var currentProfile = profile();
		if (currentProfile == null || !currentProfile.profession.hasProfession()) {
			return "";
		}
		var data = currentProfile.profession;
		String ready = data.xpFull(
				dev.squire.server.runtime.SquireRuntime.professionBalance())
			? " ★" : "";
		return " [" + data.profession().displayName() + " Lv" + data.level + ready + "]";
	}

	private void applyActivityName() {
		if (baseName == null && getCustomName() != null) {
			baseName = net.minecraft.text.Text.literal(
				stripNameDecorations(getCustomName().getString()));
		}
		if (baseName == null) {
			return;
		}
		// 名牌同时带上移动模式：玩家反馈"设完状态看不出有没有生效"，
		// 而他本来就经常站着不动，光看动作分辨不出待命和空闲。
		String modeTag = switch (mode) {
			case STAY -> " [待命]";
			case PATROL -> " [巡逻]";
			case FOLLOW -> " [跟随]";
			case IDLE -> "";
		};
		String detail = activityDetail == null || activityDetail.isBlank()
			? "" : " [" + activityDetail + "]";
		setCustomName(net.minecraft.text.Text.literal(
			baseName.getString() + professionTag() + modeTag + detail
				+ activity.nameSuffix()));
		// 忙碌或非空闲模式时名牌常亮，其余恢复"看着才显示"。
		setCustomNameVisible(activity != ActivityState.IDLE || mode != MovementMode.IDLE);
	}

	private void tickActivityFeedback() {
		if (activity == ActivityState.IDLE) {
			return;
		}
		activityAge++;
		// FAILED 是瞬时提示，别让它一直挂在头上。
		if (activity == ActivityState.FAILED && activityAge >= FAILED_STATE_TICKS) {
			setActivity(ActivityState.IDLE);
			return;
		}
		if (activityAge % ACTIVITY_PARTICLE_INTERVAL != 0
				|| !(getWorld() instanceof ServerWorld server)) {
			return;
		}
		server.spawnParticles(activity.particle(),
			getX(), getEyeY() + 0.6, getZ(), 4, 0.22, 0.12, 0.22, 0.02);
	}

	private void playActivitySound(ActivityState state) {
		if (!(getWorld() instanceof ServerWorld server)) {
			return;
		}
		net.minecraft.sound.SoundEvent sound = switch (state) {
			case THINKING -> net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
			case WORKING -> net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_WORK_TOOLSMITH;
			case FAILED -> net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO;
			case IDLE -> null;
		};
		if (sound == null) {
			return;
		}
		server.playSound(null, getX(), getY(), getZ(), sound,
			net.minecraft.sound.SoundCategory.NEUTRAL, 0.5f, 1.0f);
	}

	/**
	 * 右键侍从打开操作面板（背包/装备/状态/权限/对话）。
	 *
	 * <p>只有主人和管理员能打开：面板里能改权限、能搬他身上的东西，路人不该碰得到。</p>
	 */
	@Override
	public net.minecraft.util.ActionResult interactMob(PlayerEntity player,
			net.minecraft.util.Hand hand) {
		if (getWorld().isClient) {
			return net.minecraft.util.ActionResult.SUCCESS;
		}
		if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity server)) {
			return net.minecraft.util.ActionResult.PASS;
		}
		boolean owner = ownerUuid != null && ownerUuid.equals(player.getUuid());
		if (!owner && !player.hasPermissionLevel(2)) {
			player.sendMessage(net.minecraft.text.Text.literal(
				"[Squire] 这不是你的侍从。"), false);
			return net.minecraft.util.ActionResult.FAIL;
		}
		// 拿着命名牌右键 = 改名，不是开面板。以前这一句无条件开面板，于是命名牌
		// 完全用不了——玩家拿着它戳半天，只会一次次弹出同一个界面。
		net.minecraft.item.ItemStack held = player.getStackInHand(hand);
		if (held.isOf(dev.squire.server.registry.SquireItems.RECALL_BELL)
				&& (!held.hasNbt() || !held.getNbt().containsUuid(
					dev.squire.server.registry.SquireItems.NBT_AGENT))) {
			var result = dev.squire.server.runtime.SquireRuntime.get()
				.bindRecallBell(server, this, held);
			player.sendMessage(net.minecraft.text.Text.literal(result.message()), false);
			return result.success() ? net.minecraft.util.ActionResult.CONSUME
				: net.minecraft.util.ActionResult.FAIL;
		}
		if (held.isOf(net.minecraft.item.Items.NAME_TAG) && held.hasCustomName()) {
			var runtime = dev.squire.server.runtime.SquireRuntime.get();
			var result = runtime.renameAgent(server, this,
				held.getName().getString());
			player.sendMessage(net.minecraft.text.Text.literal(result.message()), false);
			if (result.success() && !player.getAbilities().creativeMode) {
				held.decrement(1);
			}
			return net.minecraft.util.ActionResult.CONSUME;
		}
		dev.squire.server.registry.SquireScreens.open(server, this);
		return net.minecraft.util.ActionResult.CONSUME;
	}

	@Override
	public void onDeath(net.minecraft.entity.damage.DamageSource damageSource) {
		// Capture the authoritative inventory/equipment before vanilla removal. The
		// world-level record lets the same companion identity be summoned again.
		endOath();
        dev.squire.server.runtime.SquireRuntime.onAvatarDeath(this);
		super.onDeath(damageSource);
	}

	/**
	 * 战斗熟练度的信号源（第 2 期）。
	 *
	 * <p>只计<b>敌对生物</b>：刷怪塔里的牛羊不是威胁，杀它们不该算成作战经验。
	 * 真正拦住刷怪塔的是另外两道闸：每天 40 杀封顶（{@code Track.COMBAT}），
	 * 而且只在主人在线时计入。</p>
	 *
	 * <p>职业系统（守卫经验）也挂在这里，但它<b>要知道杀的是什么</b>——分档、Boss
	 * 衰减、保护主人加成全都取决于对面是谁，所以被杀的那一只必须一路传过去。</p>
	 */
	@Override
	public boolean onKilledOther(ServerWorld world, net.minecraft.entity.LivingEntity other) {
		boolean result = super.onKilledOther(world, other);
		if (other instanceof net.minecraft.entity.mob.Monster) {
			dev.squire.server.runtime.SquireRuntime.onAvatarKill(this, other);
		}
		return result;
	}

	/** Damage telemetry stays exact; personality no longer inserts a damage multiplier. */
	@Override
	public boolean damage(net.minecraft.entity.damage.DamageSource source, float amount) {
        if (oathActive() && !source.isIn(net.minecraft.registry.tag.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
		float poolBefore = getHealth() + getAbsorptionAmount();
		Vec3d hitPosition = getPos();
        boolean accepted = super.damage(source, amount);
        if (accepted && source.getAttacker() instanceof net.minecraft.entity.LivingEntity attacker
                && !isOwner(attacker) && !isTeammate(attacker)) selfDefenceOrigin = hitPosition;
		float actual = Math.max(0f,
			poolBefore - getHealth() - getAbsorptionAmount());
		dev.squire.server.combat.CombatBalanceTelemetry.recordDamageTaken(
			agentId(), actual);
		return accepted;
	}

	/** Exact post-mitigation healing counter for opt-in balance measurements. */
	@Override
	public void heal(float amount) {
		float before = getHealth();
		super.heal(amount);
		dev.squire.server.combat.CombatBalanceTelemetry.recordHealing(
			agentId(), Math.max(0f, getHealth() - before));
	}

	/** Vanilla calls this exactly once for every successful shield block. */
	@Override
	public void damageShield(float amount) {
		dev.squire.server.combat.CombatBalanceTelemetry.recordShieldBlock(agentId());
		super.damageShield(amount);
	}

	/** 「轻捷」的移动速度系数。 */
	private static final double SWIFT_SPEED_FACTOR =
		dev.squire.server.profile.Trait.SWIFT_SPEED_FACTOR;
	/** 基础移动速度，与 {@link #createAttributes} 保持一致。 */
	private static final double BASE_MOVEMENT_SPEED = 0.35;

	/**
	 * 由运行时注入的档案读取口。返回 {@code null} 表示这只随从没有档案
	 * （最小/测试运行时），此时每一处都必须退回「基础行为」，而不是一律放行。
	 *
	 * <p>用 supplier 而不是把字段抄一份到实体上：玩家在面板上装一项能力之后，
	 * 下一 tick 的 Goal 就该按新档案跑，抄一份意味着还要再想「什么时候同步」。</p>
	 */
	private java.util.function.Supplier<dev.squire.server.profile.SquireProfile>
		profileSupplier;

	public void attachProfile(
			java.util.function.Supplier<dev.squire.server.profile.SquireProfile> supplier) {
		this.profileSupplier = supplier;
		// 名牌上要写职业和等级，而那两样只有档案接上来之后才读得到。刷新放在这里
		// 而不是放在每个调用点上：召唤、跨维度、区块重新加载各走一条路径，
		// 漏掉任何一条的表现都是「他头顶的等级有时候不见了」。
		refreshNameplate();
	}

	/** 重新拼一次名牌。职业等级变了、档案刚接上来时调用。 */
	public void refreshNameplate() {
		if (!getWorld().isClient) syncProfessionToClients();
		applyActivityName();
	}

	/** {@code null} 表示读不到档案。 */
	public dev.squire.server.profile.SquireProfile profile() {
		return profileSupplier == null ? null : profileSupplier.get();
	}

	private boolean can(dev.squire.server.profile.Ability ability) {
		var profile = profile();
		return profile == null ? ability.basic() && ability.available()
			: profile.can(ability);
	}

	/**
	 * 待命区域的锚点。<b>刻意和 {@link #mode} 分开存</b>：任务接管移动时会把 mode 改成
	 * IDLE（见 {@link #moveTo}），如果闸门只看 mode，那么一个多段移动的任务只有第一段
	 * 会被拦住，后面几段照样能把伙伴带出待命区。区域一直有效，直到玩家自己换一个模式。
	 */
	/** 待命区域拦下一次移动时的错误码。玩家看到的就是这一条。 */
	public static final String OUT_OF_STAY_AREA = "OUT_OF_STAY_AREA";

	private GlobalPos stayArea;

	/** 待命锚点容差。当前为 0；区域走动只属于 PATROL。 */
	private int stayRadius() {
		var profile = profile();
		return profile == null
			? dev.squire.server.profile.SquireProfile.DEFAULT_STAY_RADIUS
			: profile.stayRadius;
	}

	/**
	 * 玩家刚下了一个要跑到 {@code target} 去干的活；如果那儿在待命圈外，就放弃待命。
	 *
	 * <p><b>最近一次命令优先。</b>一个先说「待命」、再说「去那边盖个前哨站」的玩家，
	 * 要的显然是后一句。以前这里会把任务拦下来报 {@code OUT_OF_STAY_AREA}，
	 * 结果是玩家看到一个“接了活却站着不动”的伙伴。圈内的活不受影响：
	 * 待命仍然拦得住他<b>自己</b>跑出去（回家收工、巡检之类）。</p>
	 *
	 * @return true 表示真的放弃了待命，调用方该把这件事告诉玩家
	 */
	public boolean releaseStayFor(BlockPos target) {
		if (mode != MovementMode.STAY || stayArea == null || target == null) {
			return false;
		}
		BlockPos anchor = stayArea.getPos();
		double radius = stayRadius();
		double dx = target.getX() - anchor.getX();
		double dy = target.getY() - anchor.getY();
		double dz = target.getZ() - anchor.getZ();
		if (dx * dx + dy * dy + dz * dz <= radius * radius) {
			return false; // 圈内的活，待命和干活不矛盾
		}
		setIdleMode();
		return true;
	}

	/** 当前待命区域（若在待命中且锚点在本维度）。 */
	public Optional<GlobalPos> stayArea() {
		return Optional.ofNullable(stayArea);
	}


	/** Stable id: repeated loads/rerolls replace this modifier instead of stacking it. */
	private static final UUID STURDY_HEALTH_MODIFIER_ID = UUID.fromString(
		"97d0be77-ea1d-4bda-b7a8-40c503fe9215");

	/**
	 * 把性格特质落到实体属性上。召唤与实体加载时各调一次。
	 *
	 * <p>写成一个幂等的 setter 而不是“加一个修饰符”：重复调用不会叠加，
	 * 也就不会出现“重启几次之后他跑得比马快”这种 bug。</p>
	 */
	public void applyTraits(java.util.Collection<String> traitIds) {
		boolean swift = traitIds != null
			&& traitIds.contains(dev.squire.server.profile.Trait.SWIFT.id());
		boolean sturdy = traitIds != null
			&& traitIds.contains(dev.squire.server.profile.Trait.STURDY.id());
		var speed = getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
		if (speed != null) {
			speed.setBaseValue(swift ? BASE_MOVEMENT_SPEED * SWIFT_SPEED_FACTOR
				: BASE_MOVEMENT_SPEED);
		}
		var health = getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
		if (health != null) {
			float healthBefore = getHealth();
			float maxBefore = getMaxHealth();
			boolean wasFull = healthBefore >= maxBefore - 0.001f;
			health.removeModifier(STURDY_HEALTH_MODIFIER_ID);
			if (sturdy) {
				health.addPersistentModifier(new EntityAttributeModifier(
					STURDY_HEALTH_MODIFIER_ID, "Squire sturdy max health",
					dev.squire.server.profile.Trait.STURDY_MAX_HEALTH_BONUS,
					EntityAttributeModifier.Operation.ADDITION));
			}
			setHealth(wasFull ? getMaxHealth() : Math.min(healthBefore, getMaxHealth()));
		}
	}

	private static final double ARRIVAL_DISTANCE_SQ = 2.25;

	// ------------------------------------------------------------------ AgentBody

	@Override
	public UUID agentId() {
		if (agentId == null) {
			agentId = UUID.randomUUID();
		}
		return agentId;
	}

	/**
	 * 绑定 Store 中的永久 agentId（方案 A1：同一伙伴的每个实体实例共用一个 id）。
	 * 仅在尚未物化 id 时生效，避免覆盖已持久化的身份。
	 */
	public void assignAgentId(UUID fixed) {
		if (this.agentId == null && fixed != null) {
			this.agentId = fixed;
		}
	}

	@Override
	public UUID ownerId() {
		return ownerUuid == null ? null : ownerUuid;
	}

	@Override
	public BodyCapabilities capabilities() {
		return BodyCapabilities.avatarLike();
	}

	@Override
	public AgentPhysicalState snapshotState() {
		return new AgentPhysicalState(agentId(), getX(), getY(), getZ(),
			getWorld().getRegistryKey().getValue().toString(), getYaw(), getPitch(),
			getHealth(), getMaxHealth(), isAlive(), getFireTicks(), isInSwimmingPose());
	}

	@Override
	public MoveHandle moveTo(TargetPosition target, MoveOptions options) {
        if (oathActive()) {
            AvatarMoveHandle refused = new AvatarMoveHandle();
            refused.markFailed("OATH_STATIONARY");
            return refused;
        }
		if (!target.dimension().equals(getWorld().getRegistryKey().getValue().toString())) {
			AvatarMoveHandle failed = new AvatarMoveHandle();
			failed.markFailed("UNREACHABLE");
			return failed; // cross-dimension travel is out of M1 scope
		}
		// 待命闸门：待命时他可以继续干活，但不能离开区域。
		// 闸门下在这里而不是 StayAreaGoal 里——Goal 只管移动，任务会直接把它拖走；
		// 只有在这里失败，TaskScheduler 才会如实失败、玩家才知道为什么。
		if (stayArea != null && getWorld() instanceof ServerWorld stayWorld
				&& stayWorld.getRegistryKey().equals(stayArea.getDimension())) {
			BlockPos anchor = stayArea.getPos();
			double radius = stayRadius();
			double dx = target.x() - (anchor.getX() + 0.5);
			double dy = target.y() - anchor.getY();
			double dz = target.z() - (anchor.getZ() + 0.5);
			if (dx * dx + dy * dy + dz * dz > radius * radius) {
				AvatarMoveHandle refused = new AvatarMoveHandle();
				refused.markFailed(OUT_OF_STAY_AREA);
				return refused;
			}
		}
		// 以前这里是 {@code mode = IDLE}。那是一个静默的状态覆盖：任务随便动一下身体，
		// 玩家让他「跟随」或「巡逻」的命令就永久消失了，而且再也不会回来。
		// 现在模式是玩家的<b>长期命令</b>，任务只是在它运行期间接管身体（见
		// {@link #taskDriven()}）；任务一结束，长期命令自己就接上了。
		boolean started = getNavigation().startMovingTo(target.x(), target.y(), target.z(),
			options.speed());
		AvatarMoveHandle handle = new AvatarMoveHandle();
		if (!started) {
			handle.markFailed("PATH_NOT_FOUND");
			return handle;
		}
		activeHandle = handle;
		moveTarget = new Vec3d(target.x(), target.y(), target.z());
		stuckCheckAnchor = getPos();
		stuckCheckAge = 0;
		return handle;
	}

	@Override
	public void stopMoving() {
		setSneaking(false);
		getNavigation().stop();
		if (activeHandle != null && activeHandle.state() == MoveHandle.State.MOVING) {
			activeHandle.markFailed("CANCELLED");
		}
		moveTarget = null;
		stuckCheckAnchor = null;
		stuckCheckAge = 0;
	}

	/** 最近一次 moveTo 失败的错误码（PATH_NOT_FOUND / UNREACHABLE / AGENT_STUCK）。 */
	public String lastMoveErrorCode() {
		return activeHandle == null ? null : activeHandle.errorCode();
	}

	@Override
	public InventoryView inventory() {
		return new AvatarInventoryView();
	}

	@Override
	public void lookAt(TargetPosition target) {
		getLookControl().lookAt(target.x(), target.y(), target.z());
	}

	@Override
	public void emote(EmoteType type) {
		switch (type) {
			case WAVE -> swingHand(net.minecraft.util.Hand.MAIN_HAND);
			case JUMP -> getJumpControl().setActive();
			case NOD, SHAKE_HEAD -> {
				// head animation without a player model hook: small pitch bob
				setPitch(type == EmoteType.NOD ? 35.0f : -20.0f);
			}
		}
	}

	@Override
	public boolean alive() {
		return isAlive();
	}

	/**
	 * 血量 + 吸收。吸收必须算进来，否则打掉一整层金苹果护盾会被记成「没造成伤害」。
	 * 非生物返回 0，差值因此恒为 0。
	 */
	private static double healthPoolOf(net.minecraft.entity.Entity entity) {
		return entity instanceof net.minecraft.entity.LivingEntity living
			? living.getHealth() + living.getAbsorptionAmount() : 0.0;
	}

	/**
	 * 这是我的主人吗。
	 *
	 * <p>存在的唯一理由是下面那条不变量。它<b>不</b>看模式、不看权限、不看职业——
	 * 任何条件都可能在某个分支里为假，而这件事没有例外。</p>
	 */
	public boolean isOwner(net.minecraft.entity.Entity target) {
		return target != null && ownerUuid != null
			&& ownerUuid.equals(target.getUuid());
	}

	/**
	 * <b>侍从永远不会攻击自己的主人。</b>
	 *
	 * <p>这条不变量放在 {@link #attack} 和 {@link #shoot} 里，而不是放在各个决策层，
	 * 因为决策层有四条独立的路径会挑目标（长期护卫、自主反射、攻击任务、护卫任务），
	 * 任何一条漏掉一次判断，代价都是玩家被自己的伙伴打死——而这正好发生过：
	 * 玩家误伤了侍从一下，香草的 {@code damage()} 把主人写进了 {@code getAttacker()}，
	 * 自主反射把它当成「正在打我的人」还了手，然后每 tick 还一次，直到玩家死。</p>
	 *
	 * <p>所以判断只能落在<b>所有路径的唯一出口</b>上。顺手把锁定和寻路一起清掉，
	 * 免得他一边不打你一边追着你跑。</p>
	 */
	private InteractionResult refuseToHitOwner() {
		cancelDraw();
		setTarget(null);
		combatPathTargetId = null;
		return InteractionResult.fail("OWNER_IMMUNE",
			"a squire never attacks its owner");
	}

	/**
	 * 锁定目标。主人一律锁不上——被自己的伙伴瞄着，本身就是一个不该出现的状态。
	 */
	@Override
	public void setTarget(net.minecraft.entity.LivingEntity target) {
		super.setTarget(isOwner(target) ? null : target);
	}

	/** Melee reach in blocks, squared (player-like 3-block reach). */
	public static final double MELEE_REACH_SQ = 9.0;
	/**
	 * 近战追击的重寻路间隔。香草近战 Goal 也不会每 tick 重新建路；那样会让
	 * MoveControl 不断收到一条“新路径”，表现成一步一顿。
	 */
	private static final int COMBAT_REPATH_TICKS = 8;
	private UUID combatPathTargetId;
	private Vec3d combatPathTargetPos;
	private int nextCombatRepathTick;

	/**
	 * Combat primitive for the runtime's combat executors (spec section 38): closes
	 * distance when out of reach, swings once when in reach. Vanilla attack cooldowns
	 * apply; callers simply re-issue on the next tick.
	 */
	@Override
	public dev.squire.api.body.InteractionResult attack(UUID targetEntityId) {
		if (!(getWorld() instanceof ServerWorld serverWorld)) {
			return InteractionResult.fail("SERVER_ONLY", "attacks resolve server-side");
		}
		net.minecraft.entity.Entity target = serverWorld.getEntity(targetEntityId);
		if (target == null || !target.isAlive()) {
			return InteractionResult.fail("ENTITY_NOT_FOUND", "target gone or dead");
		}
		if (isOwner(target)) {
			return refuseToHitOwner();
		}
		// 拉着弓砍人是不可能的。这一句同时兜住了 GuardTaskExecutor 那条只走近战的路
		// ——它从不调用 shoot()，所以放下弓的责任只能落在这里。
		cancelDraw();
		getLookControl().lookAt(target);
		if (squaredDistanceTo(target) > MELEE_REACH_SQ) {
            if (oathActive()) return InteractionResult.fail("OATH_STATIONARY", "Standing at the rescue point");
			boolean newTarget = !targetEntityId.equals(combatPathTargetId);
			boolean targetMoved = combatPathTargetPos == null
				|| target.squaredDistanceTo(combatPathTargetPos) > 2.25;
			if (newTarget || targetMoved || getNavigation().isIdle()
					|| age >= nextCombatRepathTick) {
				getNavigation().startMovingTo(target, 1.2);
				combatPathTargetId = targetEntityId;
				combatPathTargetPos = target.getPos();
				nextCombatRepathTick = age + COMBAT_REPATH_TICKS;
			}
			return new InteractionResult(true, null, "closing to melee range");
		}
		// 已经够得着就站稳挥砍。继续沿旧路径顶进目标碰撞箱，也会看起来一顿一顿。
		getNavigation().stop();
		// 挥砍前后的血量差就是这一下真的打掉了多少——护甲、附魔、抗性全都算进去了。
		// 职业系统用它判断「他到底参没参与这场战斗」（设计文档 §10.1 的 20% 那一条）。
		double before = healthPoolOf(target);
		boolean hit = tryAttack(target);
		if (hit && target instanceof net.minecraft.entity.LivingEntity living) {
			dev.squire.server.runtime.SquireRuntime.onAvatarDealtDamage(this, living,
				before - healthPoolOf(target));
		}
		return hit ? InteractionResult.ok()
			: InteractionResult.fail("ATTACK_ON_COOLDOWN", "swing not ready yet");
	}

	/**
	 * 拉满一次弓的 tick 数。
	 *
	 * <p>必须是 20：客户端弓的 {@code pull} 模型谓词按
	 * {@code (72000 - itemUseTimeLeft) / 20} 计算弯曲程度，20 tick 正好到 1.0。
	 * 服务端的 {@code BowItem.getPullProgress} 用的是同一条曲线，于是"看起来拉满了"
	 * 和"真的拉满了"天然同步，不需要额外对齐。</p>
	 */
	private static final int BOW_DRAW_TICKS = 20;
	/** 一整个射击周期：拉弓 + 收弓。保持 30，手感和以前一致，只是现在看得见。 */
	private static final int SHOT_CYCLE_TICKS = 30;
	/** 放箭之后的收弓时间。 */
	private static final int SHOT_RECOVERY_TICKS = SHOT_CYCLE_TICKS - BOW_DRAW_TICKS;
	/**
	 * 驱动方连续这么多 tick 不再要求射击，就把弓放下。
	 *
	 * <p>这里没有战斗 Goal：拉弓是 GuardRuntime / AttackTargetExecutor 每 tick 从外面
	 * 推进来的，而它们在目标死掉或跑远的<b>那一拍</b>就直接不再调用 {@link #shoot}，
	 * 没有任何 stop 回调。所以放弃的判据只能是心跳，不能是回调。</p>
	 */
	private static final int DRAW_HEARTBEAT_GRACE = 2;

	/** 弓的有效射程（平方）。再远箭的落点误差太大，不如走近点。 */
	public static final double BOW_RANGE_SQ = 400.0;
	/** 满弓与玩家相同的初速。 */
	private static final float BOW_PROJECTILE_SPEED = 3.0f;
	/**
	 * 骷髅的 {@code horizontal * 0.2} 是按 1.6 初速调出来的抛物线补偿。
	 * 本实体用玩家满弓的 3.0 初速，重力下坠近似与初速平方成反比，因此必须同步缩放；
	 * 原样照抄 0.2 会让 20 格处的箭高出目标数格，随机散布调得再小也没有用。
	 */
	private static final double BOW_GRAVITY_LIFT =
		0.2 * (1.6 / BOW_PROJECTILE_SPEED) * (1.6 / BOW_PROJECTILE_SPEED);
	/** 玩家弓本身的散布；伙伴不额外叠加“怪物难度”误差。 */
	private static final float BOW_DIVERGENCE = 1.0f;

	/**
	 * 射线上站着人时的错误码。
	 *
	 * <p>和 {@code NO_LINE_OF_SIGHT} 并列而不是复用它：两者的<b>处理方式</b>一样
	 * （挪个位置，别换武器），但原因完全不同，日志和遥测里必须分得开——
	 * 「他一直不放箭」到底是被地形挡着还是被你自己挡着，是两个不同的 bug。</p>
	 */
	public static final String FRIENDLY_IN_LINE = "FRIENDLY_IN_LINE";

	/**
	 * 判定「挡在射线上」时给玩家碰撞箱留的余量（格）。
	 *
	 * <p>取 0.3，也就是<b>香草箭矢自己判命中时给实体加的那一圈</b>
	 * （{@code ProjectileEntity} 对每个候选实体做 {@code expand(0.3)}）。
	 * 换句话说这条闸问的是一个物理问题：<b>这一箭真的会打到他吗</b>。</p>
	 *
	 * <p>第一版写的是 0.55，「宁可多收一次弓」。M23 的平衡场立刻抓到了代价：
	 * 主人在前一格、怪在主人正对面，是跟随时最常见的站位，而 0.55 会让射线擦到
	 * 判定框的一个角——一个 Lv.4 的弓手因此整场一箭没放。那不是安全，那是把
	 * Lv.4 解锁的能力关掉了。</p>
	 *
	 * <p>余下的那部分风险（箭在飞、主人自己走进弹道）本来就不该由瞄准这一层承担：
	 * 做决定的时候那支箭还不存在。那一半交给 {@code OwnerFriendlyFire}
	 * 在结算伤害时兜住。</p>
	 */
	private static final double FRIENDLY_FIRE_MARGIN = 0.3;

	/**
	 * 上一次因为有人挡着而收弓时，挡路的那个人在哪儿。
	 *
	 * <p>给驱动层用：知道挡在哪一侧，才能往<b>另一侧</b>挪一步，而不是随便挪。</p>
	 */
	private Vec3d lastShotBlockerPos;

	/** @return 上一次挡住射线的人所在位置；没有就是 null */
	public Vec3d lastShotBlockerPos() {
		return lastShotBlockerPos;
	}

	/**
	 * 从出箭点到瞄准点之间，有没有玩家挡着。
	 *
	 * <p>起点和终点都<b>照抄 {@link #release} 的那两行</b>——判的必须是真正会飞出去的
	 * 那条线，而不是一条差不多的线。旁观者不算（箭穿过他们）；创造模式的玩家仍然算，
	 * 因为为了一个极少见的情形去放宽这条闸不值得。</p>
	 */
	private boolean friendlyInLineOfFire(ServerWorld world,
			net.minecraft.entity.Entity target) {
		lastShotBlockerPos = null;
		Vec3d from = new Vec3d(getX(), getEyeY() - 0.1, getZ());
		Vec3d to = new Vec3d(target.getX(), target.getBodyY(0.3333), target.getZ());
		double reachSq = from.squaredDistanceTo(to);
		net.minecraft.util.math.Box sweep =
			new net.minecraft.util.math.Box(from, to).expand(FRIENDLY_FIRE_MARGIN + 1.0);
		for (net.minecraft.entity.player.PlayerEntity player
				: world.getEntitiesByClass(net.minecraft.entity.player.PlayerEntity.class,
					sweep, candidate -> candidate.isAlive() && !candidate.isSpectator())) {
			if (player == target) {
				continue; // 目标本人不算「挡路」——那是 isOwner 那道闸的事
			}
			net.minecraft.util.math.Box body =
				player.getBoundingBox().expand(FRIENDLY_FIRE_MARGIN);
			// 贴脸站着时射线根本没有「入射点」，raycast 会返回空。那也是挡着。
			if (body.contains(from)) {
				lastShotBlockerPos = player.getPos();
				return true;
			}
			Vec3d entry = body.raycast(from, to).orElse(null);
			if (entry != null && from.squaredDistanceTo(entry) < reachSq) {
				lastShotBlockerPos = player.getPos(); // 驱动层要靠它决定往哪边挪
				return true; // 站在目标<b>之前</b>才算挡；站在目标身后不影响
			}
		}
		return false;
	}

	private int nextShotTick;
	/**
	 * 主手最后一次被换掉的 tick。
	 *
	 * <p>换武器的那一拍<b>不能</b>立刻开始拉弓。服务端的装备变更要到本 tick 末尾才
	 * 随实体同步包发出去，而「正在使用物品」的标志位是另一条通道——客户端很可能先
	 * 收到「他在用物品」、手上却还是旧东西。客户端 {@code tickActiveItemStack} 一比
	 * 对不上就直接 {@code clearActiveItem()}，那一箭于是完全没有拉弓动画。</p>
	 *
	 * <p>这正是「第一箭的动画不对、后面都正常」——后面几箭弓已经在手上，没有装备
	 * 变更，自然不会错位。</p>
	 */
	private int mainHandSwapTick = Integer.MIN_VALUE;
	/** 正在瞄谁；null 表示没在拉弓。 */
	private UUID drawTargetId;
	/** 正在拉的<b>那一把</b>弓。用对象身份比较，见 {@link #tickBowDraw()}。 */
	private ItemStack drawingBow = ItemStack.EMPTY;
	private int drawStartTick;
	private int lastDrawRequestTick = Integer.MIN_VALUE;

	public boolean canShootNow() {
		return age >= nextShotTick;
	}

	public boolean isDrawingBow() {
		return drawTargetId != null;
	}

	/** 0..1 的拉弓进度，给回执和调试用。 */
	public float bowPullProgress() {
		return drawTargetId == null ? 0f
			: Math.min(1f, (age - drawStartTick) / (float) BOW_DRAW_TICKS);
	}

	/** 主手刚被换过。战斗层每次搬动主手都要报一声，拉弓会因此推迟一拍。 */
	public void noteMainHandSwapped() {
		this.mainHandSwapTick = age;
		dev.squire.server.combat.CombatBalanceTelemetry.recordWeaponSwitch(agentId());
		cancelDraw();
	}

	/**
	 * 举盾（守卫 Lv.6）。
	 *
	 * <p>走的是香草那条路：把副手的盾进入「正在使用」状态。香草
	 * {@code LivingEntity.damage} 会因此调用 {@code blockedByShield}，于是伤害真的
	 * 被挡下来、盾真的掉耐久、客户端真的看得见举盾姿势——一条特判都不需要。</p>
	 *
	 * @return 副手确实是一面盾，并且已经举起来了
	 */
	public boolean startBlocking() {
		ItemStack shield = items().equipped(net.minecraft.entity.EquipmentSlot.OFFHAND);
		if (!(shield.getItem() instanceof net.minecraft.item.ShieldItem)) {
			return false;
		}
		if (isUsingItem() && getActiveItem() == shield) {
			return true; // 已经举着了，别每拍重置 itemUseTime，那样永远到不了生效的 5 tick
		}
		cancelDraw();
		setCurrentHand(net.minecraft.util.Hand.OFF_HAND);
		return true;
	}

	/** 放盾。举着盾是砍不了人的。 */
	public void stopBlocking() {
		if (isUsingItem() && getActiveItem().getItem()
				instanceof net.minecraft.item.ShieldItem) {
			clearActiveItem();
		}
	}

	/** 现在举着盾吗（面板与测试用）。 */
	public boolean blocking() {
		return isBlocking();
	}

	/** 放下弓：清掉物品使用状态，客户端的弯弓模型和手臂姿势随之复位。 */
	public void cancelDraw() {
		drawTargetId = null;
		drawingBow = ItemStack.EMPTY;
		if (isUsingItem()) {
			// 用 clearActiveItem 而不是 stopUsingItem：后者会走 BowItem.onStoppedUsing，
			// 而那条路径只认 PlayerEntity，对我们是白跑一趟。
			clearActiveItem();
		}
		setAttacking(false);
	}

	/**
	 * 拉到一半被放弃的弓必须自己收回来，否则他会永远举着一把拉满的弓站在那里。
	 *
	 * <p>在 {@link #tick()} 的末尾调用，也就是 {@code super.tick()}（内含
	 * {@code goalSelector.tick()} → {@code SelfCareGoal}）已经跑完之后。这一点很重要：
	 * {@code SelfCare.consume()} 为了吃东西会把主手临时换成食物、再在 finally 里换回
	 * <b>同一个</b> ItemStack 对象，等我们看的时候已经复原了。所以用对象身份比较既能
	 * 抓住「玩家从面板里把弓拽走了」，又不会因为他咬了一口吃的就白白丢掉 20 tick 的
	 * 拉弓。</p>
	 */
	private void tickBowDraw() {
		if (drawTargetId == null) {
			return;
		}
		boolean abandoned = age - lastDrawRequestTick > DRAW_HEARTBEAT_GRACE
			|| items().equipped(net.minecraft.entity.EquipmentSlot.MAINHAND) != drawingBow
			|| !isUsingItem();
		if (abandoned) {
			cancelDraw();
		}
	}

	/**
	 * 拉弓射箭。
	 *
	 * <p>整套走香草的箭矢实体，而不是"隔空扣血"：箭真的飞出去、会被挡、会掉在地上、
	 * 会被捡回来。<b>箭是从背包里真实扣掉的</b>（无限附魔除外），弓也真的掉耐久——
	 * 这个模组一直的规矩是只搬运，绝不凭空生成。</p>
	 *
	 * <p>力量/冲击/火矢三条附魔按香草公式生效，所以「满配弓」是真的更强，
	 * 而不只是名字好听。</p>
	 */
	public dev.squire.api.body.InteractionResult shoot(UUID targetEntityId) {
        if (!dev.squire.server.combat.CombatStyle.gatesFor(profile() == null ? null : profile().profession).bow()) {
            cancelDraw();
            return InteractionResult.fail("BOW_LOCKED", "This profession has not unlocked bows");
        }
		if (!(getWorld() instanceof ServerWorld serverWorld)) {
			return InteractionResult.fail("SERVER_ONLY", "shots resolve server-side");
		}
		net.minecraft.entity.Entity target = serverWorld.getEntity(targetEntityId);
		if (target == null || !target.isAlive()) {
			cancelDraw();
			return InteractionResult.fail("ENTITY_NOT_FOUND", "target gone or dead");
		}
		if (isOwner(target)) {
			return refuseToHitOwner();
		}
		ItemStack bow = items().equipped(net.minecraft.entity.EquipmentSlot.MAINHAND);
		if (!(bow.getItem() instanceof net.minecraft.item.BowItem)) {
			cancelDraw();
			return InteractionResult.fail("NO_RANGED_WEAPON", "not holding a bow");
		}
		boolean infinite = net.minecraft.enchantment.EnchantmentHelper.getLevel(
			net.minecraft.enchantment.Enchantments.INFINITY, bow) > 0;
		var arrowSlot = items().firstArrowSlot();
		if (arrowSlot == null && !infinite) {
			cancelDraw(); // 空弦就把弓放下，别举着一把射不出去的弓发呆
			return InteractionResult.fail("NO_ARROWS", "out of arrows");
		}

		lastDrawRequestTick = age;       // 心跳：tickBowDraw 靠它判断有没有被放弃
		getLookControl().lookAt(target); // 手臂姿势直接读 head 的朝向，必须每拍更新

		// 看不见就别放箭——不然箭全钉在墙上和地形里，玩家看到的就是「准头很差」。
		// 原版 BowAttackGoal 也是这么做的：视线断了立刻收弓，而不是照射不误。
		if (!canSee(target)) {
			cancelDraw();
			return InteractionResult.fail("NO_LINE_OF_SIGHT", "target is behind cover");
		}
		// 有人站在射线上就收弓。{@link #isOwner} 挡住的是「瞄准主人」，挡不住
		// 「瞄着僵尸、而主人正好站在中间」——箭是真实的香草实体，它不认识谁是主人。
		// 这一条和视线被挡是同一类处境，所以给同一类出路：放下弓、挪个位置，
		// 而不是站着不动，也不是照射不误。
		if (friendlyInLineOfFire(serverWorld, target)) {
			cancelDraw();
			return InteractionResult.fail(FRIENDLY_IN_LINE,
				"a player is standing in the line of fire");
		}
		if (!canShootNow()) {
			return InteractionResult.fail("DRAWING_BOW", "recovering");
		}
		// 刚换上弓的那一拍不开始拉：装备变更还没同步到客户端，这时候进入使用状态
		// 会被客户端判成"手上的东西和正在用的对不上"而当场清掉，第一箭就没有动画。
		if (age <= mainHandSwapTick) {
			return InteractionResult.fail("DRAWING_BOW", "just drew the bow");
		}
		if (drawTargetId == null || !drawTargetId.equals(targetEntityId)
				|| drawingBow != bow) {
			beginDraw(targetEntityId, bow);
			return InteractionResult.fail("DRAWING_BOW", "drawing");
		}
		int drawn = age - drawStartTick;
		if (drawn < BOW_DRAW_TICKS) {
			return InteractionResult.fail("DRAWING_BOW", "drawing");
		}
		return release(serverWorld, target, bow, arrowSlot, infinite, drawn);
	}

	/** 开始拉弓。{@code setCurrentHand} 这一句就是客户端看到的"弓被拉开了"。 */
	private void beginDraw(UUID targetId, ItemStack bow) {
		// setCurrentHand 在已经在用别的东西时是无操作，会拉一把不存在的弓，先清干净。
		if (isUsingItem() && getActiveItem() != bow) {
			clearActiveItem();
		}
		drawTargetId = targetId;
		drawingBow = bow;
		drawStartTick = age;
		setAttacking(true);
		setCurrentHand(net.minecraft.util.Hand.MAIN_HAND);
	}

	/** 放弦。 */
	private dev.squire.api.body.InteractionResult release(ServerWorld serverWorld,
			net.minecraft.entity.Entity target, ItemStack bow,
			AvatarInventory.SlotRef arrowSlot, boolean infinite, int drawn) {
		float pull = net.minecraft.item.BowItem.getPullProgress(drawn);
		ItemStack arrowStack = arrowSlot == null
			? new ItemStack(net.minecraft.item.Items.ARROW)
			: items().getStack(arrowSlot.mainIndex()).copy();

		// createArrowProjectile 内部已经调用 applyEnchantmentEffects(this, 1.0f)，
		// 而那个方法自己就会从主手读 力量/冲击/火矢。之前这里又手动加了一遍——
		// 一把力量 V 的弓被当成了力量 X。那一段已经删掉，不要再加回来。
		var arrow = net.minecraft.entity.projectile.ProjectileUtil
			.createArrowProjectile(this, arrowStack, 1.0f);

		double dx = target.getX() - getX();
		double dy = target.getBodyY(0.3333) - arrow.getY();
		double dz = target.getZ() - getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		// 瞄准结构借鉴骷髅：目标身体下段 + 对重力下坠作补偿；但补偿量必须和这里
		// 的玩家弓初速配套，不能把骷髅 1.6 初速用的 0.2 原样搬过来。
		arrow.setVelocity(dx, dy + horizontal * BOW_GRAVITY_LIFT, dz,
			pull * BOW_PROJECTILE_SPEED, BOW_DIVERGENCE);
		if (pull >= 1.0f) {
			arrow.setCritical(true);
		}
		serverWorld.spawnEntity(arrow);
		serverWorld.playSound(null, getX(), getY(), getZ(),
			net.minecraft.sound.SoundEvents.ENTITY_ARROW_SHOOT,
			net.minecraft.sound.SoundCategory.NEUTRAL, 1.0f,
			1.0f / (random.nextFloat() * 0.4f + 1.2f) + pull * 0.5f);

		if (arrowSlot != null && !infinite) {
			items().getStack(arrowSlot.mainIndex()).decrement(1);
		}
		bow.damage(1, serverWorld.random, null);
		// 不再 swingHand()：现在有真的放箭动作了，再挥一下手反而像抽搐。
		cancelDraw();
		nextShotTick = age + SHOT_RECOVERY_TICKS;
		return InteractionResult.ok();
	}

	/** Restore health directly (used by the heal executor after consuming an item). */
	public void restoreHealth(float amount) {
		heal(amount);
	}

	// ------------------------------------------------------------------ modes (runtime-owned)

	public MovementMode mode() {
		return mode;
	}

	public void setFollowMode(UUID ownerUuid) {
		this.mode = MovementMode.FOLLOW;
		if (ownerUuid != null) {
			this.ownerUuid = ownerUuid;
			this.dataTracker.set(TRACKED_OWNER, java.util.Optional.of(ownerUuid));
		}
		this.stayPos = null;
		this.stayGlobalPosField = null;
		this.stayArea = null;
		applyActivityName();
	}

	public void setStayMode() {
		this.mode = MovementMode.STAY;
		this.stayPos = getBlockPos();
		if (getWorld() instanceof ServerWorld serverWorld) {
			this.stayGlobalPosField = GlobalPos.create(serverWorld.getRegistryKey(),
				stayPos.toImmutable());
		}
		getNavigation().stop();
		this.stayArea = stayGlobalPosField;
		applyActivityName();
	}

	/** Restore a saved STAY anchor（跨重启恢复锚点，方案 A3）。 */
	public void setStayAnchor(GlobalPos anchor) {
		if (anchor == null) {
			return;
		}
		this.mode = MovementMode.STAY;
		this.stayGlobalPosField = anchor;
		this.stayPos = anchor.getPos();
		getNavigation().stop();
		this.stayArea = stayGlobalPosField;
		applyActivityName();
	}

	public void setIdleMode() {
		this.mode = MovementMode.IDLE;
		this.stayPos = null;
		this.stayGlobalPosField = null;
		getNavigation().stop();
		this.stayArea = null;
		applyActivityName();
	}

	/**
	 * 巡逻：以当前位置为锚点，在半径内不断挑一个可达的点走过去。
	 * 和 STAY 一样有锚点，区别是它会动，用来"守着这一片"而不是杵在原地。
	 */
	public void setPatrolMode() {
		this.mode = MovementMode.PATROL;
		this.stayPos = getBlockPos();
		if (getWorld() instanceof ServerWorld serverWorld) {
			this.stayGlobalPosField = GlobalPos.create(serverWorld.getRegistryKey(),
				stayPos.toImmutable());
		}
		getNavigation().stop();
		this.stayArea = null;
		applyActivityName();
	}

	/**
	 * Restore a saved PATROL anchor（跨重启/跨维度恢复巡逻中心）。
	 *
	 * <p>和 {@link #setStayAnchor} 是一对：巡逻同样有锚点，只是它会在锚点周围走动。
	 * 少了这个方法，恢复路径就只能把巡逻降级成 IDLE——玩家点了巡逻，退出再进来
	 * 他就站着不动了。</p>
	 */
	public void setPatrolAnchor(GlobalPos anchor) {
		if (anchor == null) {
			return;
		}
		this.mode = MovementMode.PATROL;
		this.stayGlobalPosField = anchor;
		this.stayPos = anchor.getPos();
		getNavigation().stop();
		this.stayArea = null;
		applyActivityName();
	}

	/**
	 * 把一份"模式 + 锚点"整体应用到这个身体上——恢复档案、跨维度重放、重新召唤
	 * 全部走这一个入口。
	 *
	 * <p>在此之前，同样的 switch 在 {@code SquireRuntime} 里被抄了三份
	 * （restoreRecordOnto / restoreBelongings / controlledTeleport），而三份都只写了
	 * FOLLOW/STAY/IDLE。结果是 PATROL 被正常存进档案、却在每一条恢复路径上静默退化成
	 * IDLE：玩家点了巡逻，退出再进来他就站着不动了。收敛成一处之后，再加模式只会漏一个
	 * 地方——就是这里。</p>
	 *
	 * @param anchor STAY/PATROL 的锚点；为 null 或跨维度时退回 IDLE
	 */
	public void applyMovementState(MovementMode mode, UUID ownerId, GlobalPos anchor) {
		MovementMode target = mode == null ? MovementMode.IDLE : mode;
		boolean anchorUsable = anchor != null && getWorld() instanceof ServerWorld world
			&& anchor.getDimension().equals(world.getRegistryKey());
		switch (target) {
			case FOLLOW -> setFollowMode(ownerId);
			case STAY -> {
				if (anchorUsable) {
					setStayAnchor(anchor);
				} else {
					setIdleMode();
				}
			}
			case PATROL -> {
				if (anchorUsable) {
					setPatrolAnchor(anchor);
				} else {
					setIdleMode();
				}
			}
			case IDLE -> setIdleMode();
		}
	}

	/** Restore mode without side effects（从 Store 恢复既有伙伴时使用，方案 A2）。 */
	public void restoreMode(MovementMode restored, GlobalPos stayAnchor) {
		this.mode = restored == null ? MovementMode.IDLE : restored;
		// STAY 和 PATROL 都靠 stayPos 定位：前者站在锚点上，后者绕着锚点走。
		boolean anchored = this.mode == MovementMode.STAY
			|| this.mode == MovementMode.PATROL;
		if (anchored && stayAnchor != null
				&& stayAnchor.getDimension().equals(
					((ServerWorld) getWorld()).getRegistryKey())) {
			this.stayPos = stayAnchor.getPos();
			this.stayGlobalPosField = stayAnchor;
		}
	}

	public Optional<BlockPos> stayPos() {
		return Optional.ofNullable(stayPos);
	}

	public Optional<GlobalPos> stayGlobalPos() {
		return Optional.ofNullable(stayGlobalPosField);
	}

	public Optional<UUID> syncedOwner() {
		Optional<UUID> synced = this.dataTracker.get(TRACKED_OWNER);
		if (synced != null && synced.isPresent()) {
			return synced;
		}
		return Optional.ofNullable(ownerUuid);
	}

	/**
	 * 在水里稳住，而不是不停起跳。
	 *
	 * <p>原版 {@code SwimGoal} 的 {@code tick()} 是「在水里有 80% 概率触发一次跳跃」
	 * ——那是僵尸和牛在水面上一颠一颠的由来。放在一个长得像玩家的伙伴身上，观感
	 * 就是玩家反馈的「他在水面上行走，很奇怪」。</p>
	 *
	 * <p>这里换成平滑上浮：只在头顶还在水面以下时给一点向上的速度，够浮起来就停，
	 * 于是他稳定停在水面而不是反复弹起落下。构造时仍然要
	 * {@code setCanSwim(true)}（原版 SwimGoal 的构造函数做的事），否则陆地寻路
	 * 会把水格当成不可通行，他会拒绝下水。</p>
	 */
	private class SwimSurfaceGoal extends net.minecraft.entity.ai.goal.Goal {

		SwimSurfaceGoal() {
			setControls(java.util.EnumSet.of(
				net.minecraft.entity.ai.goal.Goal.Control.JUMP));
			getNavigation().setCanSwim(true);
		}

		@Override
		public boolean canStart() {
			return isTouchingWater()
				&& getFluidHeight(net.minecraft.registry.tag.FluidTags.WATER)
					> getSwimHeight()
				|| isInLava();
		}

		@Override
		public boolean shouldContinue() {
			return canStart();
		}

		@Override
		public boolean shouldRunEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			// 只在快沉下去时补一点浮力。原版那种「随机起跳」会把人弹出水面。
			if (getFluidHeight(net.minecraft.registry.tag.FluidTags.WATER)
					> getSwimHeight() * 1.4) {
				var velocity = getVelocity();
				setVelocity(velocity.x, Math.min(0.09, velocity.y + 0.03), velocity.z);
			}
		}
	}

	/**
	 * 在水里摆出游泳姿势，而不是直立着走。
	 *
	 * <p>光设 {@code setSwimming(true)} 是不够的——真正驱动动画的是<b>姿势</b>：
	 * {@code LivingEntity.updateLeaningPitch()} 看的是 {@code isInSwimmingPose()}，
	 * 也就是 {@code getPose() == SWIMMING}，而 {@code leaningPitch} 才是
	 * {@code PlayerEntityModel} 用来把身体放平的那个量。原版只有玩家会去设这个姿势，
	 * 普通 mob 永远是站着的——所以之前只改标志位，看起来仍然是「在水里走路」。</p>
	 *
	 * <p>实体是用 {@code EntityDimensions.fixed} 注册的，姿势不会改变碰撞箱，
	 * 所以这纯粹是观感改动，不影响任何寻路和命中判定。</p>
	 *
	 * <p>另外这里<b>主动在 tick 里调用</b>，不依赖原版是否会替 mob 调
	 * {@code updateSwimming()}——原版那条路要求 {@code isSprinting()}，mob 永远不满足。</p>
	 */
	private void tickSwimmingPose() {
		boolean swimming = isTouchingWater()
			&& getFluidHeight(net.minecraft.registry.tag.FluidTags.WATER)
				> getSwimHeight();
		if (swimming != isSwimming()) {
			setSwimming(swimming);
		}
		if (swimming) {
			if (getPose() != net.minecraft.entity.EntityPose.SWIMMING) {
				setPose(net.minecraft.entity.EntityPose.SWIMMING);
			}
		} else if (getPose() == net.minecraft.entity.EntityPose.SWIMMING) {
			setPose(net.minecraft.entity.EntityPose.STANDING);
		}
	}

	/**
	 * 残血时自己吃东西——一条反射，不需要玩家开口。
	 *
	 * <p>「治疗自己」以前只有玩家喊出来才会发生：需要盯着他的血条、在战斗中腾出手
	 * 打字。那不是一个能力，那是一个负担。现在掉到半血就自己想办法，手动那条路
	 * （{@code heal.self} 任务）保留给「现在就吃，别等」。</p>
	 *
	 * <p>不占用任何 Control：吃东西不妨碍走路和打架，他该边打边喝。</p>
	 */
	private class SelfCareGoal extends net.minecraft.entity.ai.goal.Goal {
		/**
		 * 下一次允许自救的 tick。
		 *
		 * <p>用绝对 tick 而不是倒计数：之前写的是 {@code cooldown-- > 0}，在一个
		 * 契约上应当是纯判断的 {@code canStart()} 里改状态。它现在只是「碰巧」每
		 * tick 被轮询一次才对得上，任何多问一次（别的模组包了 goalSelector、
		 * 测试里直接调）都会白白烧掉冷却。</p>
		 */
		private int nextAllowedTick = Integer.MIN_VALUE;

		SelfCareGoal() {
			setControls(java.util.EnumSet.noneOf(
				net.minecraft.entity.ai.goal.Goal.Control.class));
		}

		@Override
		public boolean canStart() {
			if (getWorld().isClient) {
				return false;
			}
			if (age < nextAllowedTick) {
				// Counting this check is intentionally limited to active measurement
				// sessions; ordinary gameplay does not rescan the inventory on cooldown.
				if (dev.squire.server.combat.CombatBalanceTelemetry.active(agentId())
						&& dev.squire.server.item.SelfCare.needsCare(AvatarEntity.this,
							dev.squire.server.item.SelfCare.triggerFraction(profession(),
								dev.squire.server.runtime.SquireRuntime.professionBalance()))
						&& !dev.squire.server.item.SelfCare.stillHealing(AvatarEntity.this)
						&& dev.squire.server.item.SelfCare
							.pick(AvatarEntity.this, potionsAllowed()).isPresent()) {
					dev.squire.server.combat.CombatBalanceTelemetry
						.recordCooldownAttempt(agentId());
				}
				return false;
			}
			// The explicit heal task and this reflex share one inventory. Let exactly one
			// consumer own it, otherwise both can refresh the same non-stacking effect and
			// burn through the whole backpack without restoring the expected health.
			if (explicitSelfCareCheck != null && explicitSelfCareCheck.getAsBoolean()) {
				return false;
			}
			var currentProfile = profile();
			if (currentProfile != null && !currentProfile.autonomyLevel().atLeast(
					dev.squire.server.profile.AutonomyLevel.STANDARD)) {
				return false;
			}
			if (!dev.squire.server.item.SelfCare.needsCare(AvatarEntity.this,
						dev.squire.server.item.SelfCare.triggerFraction(profession(),
							dev.squire.server.runtime.SquireRuntime.professionBalance()))
					|| dev.squire.server.item.SelfCare.stillHealing(AvatarEntity.this)) {
				return false;
			}
			return dev.squire.server.item.SelfCare
				.pick(AvatarEntity.this, potionsAllowed()).isPresent();
		}

		/** 守卫 Lv.6：血量还高的时候只准吃饭，不许开药水。 */
		private boolean potionsAllowed() {
			return dev.squire.server.item.SelfCare.potionsAllowed(AvatarEntity.this,
				profession(),
				dev.squire.server.runtime.SquireRuntime.professionBalance());
		}

		@Override
		public boolean shouldContinue() {
			return false; // 一次一件，吃完就退出，下一拍重新判断
		}

		/** 连续失败次数。{@code pick()} 是确定性的，失败会一直挑到同一件东西。 */
		private int consecutiveFailures;

		@Override
		public void start() {
			// 吃成了才进长冷却。之前无论成败都等 40 tick 且丢弃返回值——于是他会
			// 对着同一件用不了的东西每两秒重试一次，永远轮不到下一件。
			boolean ate = dev.squire.server.item.SelfCare
				.pick(AvatarEntity.this, potionsAllowed())
				.map(remedy -> dev.squire.server.item.SelfCare.useOnSelf(
					AvatarEntity.this, remedy.itemId()))
				.orElse(false);
			if (ate) {
				consecutiveFailures = 0;
				nextAllowedTick = age + consumableCooldown();
				return;
			}
			// 短间隔重试几次（背包在变，下一次多半会挑到别的），仍然失败就退到长
			// 冷却——绝不为了「万一这次能行」把每 5 tick 一次的空转永远跑下去。
			nextAllowedTick = age + (++consecutiveFailures
					>= dev.squire.server.item.SelfCare.MAX_RETRIES
				? consumableCooldown()
				: dev.squire.server.item.SelfCare.RETRY_TICKS);
		}

		/**
		 * 两次自救之间的最短间隔。
		 *
		 * <p>守卫 Lv.6 起用配置里的 {@code consumableUseCooldown}——设计文档明令
		 * 禁止「战斗中无冷却连续喝药」，而那条禁令要生效就得有一个真的能调的数字。</p>
		 */
		private int consumableCooldown() {
			var data = profession();
			if (data != null && data.can(dev.squire.server.profession.ProfessionAbility
					.GUARD_SHIELD_PROFICIENCY)) {
				return dev.squire.server.runtime.SquireRuntime.professionBalance()
					.guardConsumableCooldownTicks;
			}
			return dev.squire.server.item.SelfCare.COOLDOWN_TICKS;
		}
	}

	/** 这只随从的职业进度；没有档案时返回 null。 */
	private dev.squire.server.profession.ProfessionData profession() {
		var currentProfile = profile();
		return currentProfile == null ? null : currentProfile.profession;
	}

	/** Internal goal implementing FOLLOW semantics (start/stop distances, far teleport). */
	private class FollowOwnerGoal extends net.minecraft.entity.ai.goal.Goal {
		private PlayerEntity ownerRef;
		/** 连续多少 tick 没有可走的路。用来识别「近但过不去」。 */
		private int idleTicks;
		/** 和香草跟随 Goal 一样低频重寻路，避免每 tick 重启 MoveControl。 */
		private int repathCooldown;
		/** 上一拍看到的主人位置，用真实位移判断他是否还在走。 */
		private Vec3d lastOwnerPos;
		/** 主人刚停下时保留几拍连续跟随，避免位置量化造成启停抖动。 */
		private int ownerMovementGraceTicks;
		/** 空中没有安全落点时降低重试频率，不能每 tick 扫一遍候选格。 */
		private int teleportRetryCooldown;

		FollowOwnerGoal() {
			setControls(java.util.EnumSet.of(net.minecraft.entity.ai.goal.Goal.Control.MOVE));
		}

		@Override
		public boolean canStart() {
			if (mode != MovementMode.FOLLOW || ownerUuid == null || getWorld().isClient
					|| taskDriven() || inCombat()) {
				return false;
			}
			this.ownerRef = ((ServerWorld) getWorld()).getServer()
				.getPlayerManager().getPlayer(ownerUuid);
			if (ownerRef == null || ownerRef.getWorld() != getWorld()) {
				return false;
			}
			observeOwnerMovement();
			double startDistance = ownerMovementGraceTicks > 0
				? FOLLOW_MOVING_START_DISTANCE_SQ : FOLLOW_START_DISTANCE_SQ;
			return squaredDistanceTo(ownerRef) > startDistance;
		}

		@Override
		public boolean shouldContinue() {
			if (mode != MovementMode.FOLLOW || taskDriven() || inCombat()
					|| ownerRef == null || !ownerRef.isAlive()) {
				return false;
			}
			double distSq = squaredDistanceTo(ownerRef);
			// 主人在走时让 Goal 保持接管：即使刚追到合适距离，也能在主人下一步迈出后
			// 立刻续上，而不是退出 Goal、等距离重新拉到六格才启动。
			if (ownerMovementGraceTicks > 0) {
				return true;
			}
			// 隔着墙时寻路会直接放弃（isIdle），而那正是最需要传送的时候。
			// 只要还差得远，这个 goal 就必须继续 tick 下去，否则 tick() 里的
			// 传送分支永远没有机会执行。
			if (distSq > followTeleportDistanceSq()) {
				return true;
			}
			if (getNavigation().isIdle()) {
				return distSq > FOLLOW_START_DISTANCE_SQ
					&& idleTicks < FOLLOW_STUCK_TELEPORT_TICKS * 2;
			}
			return distSq > FOLLOW_STOP_DISTANCE_SQ;
		}

		@Override
		public void start() {
			idleTicks = 0;
			repathCooldown = 10;
			getNavigation().startMovingTo(ownerRef, 1.15);
		}

		@Override
		public boolean shouldRunEveryTick() {
			// 距离带和“主人正在走”的判断需要逐 tick 更新；隔拍执行会把一次正常迈步
			// 变成停一下再追一下，玩家近距离看起来就是卡顿。
			return true;
		}

		@Override
		public void stop() {
			repathCooldown = 0;
			// A task calling moveTo() switches the mode to IDLE, which makes this goal
			// stop on the NEXT ai pass — one tick AFTER the task started its own path.
			// Cancelling navigation here would kill that path, so the body would stand
			// still and the task would report AGENT_STUCK for a move it never got to try.
			if (!taskDriven()) {
				getNavigation().stop();
			}
		}

		@Override
		public void tick() {
			if (ownerRef == null) {
				return;
			}
			observeOwnerMovement();
			double distSq = squaredDistanceTo(ownerRef);
			// 「路断了」也算跟丢：隔着一堵墙站着不动，和落在三十格外没有区别。
			idleTicks = getNavigation().isIdle() ? idleTicks + 1 : 0;
			boolean stuck = idleTicks >= FOLLOW_STUCK_TELEPORT_TICKS
				&& distSq > FOLLOW_START_DISTANCE_SQ;
			if (distSq > followTeleportDistanceSq() || stuck) {
				if (teleportRetryCooldown > 0) {
					teleportRetryCooldown--;
				} else if (tryTeleportNear(ownerRef)) {
					idleTicks = 0;
					teleportRetryCooldown = 0;
				} else {
					// 最常见的失败就是主人在半空；一秒后再试，落地后仍会很快跟上。
					teleportRetryCooldown = 20;
				}
				getLookControl().lookAt(ownerRef);
				return;
			}
			teleportRetryCooldown = 0;
			double stopDistance = ownerMovementGraceTicks > 0
				? FOLLOW_MOVING_STOP_DISTANCE_SQ : FOLLOW_STOP_DISTANCE_SQ;
			if (distSq <= stopDistance) {
				if (!getNavigation().isIdle()) {
					getNavigation().stop();
				}
				// 主人下一步走出距离带时应立即出发，不能继承上一次的十拍冷却。
				repathCooldown = 0;
				getLookControl().lookAt(ownerRef);
				return;
			}
			// 原来站位分支拿“自己离站位还有多远”作重寻路判据；在走到两格内以前它
			// 永远为真，于是每 tick 都把当前路径换成一条新路径，正是一步一卡的来源。
			// 固定十 tick 更新一次既能跟上移动的主人，也让 MoveControl 连续走完节点。
			// 路径可能在十拍冷却结束前已经走到旧目标。此时若主人仍在走，等待剩余
			// 冷却就是最明显的“一步一卡”；空闲路径必须当拍续上。
			if (getNavigation().isIdle() || --repathCooldown <= 0) {
				repathCooldown = 10;
				Vec3d station = station(ownerRef);
				if (station != null) {
					getNavigation().startMovingTo(station.x, station.y, station.z, 1.2);
				} else {
					getNavigation().startMovingTo(ownerRef, 1.15);
				}
			}
			getLookControl().lookAt(ownerRef);
		}

		/** 用主人相邻两拍的水平位移判断走动，不依赖服务端玩家速度字段。 */
		private void observeOwnerMovement() {
			Vec3d now = ownerRef.getPos();
			if (lastOwnerPos != null) {
				double dx = now.x - lastOwnerPos.x;
				double dz = now.z - lastOwnerPos.z;
				if (dx * dx + dz * dz > 1.0e-4) {
					ownerMovementGraceTicks = FOLLOW_OWNER_MOVEMENT_GRACE_TICKS;
				} else if (ownerMovementGraceTicks > 0) {
					ownerMovementGraceTicks--;
				}
			}
			lastOwnerPos = now;
		}

		/**
		 * 受到威胁时的站位；没有威胁（或没有相应能力）时返回 {@code null}，
		 * 跟随就回到普通的“跟着人走”。
		 *
		 * <p>两种站位是同一个机制的两个方向：血够就站到主人与攻击者的连线上
		 * （挡在前面），血不够就站到主人背后（性格决定阈值）。后一种不需要能力：
		 * 一个快死的伙伴继续抵在前面只会变成一具尸体。</p>
		 */
		private Vec3d station(PlayerEntity owner) {
			net.minecraft.entity.LivingEntity threat = owner.getAttacker();
			if (threat == null || !threat.isAlive() || threat == AvatarEntity.this) {
				return null;
			}
			Vec3d away = owner.getPos().subtract(threat.getPos());
			if (away.lengthSquared() < 1.0e-4) {
				return null;
			}
			Vec3d unit = away.normalize();
			boolean hurt = getHealth() <= getMaxHealth()
				* dev.squire.server.combat.GuardRuntime.retreatFraction(profile());
			if (hurt) {
				return owner.getPos().add(unit.multiply(2.5)); // 退到主人身后
			}
			if (!can(dev.squire.server.profile.Ability.COMBAT_INTERPOSE)) {
				return null;
			}
			return owner.getPos().subtract(unit.multiply(1.6)); // 插到中间
		}

		/**
		 * 传送到主人身边的一格安全落点。
		 *
		 * <p>以前只在主人同一高度的 3×3 里找，而且判据是「脚下不是空气、身位两格是空气」。
		 * 主人站在台阶上、船里、洞口、树上时几乎必然找不到落点，于是传送静默失败，
		 * 表现出来就是「跟随卡住了」。现在按香草狼的做法扫 7×3×7，并用寻路自己的
		 * {@code WALKABLE} 判据——岩浆、火、仙人掌、掉落陷阱都由它统一排除。</p>
		 *
		 * @return 真的传送了才返回 true
		 */
		private boolean tryTeleportNear(PlayerEntity ownerRef) {
			BlockPos ownerPos = ownerRef.getBlockPos();
			boolean allowWater = ownerRef.isTouchingWater();
			for (int attempt = 0; attempt < 10; attempt++) {
				int dx = random.nextInt(7) - 3;
				int dy = random.nextInt(3) - 1;
				int dz = random.nextInt(7) - 3;
				// 别落在主人头上/脚下那一格，会把人顶开。
				if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
					continue;
				}
				if (teleportTo(ownerPos.add(dx, dy, dz), allowWater)) {
					return true;
				}
			}
			// 随机点都不合适时再做一次确定性搜索；它现在只接受安全落脚点。
			// 主人在半空时会返回 false，绝不能再退回到主人的悬空坐标。
			return teleportNextTo(ownerRef);
		}

		/** 和香草狼同一套判据：能走上去、不是树叶、而且身位放得下。 */
		private boolean teleportTo(BlockPos pos, boolean allowWater) {
			if (!isSafeStandingCell(pos, allowWater)) {
				return false;
			}
			teleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			getNavigation().stop();
			return true;
		}
	}

	// ------------------------------------------------------------------ stay anchor goal

	/**
	 * STAY 语义的本地回归（方案 A3）：被推动后自动走回锚点，而不只是关闭 wander。
	 * 锚点在别的维度时不追（跨维度由受控传送负责）。
	 */
	private class StayAreaGoal extends net.minecraft.entity.ai.goal.Goal {
		private BlockPos anchor;

		StayAreaGoal() {
			setControls(java.util.EnumSet.of(net.minecraft.entity.ai.goal.Goal.Control.MOVE));
		}

		@Override
		public boolean canStart() {
			return mode == MovementMode.STAY && !taskDriven()
				&& stayGlobalPosField != null
				&& getWorld() instanceof ServerWorld serverWorld
				&& serverWorld.getRegistryKey().equals(stayGlobalPosField.getDimension());
		}

		@Override
		public boolean shouldContinue() {
			return canStart();
		}

		@Override
		public boolean shouldRunEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			// GoalSelector 会直接 tick 运行中的 goal（不重查 shouldContinue），
			// 模式/锚点可能在两次 AI pass 之间被清掉，必须在这里自守。
			if (mode != MovementMode.STAY || stayGlobalPosField == null
					|| taskDriven()) {
				return; // 任务接管中：待命区域不抢方向盘
			}
			BlockPos target = stayGlobalPosField.getPos();
			double dx = getX() - (target.getX() + 0.5);
			double dy = getY() - target.getY();
			double dz = getZ() - (target.getZ() + 0.5);
			double distSq = dx * dx + dy * dy + dz * dz;
			// STAY means exactly this block.  Knockback can displace the entity, but the
			// mode remains STAY and this goal immediately walks it back to the anchor.
			double radius = stayRadius();
			if (distSq > radius * radius) {
				if (!getNavigation().isFollowingPath()) {
					getNavigation().startMovingTo(target.getX() + 0.5,
						target.getY(), target.getZ() + 0.5, 1.1);
				}
			} else if (!getNavigation().isIdle()) {
				getNavigation().stop();
			}
			this.anchor = target;
		}

		@SuppressWarnings("unused")
		BlockPos lastAnchor() {
			return anchor;
		}
	}

	// ------------------------------------------------------------------ persistence

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
        nbt.putLong("SquireOathDeadline", oathDeadline);
        if (oathAnchor != null) {
            nbt.putDouble("SquireOathX", oathAnchor.x);
            nbt.putDouble("SquireOathY", oathAnchor.y);
            nbt.putDouble("SquireOathZ", oathAnchor.z);
        }
		nbt.putUuid(NBT_AGENT_ID, agentId());
		if (ownerUuid != null) {
			nbt.putUuid(NBT_OWNER, ownerUuid);
		}
		if (homePos != null) {
			nbt.putLong(NBT_HOME, homePos.asLong());
		}
		nbt.putInt(NBT_FOLLOW_TELEPORT, followTeleportDistance);
		nbt.putString(NBT_COMBAT_STYLE, combatStyle.name());
		net.minecraft.inventory.Inventories.writeNbt(nbt, inventory.stacks);
		// 背包单独存一格。不并进主背包的列表：那 36 格的下标是有语义的（面板网格、
		// SlotRef.mainIndex），多塞一个进去所有下标都会错位。
		if (!backpackStack().isEmpty()) {
			nbt.put(NBT_BACKPACK, backpackStack().writeNbt(new NbtCompound()));
		}
		if (baseName != null) {
			nbt.putString(NBT_BASE_NAME, baseName.getString());
		}
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
        oathDeadline = Math.max(0, nbt.getLong("SquireOathDeadline"));
        if (oathActive()) {
            oathAnchor = new Vec3d(nbt.getDouble("SquireOathX"), nbt.getDouble("SquireOathY"), nbt.getDouble("SquireOathZ"));
            setAiDisabled(true);
        }
		if (nbt.containsUuid(NBT_AGENT_ID)) {
			this.agentId = nbt.getUuid(NBT_AGENT_ID);
		}
		if (nbt.containsUuid(NBT_OWNER)) {
			this.ownerUuid = nbt.getUuid(NBT_OWNER);
			this.dataTracker.set(TRACKED_OWNER, java.util.Optional.of(ownerUuid));
		}
		if (nbt.contains(NBT_HOME)) {
			this.homePos = BlockPos.fromLong(nbt.getLong(NBT_HOME));
			this.homeGlobalPosField = GlobalPos.create(
				((ServerWorld) getWorld()).getRegistryKey(), homePos.toImmutable());
		}
		if (nbt.contains(NBT_COMBAT_STYLE)) {
			try {
				setCombatStyle(dev.squire.server.combat.CombatStyle.Style.valueOf(
					nbt.getString(NBT_COMBAT_STYLE)));
			} catch (IllegalArgumentException e) {
				setCombatStyle(dev.squire.server.combat.CombatStyle.Style.AUTO);
			}
		}
		if (nbt.contains(NBT_FOLLOW_TELEPORT)) {
			setFollowTeleportDistance(nbt.getInt(NBT_FOLLOW_TELEPORT));
		}
		net.minecraft.inventory.Inventories.readNbt(nbt, inventory.stacks);
		setBackpackStack(nbt.contains(NBT_BACKPACK)
			? net.minecraft.item.ItemStack.fromNbt(nbt.getCompound(NBT_BACKPACK))
			: net.minecraft.item.ItemStack.EMPTY);
		// 本名先于任何 applyActivityName 恢复，否则又会拿装饰过的名牌当本名。
		if (nbt.contains(NBT_BASE_NAME)) {
			this.baseName = net.minecraft.text.Text.literal(
				stripNameDecorations(nbt.getString(NBT_BASE_NAME)));
		}
		// mode is restored from the world-level AgentRecord by the runtime on load
		// (方案 A2)；实体自身不再把模式硬编码回 IDLE。
	}

	// ------------------------------------------------------------------ legacy helpers

	public Optional<UUID> owner() {
		return Optional.ofNullable(ownerUuid);
	}

	public void setOwner(UUID uuid) {
		this.ownerUuid = uuid;
		this.dataTracker.set(TRACKED_OWNER, java.util.Optional.of(uuid));
	}

	public Optional<BlockPos> homePos() {
		return Optional.ofNullable(homePos);
	}

	public Optional<GlobalPos> homeGlobalPos() {
		return Optional.ofNullable(homeGlobalPosField);
	}

	public void setHomeGlobalPos(GlobalPos home) {
		this.homeGlobalPosField = home;
		this.homePos = home == null ? null : home.getPos().toImmutable();
	}

	@Deprecated // legacy setter kept for old callers; prefer setHomeGlobalPos
	public void setHomePos(BlockPos pos) {
		this.homePos = pos.toImmutable();
		if (getWorld() instanceof ServerWorld serverWorld) {
			this.homeGlobalPosField = GlobalPos.create(serverWorld.getRegistryKey(),
				pos.toImmutable());
		}
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	// ------------------------------------------------------------------ move handle

	private static final class AvatarMoveHandle implements MoveHandle {
		private State state = State.MOVING;
		private String errorCode;

		@Override
		public State state() {
			return state;
		}

		@Override
		public void cancel() {
			if (state == State.MOVING) {
				state = State.CANCELLED;
			}
		}

		void markFailed(String code) {
			if (state == State.MOVING) {
				state = State.FAILED;
				errorCode = code;
			}
		}

		void markArrivedIfMoving() {
			if (state == State.MOVING) {
				state = State.ARRIVED;
			}
		}

		@Override
		public String errorCode() {
			return errorCode;
		}
	}

	// ------------------------------------------------------------------ inventory mutation

	/**
	 * 权威背包（方案 C1）。所有 Executor 的物品修改都必须经过它；下面的旧方法
	 * 只是薄封装，保留是为了不改动大量调用点。
	 */
	public AvatarInventory items() {
		return authoritativeInventory;
	}

	/** Main-inventory slot count（36 格，方案 A2/C1）。 */
	public int inventorySize() {
		return inventory.size();
	}

	/** Direct read access to one main-inventory slot (copy-free view; do NOT mutate). */
	public net.minecraft.item.ItemStack mainInventoryStack(int slot) {
		return inventory.getStack(slot);
	}

	public net.minecraft.inventory.Inventory backingInventory() {
		return inventory;
	}

	/** 背包槽本体（一格）。面板的槽位和对接层都直接读写它。 */
	public net.minecraft.inventory.Inventory backpackSlotInventory() {
		return backpackSlot;
	}

	/** 他现在背着的那件东西；没背就是 EMPTY。 */
	public net.minecraft.item.ItemStack backpackStack() {
		return backpackSlot.getStack(0);
	}

	public void setBackpackStack(net.minecraft.item.ItemStack stack) {
		backpackSlot.setStack(0, stack == null ? net.minecraft.item.ItemStack.EMPTY : stack);
		backpackSlot.markDirty();
	}

	/** 客户端读的那一份：渲染层拿它决定背上画什么。 */
	public net.minecraft.item.ItemStack syncedBackpack() {
		return this.dataTracker.get(TRACKED_BACKPACK);
	}

	private void syncBackpackToClients() {
		net.minecraft.item.ItemStack shown = backpackSlot.getStack(0);
		// 只送一件"样子"过去：数量恒为 1，内容不在 NBT 里也不需要在。
		this.dataTracker.set(TRACKED_BACKPACK,
			shown.isEmpty() ? net.minecraft.item.ItemStack.EMPTY : shown.copy());
	}

	/**
	 * Insert a stack into the avatar inventory (merge-then-empty-slot).
	 *
	 * @return the remainder that did NOT fit (EMPTY stack when everything fit)
	 */
	public net.minecraft.item.ItemStack insertStack(net.minecraft.item.ItemStack stack) {
		return authoritativeInventory.insert(stack);
	}

	public int countItem(net.minecraft.util.Identifier itemId) {
		return authoritativeInventory.countOf(itemId);
	}

	public boolean hasAtLeast(net.minecraft.util.Identifier itemId, int count) {
		return countItem(itemId) >= count;
	}

	/** Remove up to {@code count} of the item; returns stacks actually removed. */
	public java.util.List<net.minecraft.item.ItemStack> extractItems(
			net.minecraft.util.Identifier itemId, int count) {
		return authoritativeInventory.extract(itemId, count);
	}

	// ------------------------------------------------------------------ inventory view

	private final class AvatarInventoryView implements InventoryView {
		@Override
		public int countOf(String itemId) {
			return authoritativeInventory.countOf(new Identifier(itemId));
		}

		@Override
		public List<String> distinctItemIds() {
			return authoritativeInventory.distinctItemIds();
		}

		@Override
		public int occupiedSlots() {
			return authoritativeInventory.occupiedSlots();
		}
	}
}
