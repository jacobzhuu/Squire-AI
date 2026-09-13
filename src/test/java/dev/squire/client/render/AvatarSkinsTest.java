package dev.squire.client.render;

import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AvatarSkinsTest {
    @Test void mapsEachProfessionAndFallsBackToCommon() {
        assertEquals("textures/entity/avatar/common.png", AvatarSkins.texturePath(""));
        assertEquals(AvatarSkins.texturePath(""), AvatarSkins.texturePath(null));
        assertEquals(AvatarSkins.texturePath(""), AvatarSkins.texturePath("unknown"));
        assertEquals("textures/entity/avatar/guard.png", AvatarSkins.texturePath("guard"));
        assertEquals("textures/entity/avatar/engineer.png", AvatarSkins.texturePath("engineer"));
    }

    @Test void packagedSkinsHaveOpaqueBaseFacesAndTransparentUnusedRegions() throws Exception {
        for (String profession : new String[]{"", "guard", "engineer"}) {
            var path=Path.of("src/main/resources/assets/squire", AvatarSkins.texturePath(profession));
            var skin=ImageIO.read(path.toFile());
            assertNotNull(skin);
            assertEquals(64, skin.getWidth()); assertEquals(64, skin.getHeight());
            assertTrue(skin.getColorModel().hasAlpha());
            // UV origin, width, height, depth for all six classic model parts.
            for (int[] part : new int[][]{{0,0,8,8,8},{16,16,8,12,4},
                    {40,16,4,12,4},{32,48,4,12,4},{0,16,4,12,4},{16,48,4,12,4}}) {
                int u=part[0],v=part[1],w=part[2],h=part[3],d=part[4];
                for (int y=v; y<v+d+h; y++) {
                    int start=y<v+d ? u+d : u;
                    int end=y<v+d ? u+d+2*w : u+2*(w+d);
                    for (int x=start; x<end; x++)
                        assertEquals(255,skin.getRGB(x,y)>>>24,profession+" base at "+x+","+y);
                }
            }
            assertEquals(0,skin.getRGB(0,0)>>>24);
            // Empty hat face stays transparent, including underneath engineer goggles.
            assertEquals(0,skin.getRGB(43,15)>>>24);
        }
    }
}
