package dev.squire.server.runtime;

import dev.squire.server.blueprint.*;
import dev.squire.server.profession.*;

/** Single authority for catalog locks and actual construction admission. */
public final class EngineerBuildPolicy {
    private EngineerBuildPolicy() { }
    public record Decision(int minLevel, boolean allowed, String reason,
            boolean parametric, boolean rotation, boolean materials) { }
    public record Dimensions(int width, int height, int depth) { }
    public static Dimensions dimensions(Blueprint blueprint) {
        var bounds = blueprint.bounds(net.minecraft.util.math.BlockPos.ORIGIN, net.minecraft.util.math.Direction.NORTH);
        // Descriptor dimensions are a rotation frame, not proof that every step
        // stays inside it (negative-Y shafts and overhanging modules are valid).
        return new Dimensions(Math.max(blueprint.width(), bounds.max().getX() - bounds.min().getX() + 1),
            Math.max(blueprint.height(), bounds.max().getY() - bounds.min().getY() + 1),
            Math.max(blueprint.depth(), bounds.max().getZ() - bounds.min().getZ() + 1));
    }
    public static Decision evaluate(Blueprint blueprint, ProfessionData data, ProfessionConfig config) {
        if (TerrainLeveling.parse(blueprint.id()).isPresent()) {
            boolean allowed = data != null && data.profession() == SquireProfession.ENGINEER && data.level >= 1;
            return new Decision(1, allowed, allowed ? "" : "平整地基需要工程师 Lv1", false, false, false);
        }
        int current = data != null && data.profession() == SquireProfession.ENGINEER ? data.level : 0;
        Dimensions size = dimensions(blueprint);
        // Legacy unlevelled training/test blueprints remain basic skills; published
        // library assets declare positive metadata levels and remain Engineer-only.
        if (current == 0 && blueprint.minEngineerLevel() == 0 && BuildTier.of(blueprint.id()).isBasic()
                && Math.max(size.width(), size.depth()) <= config.maxFootprint(1) && size.height() <= 5)
            return new Decision(0, true, "", false, false, false);
        ProjectSpec spec = ProjectSpec.parse(blueprint.id()).orElse(null);
        int minimum = Math.max(1, blueprint.minEngineerLevel());
		boolean catalogBuilding = blueprint.metadata().tags().contains("catalog")
			|| blueprint.metadata().source().startsWith("https://github.com/gowenrw/keepitlevel_mc_style/");
        if (spec != null) minimum = Math.max(minimum, config.templateMinLevel(spec.template().id()));
        int required = 11;
        for (int level = minimum; level <= 10; level++) {
            ProfessionData candidate = new ProfessionData(); candidate.setProfession(SquireProfession.ENGINEER); candidate.level = level;
            if (Math.max(size.width(), size.depth()) > (catalogBuilding ? 48 : config.maxFootprint(level))) continue;
            if (spec != null && (spec.template().compound() && !candidate.can(ProfessionAbility.ENGINEER_COMPOUND_BLUEPRINT)
                    || !SquireEngineerService.clampToLevel(config, candidate, spec).equals(spec))) continue;
            required = level; break;
        }
        String reason = current == 0 ? "需要工程师职业"
            : required > 10 ? "超出当前配置允许的规模或能力上限"
            : current < required ? "需要工程师 Lv" + required + "，当前 Lv" + current : "";
        return new Decision(required, reason.isEmpty(), reason, spec != null,
            current >= ProfessionAbility.ENGINEER_BLUEPRINT_ROTATION.unlockLevel(),
            !blueprint.materialSlots().isEmpty() && (blueprint.materialSlots().size() == 1
                || current >= ProfessionAbility.ENGINEER_MATERIAL_REGIONS.unlockLevel()));
    }
}
