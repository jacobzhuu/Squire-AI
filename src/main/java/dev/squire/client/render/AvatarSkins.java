package dev.squire.client.render;

/** Stable resource paths; profession is synchronized separately for each companion. */
public final class AvatarSkins {
    private AvatarSkins() {}

    public static String texturePath(String profession) {
        String skin = "guard".equals(profession) ? "guard"
            : "engineer".equals(profession) ? "engineer" : "common";
        return "textures/entity/avatar/" + skin + ".png";
    }
}
