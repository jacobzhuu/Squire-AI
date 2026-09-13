package dev.squire.server.blueprint;

import java.util.*;
import com.google.gson.*;

/** Explicit block/region bindings for imported content; no name-based material guessing. */
final class ImportMaterialBindings {
    private ImportMaterialBindings() { }
    static Blueprint apply(Blueprint blueprint, JsonObject descriptor) {
        JsonArray bindings = descriptor.getAsJsonArray("materialBindings");
        if (bindings == null || bindings.isEmpty()) return blueprint;
        List<BlueprintStep> steps = new ArrayList<>();
        for (BlueprintStep step : blueprint.steps()) {
            BlueprintStep mapped = step;
            if (!step.negative() && !step.usesMaterial()) for (JsonElement raw : bindings) {
                JsonObject b = raw.getAsJsonObject();
                if (!step.blockId().equals(b.get("block").getAsString())) continue;
                if (b.has("from")) {
                    JsonArray from = b.getAsJsonArray("from"), to = b.getAsJsonArray("to");
                    if (step.x1() < from.get(0).getAsInt() || step.y1() < from.get(1).getAsInt() || step.z1() < from.get(2).getAsInt()
                        || step.x2() > to.get(0).getAsInt() || step.y2() > to.get(1).getAsInt() || step.z2() > to.get(2).getAsInt()) continue;
                }
                String slot = b.get("slot").getAsString(), variant = b.get("variant").getAsString();
                var metadata = blueprint.materialSlots().stream().filter(s -> s.id().equals(slot)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(blueprint.id() + ": unknown binding slot " + slot));
                if (!metadata.requiredVariants().contains(variant)) throw new IllegalArgumentException("undeclared binding variant " + variant);
                mapped = BlueprintStep.materialState(step.order(), step.x1(), step.y1(), step.z1(), step.x2(), step.y2(), step.z2(),
                    slot, variant, step.what(), step.optional(), step.properties());
                break;
            }
            steps.add(mapped);
        }
        return new Blueprint(blueprint.id(), blueprint.displayName(), blueprint.tier(), blueprint.category(), blueprint.width(),
            blueprint.height(), blueprint.depth(), steps, blueprint.requiredAbilities(), blueprint.materialSlots(), blueprint.metadata());
    }
}
