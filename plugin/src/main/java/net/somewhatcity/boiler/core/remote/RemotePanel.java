package net.somewhatcity.boiler.core.remote;

import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.smarttv.SmartTvStyle;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The immutable button layout is shared by painting and hit testing at every size. */
public final class RemotePanel {
    public record Button(String id, String label, String key, RemoteAction action, Rectangle bounds) {}
    public record View(String title, String status, boolean ready, Set<RemoteAction> actions) {
        public View { actions = Set.copyOf(actions); }
        public boolean enabled(Button button) { return ready && (button.action() == null || actions.contains(button.action())); }
    }
    private static final String[] TABS = {"TV", "Medien", "123", "Text"};
    private BufferedImage frame;
    private View rendered;
    private int renderedPage = -1;
    private String renderedPress = "";

    public static boolean full(int width, int height) { return width >= 128 && height >= 384 || width >= 384 && height >= 128; }
    public static Rectangle tab(int width, int index) { return new Rectangle(4 + index * (width - 8) / 4, 24, (width - 8) / 4 - 2, 17); }

    public static List<Button> buttons(int width, int height, int page) {
        List<Button> buttons = new ArrayList<>();
        if (full(width, height)) {
            boolean portrait = height >= width;
            double scale = Math.min(width / (portrait ? 128.0 : 384.0), height / (portrait ? 384.0 : 128.0));
            int dx = (int) ((width - (portrait ? 128 : 384) * scale) / 2), dy = (int) ((height - (portrait ? 384 : 128) * scale) / 2);
            group(buttons, 0, portrait ? 8 : 132, portrait ? 32 : 20, scale, dx, dy);
            group(buttons, 1, portrait ? 8 : 252, portrait ? 144 : 24, scale, dx, dy);
            group(buttons, 2, 8, portrait ? 206 : 24, scale, dx, dy);
            if (portrait) group(buttons, 3, 8, 306, scale, dx, dy);
            else group(buttons, 3, 0, 0, scale * .8, dx + (int) (252 * scale), dy + (int) (82 * scale));
        } else {
            int groupHeight = page == 0 ? 104 : page == 2 ? 92 : 54;
            double scale = Math.min((width - 12) / 112.0, (height - 52) / (double) groupHeight);
            int dx = (int) ((width - 112 * scale) / 2), dy = 46 + (int) ((height - 52 - groupHeight * scale) / 2);
            group(buttons, page, 0, 0, scale, dx, dy);
        }
        return List.copyOf(buttons);
    }

    private static void group(List<Button> out, int group, int x, int y, double s, int dx, int dy) {
        if (group == 0) {
            add(out, "up", "↑", "ARROW_UP", null, x + 44, y, 24, 24, s, dx, dy);
            add(out, "left", "←", "ARROW_LEFT", null, x + 16, y + 26, 24, 24, s, dx, dy);
            add(out, "ok", "OK", null, RemoteAction.OK, x + 44, y + 26, 24, 24, s, dx, dy);
            add(out, "right", "→", "ARROW_RIGHT", null, x + 72, y + 26, 24, 24, s, dx, dy);
            add(out, "down", "↓", "ARROW_DOWN", null, x + 44, y + 52, 24, 24, s, dx, dy);
            add(out, "home", "Home", null, RemoteAction.HOME, x, y + 80, 34, 24, s, dx, dy);
            add(out, "back", "Zur.", null, RemoteAction.BACK, x + 39, y + 80, 34, 24, s, dx, dy);
            add(out, "settings", "Setup", null, RemoteAction.SETTINGS, x + 78, y + 80, 34, 24, s, dx, dy);
        } else if (group == 1) {
            RemoteAction[] actions = {RemoteAction.PLAY_PAUSE, RemoteAction.STOP, RemoteAction.SEEK_BACKWARD, RemoteAction.SEEK_FORWARD,
                    RemoteAction.VOLUME_DOWN, RemoteAction.MUTE, RemoteAction.VOLUME_UP};
            String[] labels = {">/II", "Stop", "−10s", "+10s", "Vol−", "Stumm", "Vol+"};
            for (int index = 0; index < actions.length; index++) {
                boolean top = index < 4;
                add(out, actions[index].name(), labels[index], null, actions[index], x + (top ? index * 28 : (index - 4) * 38), y + (top ? 0 : 30), top ? 26 : 36, 24, s, dx, dy);
            }
        } else if (group == 2) {
            for (int digit = 1; digit <= 9; digit++) add(out, "digit" + digit, "" + digit, "" + digit, null, x + (digit - 1) % 3 * 38, y + (digit - 1) / 3 * 23, 36, 21, s, dx, dy);
            add(out, "digit0", "0", "0", null, x + 38, y + 69, 36, 21, s, dx, dy);
        } else {
            String[] keys = {null, "ESCAPE", "TAB", "BACKSPACE", "DELETE", "PAGE_UP", "PAGE_DOWN"};
            String[] labels = {"Text", "Esc", "Tab", "Back", "Entf", "Pg+", "Pg-"};
            for (int index = 0; index < keys.length; index++) add(out, "text" + index, labels[index], keys[index], index == 0 ? RemoteAction.TEXT_INPUT : null,
                    x + index % 4 * 28, y + index / 4 * 28, 26, 24, s, dx, dy);
        }
    }
    private static void add(List<Button> out, String id, String label, String key, RemoteAction action, int x, int y, int width, int height, double s, int dx, int dy) {
        out.add(new Button(id, label, key, action, new Rectangle(dx + (int) Math.round(x * s), dy + (int) Math.round(y * s), (int) Math.round(width * s), (int) Math.round(height * s))));
    }

