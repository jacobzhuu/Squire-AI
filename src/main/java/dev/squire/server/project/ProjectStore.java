package dev.squire.server.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code projects.json}：工程跨重启存活。
 *
 * <p>形状照 {@code GoalStateStore} / {@code BlueprintPlacementStore} 抄：版本字段、
 * tmp + 原子改名、坏行只跳过自己、未来版本 fail-closed 并停止写入。</p>
 *
 * <p>存的是<b>阶段的状态</b>，不是阶段编译出来的任务 id：任务本身由
 * {@code TaskStateStore} 负责恢复，而恢复之后的任务 id 会变。重启后正在跑的那个阶段
 * 退回 PENDING 重新编译一次——阶段的成功条件全都读真实世界，所以重来一遍是幂等的。</p>
 */
public final class ProjectStore {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(ProjectStore.class);
	private static final int VERSION = 3;

	private final java.util.function.Supplier<Path> fileSupplier;
	private boolean writable = true;

	public ProjectStore(java.util.function.Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	public boolean isWritable() {
		return writable;
	}

	public synchronized void save(Collection<Project> projects) {
		if (!writable) {
			return;
		}
		try {
			Path file = fileSupplier.get();
			if (file == null) {
				return;
			}
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", VERSION);
			JsonArray array = new JsonArray();
			for (Project project : projects) {
				array.add(write(project));
			}
			root.add("projects", array);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			LOG.warn("[project] save failed: {}", e.toString());
		}
	}

	public synchronized List<Project> load() {
		List<Project> out = new ArrayList<>();
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) {
				return out;
			}
			JsonObject root = JsonParser.parseString(Files.readString(file))
				.getAsJsonObject();
			if (root.has("version") && root.get("version").getAsInt() > VERSION) {
				writable = false;
				LOG.error("[project] future schema {}; refusing recovery and writes",
					root.get("version").getAsInt());
				return out;
			}
			int sourceVersion = root.has("version") ? root.get("version").getAsInt() : 1;
			JsonArray array = root.getAsJsonArray("projects");
			if (array == null) {
				return out;
			}
			for (JsonElement element : array) {
				try {
					out.add(read(element.getAsJsonObject(), sourceVersion));
				} catch (RuntimeException bad) {
					LOG.warn("[project] skipped corrupt row: {}", bad.toString());
				}
			}
		} catch (Exception e) {
			LOG.warn("[project] load failed: {}", e.toString());
		}
		return out;
	}

	// ------------------------------------------------------------------ rows

	static JsonObject write(Project p) {
		JsonObject o = new JsonObject();
		o.addProperty("projectId", p.projectId.toString());
		o.addProperty("ownerId", p.ownerId.toString());
		o.addProperty("name", p.name);
		o.addProperty("blueprintId", p.blueprintId);
		o.addProperty("placementId", p.placementId.toString());
		o.addProperty("dimension", p.dimensionId);
		o.addProperty("createdTick", p.createdTick);
		o.addProperty("state", p.state().name());
		o.addProperty("supplyPrepared", p.supplyPrepared());
		o.add("ownerSupply", writeSupply(p.ownerSupply()));
		o.add("agentSupply", writeSupply(p.agentSupply()));
		JsonArray stages = new JsonArray();
		for (Stage stage : p.stages()) {
			JsonObject s = new JsonObject();
			s.addProperty("stageId", stage.stageId.toString());
			s.addProperty("kind", stage.kind.name());
			s.addProperty("state", stage.state().name());
			if (stage.assignedAgentId() != null) {
				s.addProperty("agentId", stage.assignedAgentId().toString());
			}
			if (stage.blockedReason() != null) {
				s.addProperty("blockedReason", stage.blockedReason());
				s.addProperty("blockerCode", stage.blockerCode().name());
			}
			stages.add(s);
		}
		o.add("stages", stages);
		return o;
	}

	static Project read(JsonObject o) {
		return read(o, VERSION);
	}

	static Project read(JsonObject o, int sourceVersion) {
		List<Stage> stages = new ArrayList<>();
		JsonArray raw = o.getAsJsonArray("stages");
		if (raw != null) {
			for (JsonElement element : raw) {
				JsonObject s = element.getAsJsonObject();
				Stage stage = new Stage(UUID.fromString(s.get("stageId").getAsString()),
					Stage.Kind.valueOf(s.get("kind").getAsString()));
				Stage.State state = stateOf(s.has("state") ? s.get("state").getAsString()
					: null);
				// 正在跑的阶段退回 PENDING：任务恢复之后 id 会变，重新编译一次更简单，
				// 而且每个阶段的成功条件都读真实世界，重来是幂等的。
				stage.setState(state == Stage.State.RUNNING ? Stage.State.PENDING : state);
				if (s.has("agentId")) {
					stage.assignTo(UUID.fromString(s.get("agentId").getAsString()));
				}
				if (state == Stage.State.BLOCKED && s.has("blockedReason")) {
					Stage.BlockerCode code = Stage.BlockerCode.TASK_FAILED;
					if (s.has("blockerCode")) {
						try {
							code = Stage.BlockerCode.valueOf(
								s.get("blockerCode").getAsString());
						} catch (IllegalArgumentException ignored) { }
					}
					stage.block(code, s.get("blockedReason").getAsString());
				}
				stages.add(stage);
			}
		}
		Project project = new Project(
			UUID.fromString(o.get("projectId").getAsString()),
			UUID.fromString(o.get("ownerId").getAsString()),
			o.get("name").getAsString(),
			o.get("blueprintId").getAsString(),
			UUID.fromString(o.get("placementId").getAsString()),
			o.get("dimension").getAsString(),
			o.has("createdTick") ? o.get("createdTick").getAsLong() : 0L,
			stages);
		if (o.has("state")) {
			try {
				project.setState(Project.State.valueOf(o.get("state").getAsString()));
			} catch (IllegalArgumentException unknown) {
				project.setState(Project.State.PAUSED); // 认不出就先停住，别自己跑起来
			}
		}
		boolean prepared = sourceVersion >= 3
			&& (!o.has("supplyPrepared") || o.get("supplyPrepared").getAsBoolean());
		project.restoreSupply(readSupply(o.getAsJsonObject("ownerSupply")),
			readSupply(o.getAsJsonObject("agentSupply")), prepared);
		if (!prepared && project.active()) {
			// A v2 project had no durable escrow.  Never let it resume and consume an
			// unrelated backpack after upgrading; the owner explicitly re-reserves once.
			project.setState(Project.State.PAUSED);
		}
		return project;
	}

	private static JsonObject writeSupply(java.util.Map<net.minecraft.util.Identifier,
			Integer> values) {
		JsonObject out = new JsonObject();
		for (var entry : values.entrySet()) {
			if (entry.getValue() != null && entry.getValue() > 0) {
				out.addProperty(entry.getKey().toString(), entry.getValue());
			}
		}
		return out;
	}

	private static java.util.Map<net.minecraft.util.Identifier, Integer> readSupply(
			JsonObject raw) {
		java.util.Map<net.minecraft.util.Identifier, Integer> out =
			new java.util.LinkedHashMap<>();
		if (raw == null) return out;
		for (var entry : raw.entrySet()) {
			net.minecraft.util.Identifier id = net.minecraft.util.Identifier
				.tryParse(entry.getKey());
			int count = entry.getValue().getAsInt();
			if (id != null && count > 0) out.put(id, count);
		}
		return out;
	}

	private static Stage.State stateOf(String raw) {
		if (raw == null) {
			return Stage.State.PENDING;
		}
		try {
			return Stage.State.valueOf(raw);
		} catch (IllegalArgumentException unknown) {
			return Stage.State.PENDING;
		}
	}
}
