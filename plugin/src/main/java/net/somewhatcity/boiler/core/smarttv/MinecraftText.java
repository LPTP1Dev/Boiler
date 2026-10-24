package net.somewhatcity.boiler.core.smarttv;

import org.bukkit.map.MapFont;
import org.bukkit.map.MinecraftFont;
import java.awt.*;

/** Draws the server's original Minecraft bitmap glyphs as whole, opaque pixel blocks. */
public final class MinecraftText {
    private static final MapFont GLYPHS = MinecraftFont.Font;
    private MinecraftText() {}

    public static int scale(Graphics2D g) { return Math.max(1,g.getFont().getSize()/8); }
    public static void font(Graphics2D g,int style,float requestedHeight) {
        int scale = Math.max(1,(int)Math.floor(requestedHeight/8));
        g.setFont(new Font(Font.MONOSPACED,style,scale*8)); // Metadata only; AWT never draws glyphs.
    }
    private static String normalize(String text) {
        StringBuilder result = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (cp <= Character.MAX_VALUE && GLYPHS.getChar((char)cp) != null) result.append((char)cp);
            else result.append(switch (cp) { case 0x2026 -> "..."; case 0x2039 -> "<"; case 0x203a -> ">"; case 0x2013,0x2014,0x2212 -> "-"; case 0x00a0 -> " "; default -> "?"; });
        });
        return result.toString();
    }
    public static int width(Graphics2D g,String text) { return width(normalize(text),scale(g),g.getFont().isBold()); }
    private static int width(String text,int scale,boolean bold) {
        int width = 0;
        for (int i=0;i<text.length();i++) width += GLYPHS.getChar(text.charAt(i)).getWidth()+1+(bold && text.charAt(i) != ' ' ? 1 : 0);
        return Math.max(0,width-1)*scale;
    }
    public static String fit(Graphics2D g,String value,int available) {
        String text = normalize(value);
        if (width(g,text) <= available) return text;
        String tail = "...";
        while (!tail.isEmpty() && width(g,tail) > available) tail = tail.substring(0,tail.length()-1);
        while (!text.isEmpty() && width(g,text+tail) > available) text = text.substring(0,text.length()-1);
        return text+tail;
    }
    public static void draw(Graphics2D g,String text,int x,int baseline) {
        int scale = scale(g), top = baseline-7*scale;
        boolean bold = g.getFont().isBold();
        String normalized = normalize(text);
        for (int index=0;index<normalized.length();index++) {
            char ch = normalized.charAt(index);
            var glyph = GLYPHS.getChar(ch);
            for (int row=0;row<glyph.getHeight();row++) for (int col=0;col<glyph.getWidth();col++) if (glyph.get(row,col)) {
                g.fillRect(x+col*scale,top+row*scale,scale,scale);
                if (bold && ch != ' ') g.fillRect(x+(col+1)*scale,top+row*scale,scale,scale);
            }
            x += (glyph.getWidth()+1+(bold && ch != ' ' ? 1 : 0))*scale;
        }
    }
}
