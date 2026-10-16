package com.wenkrang.famara.render.stage;

import com.wenkrang.famara.render.RenderTemp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class MaskColorStageTest {
    @org.junit.jupiter.api.Test
    void clientCherryTextureColorStaysPinkUnderBrightTopFaceLighting() {
        RenderTemp rt = new RenderTemp();
        rt.x = 1;
        rt.hitFace = org.bukkit.block.BlockFace.UP;
        rt.color.set(229, 172, 194);
        new BlockFaceLightStage().render(null, rt);
        rt.color.applyLightFactor(0.3f, 1.0f);
        new MaskColorStage().render(null, rt);
        assertEquals(251, rt.color.getR());
        assertEquals(194, rt.color.getG());
        assertEquals(216, rt.color.getB());
        assertNotEquals(0xffffff, rt.color.toARGB() & 0xffffff);
    }

    @ParameterizedTest
    @CsvSource({
            "255,0,255,0,253,0,253", "255,0,255,1,255,2,255",
            "255,0,0,0,253,0,0", "255,0,0,1,255,2,2",
            "0,255,0,0,0,253,0", "0,255,0,1,2,255,2",
            "0,0,255,0,0,0,253", "0,0,255,1,2,2,255",
            "0,0,0,0,0,0,0", "0,0,0,1,2,2,2",
            "255,255,255,0,253,253,253", "255,255,255,1,255,255,255",
            "100,110,120,0,98,108,118", "100,110,120,1,102,112,122"
    })
    void clampsEachChannelWithoutTurningWholePixelBlackOrWhite(int r, int g, int b, int x, int er, int eg, int eb) {
        RenderTemp rt = new RenderTemp();
        rt.x = x; rt.y = 0;
        rt.color.set(r, g, b);
        rt.failedBlocks.add("test:missing-color");
        new MaskColorStage().render(null, rt);
        assertEquals(er, rt.color.getR());
        assertEquals(eg, rt.color.getG());
        assertEquals(eb, rt.color.getB());
        assertEquals("test:missing-color", rt.failedBlocks.getFirst());
    }
}
