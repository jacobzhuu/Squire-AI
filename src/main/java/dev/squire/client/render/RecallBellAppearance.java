package dev.squire.client.render;

/** Values must remain in [0, 1]: Minecraft clamps registered item predicates. */
public final class RecallBellAppearance {
    private RecallBellAppearance() {}

    public static float state(boolean bound, String profession) {
        if (!bound) return 0.0f;
        if ("guard".equals(profession)) return 0.5f;
        if ("engineer".equals(profession)) return 0.75f;
        return 0.25f;
    }

    public static float quality(int tier) {
        return Math.max(0, Math.min(3, tier)) * 0.25f;
    }
}
