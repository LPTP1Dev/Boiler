package net.somewhatcity.boiler.core.dj;

import java.awt.*;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/** One coordinate system for painting and all hit testing, including letterboxing. */
public final class DjLayout {
    public record Control(int deck, String id, Rectangle bounds, String label, boolean rotary) {}
    public static final List<Control> CONTROLS;
    static {
        List<Control> list = new ArrayList<>();
        for (int deck = 0; deck < 2; deck++) {
            int x = deck == 0 ? 3 : 173;
            list.add(new Control(deck, "jog", new Rectangle(x + 3, 48, 37, 37), "", true));
            list.add(new Control(deck, "tempo", new Rectangle(x + 63, 48, 12, 37), "T", false));
            String[] ids = {"play", "cue", "load", "in", "out", "loop"};
            String[] labels = {">/II", "CUE", "LOAD", "IN", "OUT", "LOOP"};
            for (int i = 0; i < ids.length; i++) list.add(new Control(deck, ids[i], new Rectangle(x + i % 3 * 26, 90 + i / 3 * 13, 24, 11), labels[i], false));
            list.add(new Control(deck, "lead", new Rectangle(x, 117, 37, 10), "LEAD", false));
            list.add(new Control(deck, "sync", new Rectangle(x + 40, 117, 37, 10), "SYNC", false));
            for (int band = 0; band < 3; band++) list.add(new Control(deck, new String[]{"high", "mid", "low"}[band],
                    new Rectangle(91 + deck * 47, 49 + band * 18, 14, 14), new String[]{"HI", "MID", "LO"}[band], true));
            list.add(new Control(deck, "channel", new Rectangle(113 + deck * 47, 49, 10, 51), "", false));
        }
        list.add(new Control(0, "master", new Rectangle(123, 86, 14, 14), "M", true));
        list.add(new Control(0, "cross", new Rectangle(91, 105, 77, 11), "", false));
        CONTROLS = List.copyOf(list);
    }
    public final double scale, left, top;
    public DjLayout(int width, int height) {
        scale = Math.min(width / 256.0, height / 128.0);
        left = (width - 256 * scale) / 2; top = (height - 128 * scale) / 2;
    }
    public Point2D point(int x, int y) { return new Point2D.Double((x - left) / scale, (y - top) / scale); }
    public Control hit(int x, int y) {
        Point2D point = point(x, y);
        for (Control control : CONTROLS) if (control.bounds.contains(point)) return control;
        return null;
    }
    public void transform(Graphics2D g) { g.translate(left, top); g.scale(scale, scale); }
}
