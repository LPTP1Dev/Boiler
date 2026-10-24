package net.somewhatcity.boiler.core.dj;

import net.somewhatcity.boiler.core.media.MediaBrowser;
import net.somewhatcity.boiler.core.media.MediaLibrary;
import java.awt.*;
import static net.somewhatcity.boiler.core.smarttv.SmartTvStyle.*;

/** Modal map overlay; shares the controller's logical coordinates and scaling. */
public final class DjBrowser {
    public static final int ROWS = 3;
    public static final Rectangle PANEL = new Rectangle(18, 12, 220, 104);
    public static final Rectangle BACK = new Rectangle(24, 17, 18, 15);
    public static final Rectangle REFRESH = new Rectangle(194, 17, 16, 15);
    public static final Rectangle CLOSE = new Rectangle(214, 17, 18, 15);
    public static final Rectangle PREVIOUS = new Rectangle(24, 96, 54, 14);
    public static final Rectangle OPEN = new Rectangle(82, 96, 90, 14);
    public static final Rectangle NEXT = new Rectangle(178, 96, 54, 14);
    private DjBrowser() {}
    public static Rectangle row(int slot) { return new Rectangle(24, 44 + slot * 16, 208, 15); }

    public static void draw(Graphics2D graphics, int width, int height, int deck, MediaBrowser.View view) {
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            new DjLayout(width, height).transform(g); pixels(g); font(g, Font.PLAIN, 8);
            g.setColor(new Color(0, 0, 0, 160)); g.fillRect(0, 0, 256, 128);
            Color accent = deck == 0 ? new Color(53, 214, 226) : new Color(246, 150, 62);
            g.setColor(new Color(18, 25, 35)); g.fill(PANEL); g.setColor(accent); g.drawRect(PANEL.x, PANEL.y, PANEL.width - 1, PANEL.height - 1);
            button(g, BACK, "<", MUTED); button(g, REFRESH, "R", MUTED); button(g, CLOSE, "X", WHITE);
            g.setColor(accent); text(g, "DECK " + (deck == 0 ? "A" : "B") + " - DATEIEN", 48, 27, 140);
            g.setColor(MUTED); text(g, "media / " + view.directory(), 24, 40, 208);
            int page = view.selected() / ROWS;
            if (view.loading() || view.entries().isEmpty()) {
                g.setColor(WHITE); lines(g, view.status().isBlank() ? "Keine Audiodateien. R: neu laden" : view.status(), 28, 57, 200, 3, 11);
            } else for (int slot = 0; slot < ROWS; slot++) {
                int index = page * ROWS + slot; if (index >= view.entries().size()) break;
                var entry = view.entries().get(index); Rectangle r = row(slot);
                g.setColor(index == view.selected() ? new Color(42, 72, 87) : new Color(28, 38, 51)); g.fill(r);
                g.setColor(entry.kind() == MediaLibrary.Kind.DIRECTORY ? new Color(247, 201, 101) : accent);
                text(g, entry.kind() == MediaLibrary.Kind.DIRECTORY ? "DIR" : "TON", r.x + 3, r.y + 11, 22);
                g.setColor(WHITE); text(g, entry.name(), r.x + 29, r.y + 11, r.width - 34);
            }
            int pages = Math.max(1, (view.entries().size() + ROWS - 1) / ROWS);
            button(g, PREVIOUS, "< " + (page + 1) + "/" + pages, MUTED);
            button(g, OPEN, "OEFFNEN", view.loading() || view.entries().isEmpty() ? MUTED : accent);
            button(g, NEXT, ">", MUTED);
        } finally { g.dispose(); }
    }
    private static void button(Graphics2D g, Rectangle r, String label, Color color) {
        g.setColor(new Color(39, 49, 63)); g.fill(r); g.setColor(color);
        text(g, label, r.x + Math.max(2, (r.width - textWidth(g, label)) / 2), r.y + 10, r.width - 4);
    }
}
