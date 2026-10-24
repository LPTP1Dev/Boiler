package net.somewhatcity.boiler.core.dj;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DjViewTest {
    private static final int CYAN = new Color(53, 214, 226).getRGB();
    private static DjEngine.DeckView deck(long in, long out, boolean active, double tempo) {
        return new DjEngine.DeckView("Loop.wav", 96000, 480000, false, tempo, 1,
                List.of(0., 0., 0.), in, out, active, 0, "", DjWaveform.EMPTY, 0, DjBeatGrid.UNKNOWN, false);
    }
    private static BufferedImage draw(long in, long out, boolean active, double tempo) {
        var image = new BufferedImage(256, 128, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            DjView.draw(graphics, 256, 128, new DjEngine.View(List.of(deck(in, out, active, tempo),
                    deck(-1, -1, false, 0)), .5, 1, -1), new String[]{"", ""});
        } finally { graphics.dispose(); }
        return image;
    }
    @Test void loopSelectionShowsShadingBoundariesAndDifferentActiveStateOnlyOnItsDeck() {
        var empty = draw(-1, -1, false, 0);
        var active = draw(72000, 120000, true, 0);
        var inactive = draw(72000, 120000, false, 0);
        assertEquals(CYAN, active.getRGB(86, 15)); // IN
        assertEquals(CYAN, active.getRGB(170, 15)); // OUT
        assertNotEquals(empty.getRGB(100, 15), active.getRGB(100, 15));
        assertNotEquals(active.getRGB(100, 15), inactive.getRGB(100, 15));
        assertNotEquals(empty.getRGB(86, 15), inactive.getRGB(86, 15));
        assertEquals(empty.getRGB(60, 15), active.getRGB(60, 15));
        for (int y = 25; y < 36; y++) for (int x = 3; x < 253; x++)
            assertEquals(empty.getRGB(x, y), active.getRGB(x, y), "Other deck stays unchanged");
        assertEquals(net.somewhatcity.boiler.core.smarttv.SmartTvStyle.WHITE.getRGB(), active.getRGB(128, 15));
    }
    @Test void pendingInAndTempoAdjustedOrOffscreenLoopsStayWithinTheWaveform() {
        var pending = draw(72000, -1, false, 0);
        var empty = draw(-1, -1, false, 0);
        assertNotEquals(empty.getRGB(86, 15), pending.getRGB(86, 15));
        assertEquals(empty.getRGB(100, 15), pending.getRGB(100, 15));
        var tempo = draw(72000, 120000, true, 16);
        assertEquals(CYAN, tempo.getRGB(92, 15));
        assertEquals(CYAN, tempo.getRGB(164, 15));
        var covering = draw(0, 240000, true, 0);
        assertNotEquals(empty.getRGB(3, 15), covering.getRGB(3, 15));
        assertNotEquals(empty.getRGB(252, 15), covering.getRGB(252, 15));
        var outside = draw(240000, 300000, true, 0);
        for (int y = 12; y < 23; y++) for (int x = 3; x < 253; x++)
            assertEquals(empty.getRGB(x, y), outside.getRGB(x, y));
    }
}
