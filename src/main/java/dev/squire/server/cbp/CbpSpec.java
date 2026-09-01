package dev.squire.server.cbp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.util.math.BlockPos;

/**
 * A command-block project description (spec §50 step 1: CBP Spec). Produced by
 * the agent for an owner, but NEVER executed directly — it only becomes blocks
 * after {@link CbpMaterializer} validates it, previews the impact and obtains
 * the owner's explicit confirmation.
 *
 * <p>Validation here is world-independent (shape, caps, duplicates). Workspace
 * containment is checked by the materializer, which knows the owner's area.</p>
 */
public final class CbpSpec {

	/** Hard cap on blocks per project — keeps preview/undo/review human-sized. */
	public static final int MAX_BLOCKS = 64;

	/** The command-block family; only these carry a baked command. */
	public static final Set<String> BLOCK_TYPES = Set.of(
		"minecraft:command_block", "minecraft:chain_command_block",
		"minecraft:repeating_command_block");

	/**
	 * Supporting parts a real, wireable device needs (方案 H2：红石连接和说明牌)。
	 * Deliberately tiny: a sensor, a visible output, a manual switch, a label and a
	 * mounting block. These NEVER carry a command.
	 */
	public static final Set<String> SUPPORT_BLOCK_TYPES = Set.of(
		"minecraft:daylight_detector", "minecraft:redstone_lamp", "minecraft:lever",
		"minecraft:oak_sign", "minecraft:stone");

	public static boolean isCommandBlock(String blockType) {
		return BLOCK_TYPES.contains(blockType);
	}

	/**
	 * One block of the project.
	 *
	 * @param command    structured command; only command blocks may carry one
	 * @param auto       command block "always active" flag
	 * @param conditional command block conditional flag (chains on the one behind it)
	 * @param facing     orientation, or null for the block's default state
	 * @param signText   label written on a sign, or null
	 */
	public record Entry(BlockPos pos, String blockType, String command, boolean auto,
			boolean conditional, net.minecraft.util.math.Direction facing,
			String signText) {

		/** Plain command-block entry (the pre-H2 shape). */
		public Entry(BlockPos pos, String blockType, String command, boolean auto) {
			this(pos, blockType, command, auto, false, null, null);
		}

		public boolean isCommandBlock() {
			return CbpSpec.isCommandBlock(blockType);
		}
	}

	private final UUID ownerId;
	private final UUID agentId;
	private final String name;
	private final List<Entry> entries;

	private CbpSpec(UUID ownerId, UUID agentId, String name, List<Entry> entries) {
		this.ownerId = ownerId;
		this.agentId = agentId;
		this.name = name;
		this.entries = List.copyOf(entries);
	}

	public UUID ownerId() {
		return ownerId;
	}

	public UUID agentId() {
		return agentId;
	}

	public String name() {
		return name;
	}

	public List<Entry> entries() {
		return entries;
	}

	public static Builder builder(UUID ownerId, UUID agentId, String name) {
		return new Builder(ownerId, agentId, name);
	}

	public static final class Builder {
		private final UUID ownerId;
		private final UUID agentId;
		private final String name;
		private final List<Entry> entries = new ArrayList<>();

		private Builder(UUID ownerId, UUID agentId, String name) {
			if (ownerId == null || agentId == null || name == null || name.isBlank()) {
				throw new IllegalArgumentException("owner/agent/name required");
			}
			this.ownerId = ownerId;
			this.agentId = agentId;
			this.name = name.trim();
		}

		public Builder entry(BlockPos pos, String blockType, String command,
				boolean auto) {
			entries.add(new Entry(pos.toImmutable(), blockType, command, auto));
			return this;
		}

		/** Full entry: orientation, conditional chaining and sign labels (方案 H2). */
		public Builder entry(BlockPos pos, String blockType, String command,
				boolean auto, boolean conditional,
				net.minecraft.util.math.Direction facing, String signText) {
			entries.add(new Entry(pos.toImmutable(), blockType, command, auto,
				conditional, facing, signText));
			return this;
		}

		/** A supporting redstone/label block; carries no command by construction. */
		public Builder support(BlockPos pos, String blockType,
				net.minecraft.util.math.Direction facing, String signText) {
			entries.add(new Entry(pos.toImmutable(), blockType, null, false, false,
				facing, signText));
			return this;
		}

		public CbpSpec build() {
			return new CbpSpec(ownerId, agentId, name, entries);
		}
	}

	/**
	 * World-independent validation.
	 *
	 * @return every problem found; empty list means acceptable
	 */
	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		if (entries.isEmpty()) {
			errors.add("spec has no entries");
			return errors;
		}
		if (entries.size() > MAX_BLOCKS) {
			errors.add("spec has " + entries.size() + " entries (max " + MAX_BLOCKS + ")");
		}
		Set<BlockPos> seen = new LinkedHashSet<>();
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			String where = "entry[" + i + "]";
			boolean commandBlock = BLOCK_TYPES.contains(e.blockType());
			if (!commandBlock && !SUPPORT_BLOCK_TYPES.contains(e.blockType())) {
				errors.add(where + ": block type '" + e.blockType()
					+ "' is neither a command block nor a whitelisted support part");
			} else if (commandBlock) {
				CbpCommandPolicy.validate(e.command())
					.ifPresent(reason -> errors.add(where + ": " + reason));
			} else if (e.command() != null && !e.command().isBlank()) {
				// a support block carrying a command would be a command block in disguise
				errors.add(where + ": support block '" + e.blockType()
					+ "' must not carry a command");
			}
			if (e.signText() != null && e.signText().length() > 60) {
				errors.add(where + ": sign text exceeds 60 characters");
			}
			if (!seen.add(e.pos())) {
				errors.add(where + ": duplicate position " + e.pos().toShortString());
			}
		}
		return errors;
	}

	/** How many of the entries are real command blocks (preview + capability budget). */
	public int commandBlockCount() {
		return (int) entries.stream().filter(Entry::isCommandBlock).count();
	}

	/** Bounding box of all entry positions. */
	public dev.squire.server.world.BoundedRegion footprint() {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (Entry e : entries) {
			minX = Math.min(minX, e.pos().getX());
			minY = Math.min(minY, e.pos().getY());
			minZ = Math.min(minZ, e.pos().getZ());
			maxX = Math.max(maxX, e.pos().getX());
			maxY = Math.max(maxY, e.pos().getY());
			maxZ = Math.max(maxZ, e.pos().getZ());
		}
		return dev.squire.server.world.BoundedRegion.ofCorners(
			minX, minY, minZ, maxX, maxY, maxZ);
	}
}
