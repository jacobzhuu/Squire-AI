package dev.squire.client.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecallBellAppearanceTest {
    private static final Path MODELS = Path.of("src/main/resources/assets/squire/models/item");

    private JsonObject model(String name) throws Exception {
        return JsonParser.parseString(Files.readString(MODELS.resolve(name + ".json"))).getAsJsonObject();
    }

    @Test void allSixteenStatesSelectTheirOwnModelAfterMinecraftClamping() throws Exception {
        var overrides = model("recall_bell").getAsJsonArray("overrides");
        String[] professions = {"engineer", "", "guard", "engineer"};
        for (int state = 0; state < 4; state++) {
            for (int tier = 0; tier < 4; tier++) {
                float value = Math.max(0, Math.min(1, RecallBellAppearance.state(state != 0, professions[state])));
                float quality = Math.max(0, Math.min(1, RecallBellAppearance.quality(tier)));
                String selected = "base";
                // Vanilla resolves the last matching override, comparing each threshold with >=.
                for (var entry : overrides) {
                    var override = entry.getAsJsonObject();
                    var predicate = override.getAsJsonObject("predicate");
                    if (value >= predicate.get("squire:bell_state").getAsFloat()
                            && quality >= predicate.get("squire:bell_quality").getAsFloat()) {
                        selected = override.get("model").getAsString();
                    }
                }
                assertEquals("squire:item/recall_bell_" + state + "_" + tier, selected);
            }
        }
        assertEquals(0.25f, RecallBellAppearance.state(true, "unknown"));
    }

    @Test void allVariantsAndFallbackUseTheSameBoundedGeometry() throws Exception {
        var body = model("recall_bell_body");
        assertEquals("minecraft:block/block", body.get("parent").getAsString());
        assertEquals("squire:item/recall_bell_body", model("recall_bell").get("parent").getAsString());
        var textures = body.getAsJsonObject("textures");
        for (int state = 0; state < 4; state++) {
            for (int tier = 0; tier < 4; tier++) {
                var variant = model("recall_bell_" + state + "_" + tier);
                assertEquals("squire:item/recall_bell_body", variant.get("parent").getAsString());
                assertFalse(variant.has("elements"), "Variants must not reintroduce sprite slabs");
                assertFalse(variant.has("display"), "All variants must share hand transforms");
                assertFalse(variant.has("overrides"), "Avoid override cycles");
                for (var entry : variant.getAsJsonObject("textures").entrySet()) {
                    assertTrue(textures.has(entry.getKey()), "Unknown material: " + entry.getKey());
                }
            }
        }

        boolean front = false, back = false;
        for (var element : body.getAsJsonArray("elements")) {
            var e = element.getAsJsonObject();
            for (int axis = 0; axis < 3; axis++) {
                double from = e.getAsJsonArray("from").get(axis).getAsDouble();
                double to = e.getAsJsonArray("to").get(axis).getAsDouble();
                assertTrue(Double.isFinite(from) && Double.isFinite(to));
                assertTrue(from >= 0 && to <= 16 && to - from >= 0.5,
                        "Geometry must fit one item and have nondegenerate thickness");
            }
            var faces = e.getAsJsonObject("faces");
            assertEquals(6, faces.size(), "Solid geometry must be closed on both hands");
            for (var faceEntry : faces.entrySet()) {
                var face = faceEntry.getValue().getAsJsonObject();
                String texture = face.get("texture").getAsString();
                assertTrue(textures.has(texture.substring(1)), "Unresolved texture: " + texture);
                var uv = face.getAsJsonArray("uv");
                assertEquals(4, uv.size());
                for (var coordinate : uv) {
                    double value = coordinate.getAsDouble();
                    assertTrue(Double.isFinite(value) && value >= 0 && value <= 16,
                            "UV must stay inside its atlas sprite");
                }
                assertTrue(uv.get(2).getAsDouble() > uv.get(0).getAsDouble());
                assertTrue(uv.get(3).getAsDouble() > uv.get(1).getAsDouble());
                if (texture.equals("#badge")) {
                    front |= e.getAsJsonArray("to").get(2).getAsDouble() <= 4;
                    back |= e.getAsJsonArray("from").get(2).getAsDouble() >= 12;
                }
            }
        }
        assertTrue(front && back, "Profession badge must be visible from either side");
    }

    @Test void handTransformsAreSharedAndMirroredAtVanillaItemScale() throws Exception {
        var display = model("recall_bell_body").getAsJsonObject("display");
        for (String perspective : new String[]{"firstperson", "thirdperson"}) {
            var right = display.getAsJsonObject(perspective + "_righthand");
            var left = display.getAsJsonObject(perspective + "_lefthand");
            assertEquals(right.get("scale"), left.get("scale"));
            assertEquals(right.get("translation"), left.get("translation"));
            for (var scale : right.getAsJsonArray("scale")) {
                assertTrue(scale.getAsFloat() > 0 && scale.getAsFloat() <= 0.68f);
            }
            for (int axis : new int[]{1, 2}) {
                assertEquals(right.getAsJsonArray("rotation").get(axis).getAsFloat(),
                        -left.getAsJsonArray("rotation").get(axis).getAsFloat(), 0.001f);
            }
        }
        assertNotEquals(model("recall_bell_2_0").getAsJsonObject("textures").get("badge"),
                model("recall_bell_3_0").getAsJsonObject("textures").get("badge"));
        var qualities = new java.util.HashSet<String>();
        for (int tier = 0; tier < 4; tier++) {
            qualities.add(model("recall_bell_0_" + tier).getAsJsonObject("textures").get("quality").getAsString());
        }
        assertEquals(4, qualities.size());
    }
}
