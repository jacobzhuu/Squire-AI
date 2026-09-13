package dev.squire.server.profession;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;

/** Data-pack configured ten-level construction growth; level-one full cost, up to 50% material savings. */
public final class EngineerProgression {
    private final int[] waste, lookAhead;
    private final double[] efficiency, movement, placementInterval;
    private static volatile EngineerProgression current = bundled();
    private EngineerProgression(int[] waste, double[] efficiency, double[] movement, int[] lookAhead, double[] placementInterval) {
        this.waste = waste.clone(); this.efficiency = efficiency.clone(); this.movement = movement.clone(); this.lookAhead = lookAhead.clone();
        this.placementInterval = placementInterval.clone();
    }
    public static EngineerProgression current() { return current; }
    private static int index(int level) { return Math.max(0, Math.min(9, level - 1)); }
    public int wasteBasisPoints(int level) { return waste[index(level)]; }
    public String materialAdjustmentLabel(int level) {
        int rate = wasteBasisPoints(level);
        return (rate <= 0 ? "省料 " : "损耗 ") + Math.abs(rate) / 100.0 + "%";
    }
    public double efficiency(int level) { return efficiency[index(level)]; }
    public double movement(int level) { return movement[index(level)]; }
    public int lookAhead(int level) { return lookAhead[index(level)]; }
    public double placementInterval(int level) { return placementInterval[index(level)]; }
    /** Preserve fractional cadence, but do not bank missed work while walking or resupplying. */
    public static double nextWorkTick(long tick, double previous, double interval) {
        return (previous > tick - 1.0 ? previous : tick) + interval;
    }
    public static EngineerProgression parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        int schema = o.get("schemaVersion").getAsInt();
        if (schema != 1 && schema != 2) throw new IllegalArgumentException("engineer progression schema");
        String costKey = schema == 2 ? "savingBasisPoints" : "wasteBasisPoints";
        for (String key : new String[]{costKey, "efficiency", "movement", "lookAhead"})
            if (o.getAsJsonArray(key).size() != 10) throw new IllegalArgumentException(key + " needs ten levels");
        int[] w = new int[10], a = new int[10]; double[] e = new double[10], m = new double[10], interval = new double[10];
        if (o.has("placementIntervalTicks") && o.getAsJsonArray("placementIntervalTicks").size() != 10) throw new IllegalArgumentException("placement interval needs ten levels");
        for (int i = 0; i < 10; i++) {
            w[i] = o.getAsJsonArray(costKey).get(i).getAsInt() * (schema == 2 ? -1 : 1);
            a[i] = o.getAsJsonArray("lookAhead").get(i).getAsInt();
            e[i] = o.getAsJsonArray("efficiency").get(i).getAsDouble();
            m[i] = o.getAsJsonArray("movement").get(i).getAsDouble();
            interval[i] = o.has("placementIntervalTicks") ? o.getAsJsonArray("placementIntervalTicks").get(i).getAsDouble() : 12 / e[i];
            if (!Double.isFinite(interval[i]) || interval[i] < 1 || interval[i] > 40 || i > 0 && interval[i] > interval[i-1]) throw new IllegalArgumentException("invalid placement interval");
            if (w[i] < (schema == 2 ? -5000 : 0) || w[i] > (schema == 2 ? 0 : 10000) || a[i] < 1 || a[i] > 256 || !Double.isFinite(e[i]) || e[i] < 1 || e[i] > 4
                    || !Double.isFinite(m[i]) || m[i] < 1 || m[i] > 1.45
                    || i > 0 && (w[i] > w[i-1] || a[i] < a[i-1] || e[i] < e[i-1] || m[i] < m[i-1]))
                throw new IllegalArgumentException("invalid growth at level " + (i + 1));
        }
        return new EngineerProgression(w, e, m, a, interval);
    }
    public static void reload(dev.squire.server.blueprint.BlueprintImporter.ResourceProvider resources) {
        try (var input = resources.open(new net.minecraft.util.Identifier("squire:engineer_progression.json"))) {
            current = parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception bad) {
            org.slf4j.LoggerFactory.getLogger(EngineerProgression.class).warn("Keeping previous Engineer growth: {}", bad.toString());
        }
    }
    private static EngineerProgression bundled() {
        try (var input = EngineerProgression.class.getClassLoader().getResourceAsStream("data/squire/engineer_progression.json")) {
            if (input == null) throw new IllegalStateException("missing engineer progression");
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException bad) { throw new IllegalStateException(bad); }
    }
}
