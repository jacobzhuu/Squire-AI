package dev.squire.server.blueprint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 一份蓝图<b>被放在世界某处</b>的这件事：谁的、哪份蓝图、落在哪、朝哪、进行到哪一步。
 *
 * <p>把它和 {@link Blueprint} 分开，是因为蓝图是共享的只读数据，而摆放是每个玩家
 * 自己的、会变状态、要跨重启存活的东西。施工任务只带一个 {@code placementId}，
 * 重启后靠它把形状重新算出来——任务参数里绝不塞几百个坐标。</p>
 */
public final class BlueprintPlacement {

	/** 摆放的生命周期。 */
	public enum State {
		/** 只有粒子轮廓，世界零改变。 */
		GHOST,
		/** 材料齐了，等一句「开工」。 */
		READY,
		/** 正在施工（挖除或放置）。 */
		BUILDING,
		/** 施工验收通过。 */
		DONE,
		/** 玩家取消。 */
		CANCELLED
	}

	public final UUID placementId;
	public final UUID ownerId;
	public final UUID agentId;
	public String blueprintId;
	public final String dimensionId;
	public BlockPos origin;
	public Direction facing;
	public final long createdTick;
	private final Map<String, String> materials = new LinkedHashMap<>();

	private State state = State.GHOST;

	public BlueprintPlacement(UUID placementId, UUID ownerId, UUID agentId,
			String blueprintId, String dimensionId, BlockPos origin, Direction facing,
			long createdTick) {
		this(placementId, ownerId, agentId, blueprintId, dimensionId, origin, facing,
			createdTick, Map.of());
	}

	public BlueprintPlacement(UUID placementId, UUID ownerId, UUID agentId,
			String blueprintId, String dimensionId, BlockPos origin, Direction facing,
			long createdTick, Map<String, String> materials) {
		this.placementId = placementId;
		this.ownerId = ownerId;
		this.agentId = agentId;
		this.blueprintId = blueprintId;
		this.dimensionId = dimensionId;
		this.origin = origin.toImmutable();
		this.facing = facing == null || facing.getAxis().isVertical()
			? Direction.NORTH : facing;
		this.createdTick = createdTick;
		if (materials != null) this.materials.putAll(materials);
	}

	public State state() {
		return state;
	}

	public void setState(State next) {
		this.state = next == null ? State.GHOST : next;
	}

	public Map<String, String> materials() {
		return Map.copyOf(materials);
	}

	public boolean setMaterial(String slotId, String familyId) {
		if ((state != State.GHOST && state != State.READY) || slotId == null
				|| slotId.isBlank() || familyId == null || familyId.isBlank()) return false;
		materials.put(slotId, familyId);
		state = State.GHOST;
		return true;
	}

	public boolean resetMaterials(Map<String, String> defaults) {
		if (state != State.GHOST && state != State.READY) return false;
		materials.clear();
		if (defaults != null) materials.putAll(defaults);
		state = State.GHOST;
		return true;
	}

	/** Adjust an uncommitted ghost without replacing its persistent identity. */
	public boolean relocate(BlockPos nextOrigin, Direction nextFacing) {
		if (state != State.GHOST && state != State.READY) {
			return false;
		}
		this.origin = nextOrigin.toImmutable();
		this.facing = nextFacing == null || nextFacing.getAxis().isVertical()
			? Direction.NORTH : nextFacing;
		this.state = State.GHOST;
		return true;
	}

	/** Replace the design of an uncommitted ghost (used by the house configurator). */
	public boolean changeBlueprint(String nextBlueprintId) {
		if ((state != State.GHOST && state != State.READY)
				|| nextBlueprintId == null || nextBlueprintId.isBlank()) {
			return false;
		}
		this.blueprintId = nextBlueprintId;
		this.state = State.GHOST;
		return true;
	}

	/** 还在等玩家动作或正在施工——已完成和已取消的摆放不再画幽灵、不再接受开工。 */
	public boolean active() {
		return state == State.GHOST || state == State.READY || state == State.BUILDING;
	}

	@Override
	public String toString() {
		return blueprintId + "@" + origin.toShortString() + " " + facing + " " + state;
	}
}
