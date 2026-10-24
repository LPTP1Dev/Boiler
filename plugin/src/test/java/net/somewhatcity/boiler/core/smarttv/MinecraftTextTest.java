package net.somewhatcity.boiler.core.smarttv;

import org.bukkit.map.MinecraftFont;
import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftTextTest {
    @Test void originalMinecraftGlyphsAreDrawnWithoutSubpixelColors() {
        BufferedImage image = new BufferedImage(32,16,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            MinecraftText.font(g,Font.PLAIN,8); g.setColor(Color.WHITE);
            MinecraftText.draw(g,"A",0,7);
        } finally { g.dispose(); }
        var glyph = MinecraftFont.Font.getChar('A');
        for (int y=0;y<glyph.getHeight();y++) for (int x=0;x<glyph.getWidth();x++)
            assertEquals(glyph.get(y,x) ? Color.WHITE.getRGB() : 0,image.getRGB(x,y));
        for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++)
            assertTrue(image.getRGB(x,y) == 0 || image.getRGB(x,y) == Color.WHITE.getRGB());
    }

    @Test void everyScaleIsAnExactIntegerExpansionOfTheSameBitmap() {
        for (int style : new int[]{Font.PLAIN,Font.BOLD}) {
            BufferedImage original = render(style,1);
            for (int scale=2;scale<=5;scale++) {
                BufferedImage enlarged = render(style,scale);
                for (int y=0;y<enlarged.getHeight();y++) for (int x=0;x<enlarged.getWidth();x++)
                    assertEquals(original.getRGB(x/scale,y/scale),enlarged.getRGB(x,y),"Non-integer glyph scaling");
            }
        }
    }

    @Test void fontRequestsAreQuantizedAndTruncationAlwaysUsesRealGlyphWidths() {
        BufferedImage image = new BufferedImage(256,32,BufferedImage.TYPE_INT_ARGB); Graphics2D g = image.createGraphics();
        try {
            for (float request : new float[]{0,4,7.5f,8,9,12,15.9f,16,23.9f,24,31.5f,32}) {
                MinecraftText.font(g,Font.PLAIN,request);
                assertEquals(Math.max(1,(int)Math.floor(request/8)),MinecraftText.scale(g));
                assertEquals(0,g.getFont().getSize()%8);
                for (int available=0;available<160;available++)
                    assertTrue(MinecraftText.width(g,MinecraftText.fit(g,"Größe · ÄÖÜ äöü ß …",available)) <= available);
            }
        } finally { g.dispose(); }
        for (char ch : "ÄÖÜäöüß".toCharArray()) assertNotNull(MinecraftFont.Font.getChar(ch));
    }

    private static BufferedImage render(int style,int scale) {
        BufferedImage image = new BufferedImage(256*scale,12*scale,BufferedImage.TYPE_INT_ARGB); Graphics2D g = image.createGraphics();
        try { MinecraftText.font(g,style,scale*8); g.setColor(new Color(91,203,245)); MinecraftText.draw(g,"SmartTV Größe ÄÖÜ äöü ß 0123",0,7*scale); }
        finally { g.dispose(); }
        return image;
    }

    @Test void settingsRowsLeaveRoomForTheFooterAtEveryDisplaySize() {
        for (int[] size : new int[][]{{128,128},{384,128},{384,256},{256,384},{768,512},{1024,576}}) {
            for (DisplaySetting option : DisplaySetting.values()) {
                var layout = new SmartTvSettings.Layout(size[0],size[1],option.ordinal());
                for (DisplaySetting row : layout.options()) {
                    Rectangle bounds = layout.row(row.ordinal());
                    if (!bounds.isEmpty()) {
                        assertTrue(layout.panel().contains(bounds));
                        assertTrue(bounds.y+bounds.height <= layout.panel().y+layout.panel().height-26,"Settings row overlaps footer");
                        assertEquals(row.ordinal(),layout.hit(bounds.x+2,bounds.y+2));
                    }
                }
            }
        }
    }
}