    public void draw(Graphics2D graphics, Rectangle viewport, View view, int page, String pressed) {
        if (frame == null || frame.getWidth() != viewport.width || frame.getHeight() != viewport.height || !view.equals(rendered) || renderedPage != page || !pressed.equals(renderedPress)) {
            frame = frame != null && frame.getWidth() == viewport.width && frame.getHeight() == viewport.height ? frame : new BufferedImage(viewport.width, viewport.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = frame.createGraphics();
            try { paint(g, viewport.width, viewport.height, view, page, pressed); } finally { g.dispose(); }
            rendered = view; renderedPage = page; renderedPress = pressed;
        }
        graphics.drawImage(frame, viewport.x, viewport.y, null);
    }
    private static void paint(Graphics2D g, int width, int height, View view, int page, String pressed) {
        SmartTvStyle.pixels(g);
        g.setColor(new Color(11, 14, 22)); g.fillRect(0, 0, width, height);
        g.setPaint(new GradientPaint(0, 0, new Color(42, 48, 61), width, height, new Color(23, 28, 39)));
        g.fillRoundRect(2, 2, width - 4, height - 4, 18, 18);
        g.setColor(new Color(163, 193, 222)); SmartTvStyle.font(g, Font.BOLD, 10);
        SmartTvStyle.text(g, view.title(), 8, 15, width - 16);
        if (!full(width, height)) for (int index = 0; index < 4; index++) {
            Rectangle tab = tab(width, index); g.setColor(page == index ? new Color(54, 102, 142) : new Color(26, 32, 44));
            g.fillRoundRect(tab.x, tab.y, tab.width, tab.height, 5, 5);
            g.setColor(SmartTvStyle.WHITE); SmartTvStyle.font(g, Font.PLAIN, 8);
            SmartTvStyle.text(g, TABS[index], tab.x + 3, tab.y + 12, tab.width - 6);
        }
        for (Button button : buttons(width, height, page)) {
            Rectangle rect = button.bounds(); boolean enabled = view.enabled(button);
            Color color = button.action() == RemoteAction.HOME ? new Color(130, 68, 135) : button.action() == RemoteAction.OK ? new Color(40, 115, 152) : new Color(61, 70, 87);
            g.setColor(enabled ? button.id().equals(pressed) ? color.brighter() : color : new Color(32, 38, 49));
            g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 6, 6);
            g.setColor(enabled ? new Color(245, 248, 255) : new Color(105, 114, 133));
            if (button.key() != null && button.key().startsWith("ARROW_")) {
                Graphics2D arrow = (Graphics2D) g.create();
                try {
                    arrow.translate(rect.getCenterX(), rect.getCenterY());
                    arrow.rotate(switch (button.key()) { case "ARROW_RIGHT" -> Math.PI/2; case "ARROW_DOWN" -> Math.PI; case "ARROW_LEFT" -> -Math.PI/2; default -> 0; });
                    int length = Math.max(3, rect.height/5);
                    arrow.setStroke(new BasicStroke(Math.max(1,rect.height/18f)));
                    arrow.drawLine(0,length,0,-length); arrow.drawLine(0,-length,-length,0); arrow.drawLine(0,-length,length,0);
                } finally { arrow.dispose(); }
                continue;
            }
            SmartTvStyle.font(g, button.action() == RemoteAction.HOME ? Font.BOLD : Font.PLAIN, Math.max(8, Math.min(14, rect.height * .4f)));
            int textWidth = SmartTvStyle.textWidth(g, button.label());
            SmartTvStyle.text(g, button.label(), rect.x + Math.max(1, (rect.width - textWidth) / 2), rect.y + (rect.height + 7 * net.somewhatcity.boiler.core.smarttv.MinecraftText.scale(g)) / 2 - 2, rect.width - 2);
        }
        if (height >= 256) { g.setColor(new Color(151, 163, 183)); SmartTvStyle.font(g, Font.PLAIN, 8); SmartTvStyle.text(g, view.status(), 8, height - 8, width - 16); }
    }
    public void clear() { frame = null; rendered = null; }
}
