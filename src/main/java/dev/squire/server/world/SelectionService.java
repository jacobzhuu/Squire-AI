package dev.squire.server.world;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;
import net.minecraft.world.RaycastContext;

/**
 * 玩家区域选择（方案 F2）。选区是 SERVER 拥有的状态：玩家用命令设定，模型只能
 * 引用 "the selection"，永远不能自己编出一组坐标。
 *
 * <p>选区随世界存档持久化，所以"设好选区 → 下线 → 上线 → 一句话铺石头"仍然成立。
 * 每次设定都会把解析后的维度、边界和体积回显给玩家，避免"我以为选的是别处"。</p>
 */
public final class SelectionService extends PersistentState {

	private static final Logger LOG = LoggerFactory.getLogger(SelectionService.class);

	public static final int CURRENT_SCHEMA_VERSION = 1;
	private static final String KEY_SCHEMA_VERSION = "schemaVersion";
	private static final String KEY_SELECTIONS = "selections";

	/** 注视取点的最大距离（与创造模式的到达距离同量级）。 */
	private static final double LOOK_DISTANCE = 32.0;

	/**
	 * 一位玩家的当前选区。两个角点可以分别设定，只有都设好了才构成一个区域。
	 */
	public record Selection(UUID ownerId, RegistryKey<net.minecraft.world.World> dimension,
			BlockPos pos1, BlockPos pos2) {

		public boolean complete() {
			return pos1 != null && pos2 != null;
		}

		public Optional<BoundedRegion> region() {
			return complete()
				? Optional.of(BoundedRegion.ofCorners(pos1.getX(), pos1.getY(), pos1.getZ(),
					pos2.getX(), pos2.getY(), pos2.getZ()))
				: Optional.empty();
		}

		public String dimensionId() {
			return dimension.getValue().toString();
		}

		/** Player-facing echo: dimension, both corners and the resolved volume. */
		public String describe() {
			if (!complete()) {
				return "选区未完成（" + dimensionId() + "）：pos1="
					+ describeCorner(pos1) + "，pos2=" + describeCorner(pos2);
			}
			BoundedRegion region = region().orElseThrow();
			return dimensionId() + " " + describeCorner(region.min()) + " → "
				+ describeCorner(region.max()) + "，共 " + region.volume() + " 格";
		}

		private static String describeCorner(BlockPos pos) {
			return pos == null ? "未设定"
				: "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
		}
	}

	/** owner uuid -> selection。 */
	private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
	private boolean failClosed;

	public static SelectionService get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(
			SelectionService::createFromNbt, SelectionService::new, "squire_selections");
	}

	private static SelectionService createFromNbt(NbtCompound nbt) {
		SelectionService store = new SelectionService();
		store.readNbt(nbt);
		return store;
	}

	/** 测试/诊断用：从 NBT 重建一个独立实例。 */
	public static SelectionService createFromNbtPublic(NbtCompound nbt) {
		return createFromNbt(nbt);
	}

	// ------------------------------------------------------------------ 读写

	public Optional<Selection> of(UUID ownerId) {
		return Optional.ofNullable(selections.get(ownerId));
	}

	/** 已完成且可用于编辑的区域，没有就是 empty。 */
	public Optional<BoundedRegion> regionOf(UUID ownerId) {
		return of(ownerId).flatMap(Selection::region);
	}

	/**
	 * 设定一个角点。换维度会清掉另一个角点——跨维度的"区域"没有意义，静默保留
	 * 只会让玩家以为自己选中了脚下这片地方。
	 */
	public Selection setCorner(ServerPlayerEntity player, boolean first, BlockPos pos) {
		ensureWritable();
		RegistryKey<net.minecraft.world.World> dimension =
			player.getWorld().getRegistryKey();
		Selection existing = selections.get(player.getUuid());
		BlockPos otherCorner = null;
		if (existing != null && existing.dimension().equals(dimension)) {
			otherCorner = first ? existing.pos2() : existing.pos1();
		}
		Selection updated = first
			? new Selection(player.getUuid(), dimension, pos.toImmutable(), otherCorner)
			: new Selection(player.getUuid(), dimension, otherCorner, pos.toImmutable());
		selections.put(player.getUuid(), updated);
		markDirty();
		return updated;
	}

	public void clear(UUID ownerId) {
		ensureWritable();
		if (selections.remove(ownerId) != null) {
			markDirty();
		}
	}

	/**
	 * 玩家注视的方块，用于不带坐标的 {@code /squire selection pos1}。
	 * 没有命中方块时退回玩家脚下那一格，并由调用方把结果回显出去。
	 */
	public static BlockPos lookedAtBlock(ServerPlayerEntity player) {
		Vec3d eye = player.getCameraPosVec(1.0f);
		Vec3d reach = eye.add(player.getRotationVec(1.0f).multiply(LOOK_DISTANCE));
		HitResult hit = player.getWorld().raycast(new RaycastContext(eye, reach,
			RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			return blockHit.getBlockPos().toImmutable();
		}
		return player.getBlockPos().toImmutable();
	}

	// ------------------------------------------------------------------ nbt

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
		NbtList list = new NbtList();
		for (Selection selection : selections.values()) {
			NbtCompound entry = new NbtCompound();
			entry.putUuid("ownerId", selection.ownerId());
			entry.putString("dimension", selection.dimensionId());
			if (selection.pos1() != null) {
				entry.putLong("pos1", selection.pos1().asLong());
			}
			if (selection.pos2() != null) {
				entry.putLong("pos2", selection.pos2().asLong());
			}
			list.add(entry);
		}
		nbt.put(KEY_SELECTIONS, list);
		return nbt;
	}

	private void readNbt(NbtCompound nbt) {
		int version = nbt.contains(KEY_SCHEMA_VERSION) ? nbt.getInt(KEY_SCHEMA_VERSION) : 0;
		if (version > CURRENT_SCHEMA_VERSION) {
			failClosed = true;
			LOG.error("[squire-selection] schemaVersion {} is newer than supported {}; "
				+ "selections loaded READ-ONLY", version, CURRENT_SCHEMA_VERSION);
			return;
		}
		NbtList list = nbt.getList(KEY_SELECTIONS, NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			try {
				NbtCompound entry = list.getCompound(i);
				UUID ownerId = entry.getUuid("ownerId");
				selections.put(ownerId, new Selection(ownerId,
					RegistryKey.of(RegistryKeys.WORLD,
						new Identifier(entry.getString("dimension"))),
					entry.contains("pos1") ? BlockPos.fromLong(entry.getLong("pos1")) : null,
					entry.contains("pos2") ? BlockPos.fromLong(entry.getLong("pos2")) : null));
			} catch (RuntimeException e) {
				LOG.warn("[squire-selection] skipped corrupt row: {}", e.toString());
			}
		}
	}

	private void ensureWritable() {
		if (failClosed) {
			throw new IllegalStateException(
				"selection schema is newer than this mod; writes are disabled");
		}
	}

	@Override
	public boolean isDirty() {
		return !failClosed && super.isDirty();
	}

	public boolean isFailClosed() {
		return failClosed;
	}
}
