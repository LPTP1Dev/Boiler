package net.somewhatcity.boiler.core.dj;

import java.awt.*;
import java.util.Locale;
import static net.somewhatcity.boiler.core.smarttv.SmartTvStyle.*;

public final class DjView {
    private static final Color[] ACCENT = {new Color(53, 214, 226), new Color(246, 150, 62)};
    private DjView() {}
    public static void draw(Graphics2D graphics, int width, int height, DjEngine.View state, String[] loading) {
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            pixels(g); g.setColor(new Color(8, 11, 16)); g.fillRect(0, 0, width, height);
            new DjLayout(width, height).transform(g); font(g, Font.PLAIN, 8);
            g.setColor(new Color(26, 32, 42)); g.fillRect(85, 38, 85, 90);
            for (int i = 0; i < 2; i++) {
                DjEngine.DeckView deck = state.decks().get(i); int x = i == 0 ? 3 : 173;
                int header = i == 0 ? 3 : 130;
                g.setColor(ACCENT[i]); text(g, (i == 0 ? "A " : "B ") + deck.name(), header, 9, 73);
                g.setColor(WHITE); text(g, deck.bpm() > 0 ? String.format(Locale.ROOT, "%.1f BPM", deck.bpm()) : "--.- BPM", header + 74, 9, 49);
                g.setColor(!loading[i].isBlank() ? ACCENT[i] : !deck.error().isBlank() ? new Color(255, 110, 100) : MUTED);
                String status = !loading[i].isBlank() ? loading[i] : !deck.error().isBlank() ? deck.error() : time(deck.position()) + "/" + time(deck.duration());
                text(g, status, x, 45, 78);
                waveform(g, deck, ACCENT[i], 3, 12 + i * 13, 250, 11);
                g.setColor(ACCENT[i]); g.fillRect(0, 12 + i * 13, 2, 11);
                text(g, String.format(Locale.ROOT, "%+.1f", deck.tempo()), x + 41, 56, 21);
                g.setColor(new Color(12, 18, 26)); g.fillRect(x + 57, 60, 3, 24);
                g.setColor(ACCENT[i]); int level = (int)(24 * Math.min(1, deck.peak() * deck.channel())); g.fillRect(x + 57, 84 - level, 3, level);
            }
            g.setColor(MUTED); text(g, "MIX", 111, 45, 24);
            for (DjLayout.Control c : DjLayout.CONTROLS) {
                Rectangle r = c.bounds(); DjEngine.DeckView d = state.decks().get(c.deck()); Color color = ACCENT[c.deck()];
                if (c.id().equals("jog")) {
                    g.setColor(new Color(57, 66, 78)); g.fillOval(r.x, r.y, r.width, r.height);
                    g.setColor(new Color(15, 20, 27)); g.fillOval(r.x + 3, r.y + 3, r.width - 6, r.height - 6);
                    g.setColor(new Color(37, 45, 57)); g.drawOval(r.x + 6, r.y + 6, r.width - 12, r.height - 12); g.drawOval(r.x + 9, r.y + 9, r.width - 18, r.height - 18);
                    double angle = d.position() / 48000.0 * Math.PI;
                    int cx = r.x + r.width / 2, cy = r.y + r.height / 2;
                    g.setColor(color); g.fillOval(cx - 7, cy - 7, 14, 14);
                    g.drawLine(cx + (int)(8 * Math.cos(angle)), cy + (int)(8 * Math.sin(angle)), cx + (int)(15 * Math.cos(angle)), cy + (int)(15 * Math.sin(angle)));
                } else if (c.rotary()) {
                    double value = switch (c.id()) { case "master" -> state.master(); case "high" -> (d.eq().get(2) + 24) / 36; case "mid" -> (d.eq().get(1) + 24) / 36; default -> (d.eq().get(0) + 24) / 36; };
                    g.setColor(MUTED); text(g, c.label(), r.x, r.y - 1, r.width + 3);
                    g.setColor(new Color(8, 12, 18)); g.fillOval(r.x, r.y, r.width, r.height);
                    g.setColor(c.id().equals("master") ? WHITE : color); double angle = Math.PI * (.75 + 1.5 * value);
                    int cx = r.x + r.width / 2, cy = r.y + r.height / 2;
                    g.drawLine(cx, cy, cx + (int)(6 * Math.cos(angle)), cy + (int)(6 * Math.sin(angle)));
                } else if (c.id().equals("tempo") || c.id().equals("channel") || c.id().equals("cross")) {
                    g.setColor(new Color(8, 12, 18)); g.fillRect(r.x, r.y, r.width, r.height);
                    g.setColor(c.id().equals("cross") ? WHITE : color);
                    if (c.id().equals("cross")) g.fillRect(r.x + (int)((r.width - 5) * state.crossfade()), r.y + 1, 5, r.height - 2);
                    else {
                        double value = c.id().equals("tempo") ? (d.tempo() + 16) / 32 : 1 - d.channel();
                        g.fillRect(r.x + 1, r.y + (int)((r.height - 4) * value), r.width - 2, 4);
                    }
                } else {
                    boolean active = c.id().equals("play") && d.playing() || c.id().equals("loop") && d.loop()
                            || c.id().equals("in") && d.loopIn() >= 0 || c.id().equals("out") && d.loopOut() >= 0
                            || c.id().equals("lead") && state.leader() == c.deck() || c.id().equals("sync") && d.synced();
                    boolean syncError = c.id().equals("sync") && !d.synced() && d.error().startsWith("SYNC:");
                    g.setColor(syncError ? new Color(110, 35, 35) : active ? color.darker() : new Color(36, 45, 58)); g.fillRect(r.x, r.y, r.width, r.height);
                    String label = syncError ? "SYNC!" : c.label();
                    g.setColor(active ? WHITE : color); text(g, label, r.x + Math.max(0, (r.width - textWidth(g, label)) / 2), r.y + 8, r.width);
                }
            }
            g.setColor(MUTED); text(g, "A < MIX > B", 93, 126, 77);
        } finally { g.dispose(); }
    }
    private static void waveform(Graphics2D g, DjEngine.DeckView deck, Color accent, int x, int y, int width, int height) {
        g.setColor(new Color(15, 24, 33)); g.fillRect(x, y, width, height);
        int center = y + height / 2;
        g.setColor(new Color(41, 54, 65)); g.drawLine(x, center, x + width - 1, center);
        if (deck.duration() == 0) return;
        // Three seconds of playback: synced decks share grid spacing even when
        // their source BPM and playback rates differ.
        double span = 144000 * (1 + deck.tempo() / 100);
        double start = deck.position() - span / 2;
        boolean hasLoop = deck.loopIn() >= 0 && deck.loopOut() > deck.loopIn();
        int loopStart = column(deck.loopIn(), deck.position(), span, width);
        int loopEnd = column(deck.loopOut(), deck.position(), span, width);
        if (hasLoop) {
            int left = Math.max(0, loopStart), right = Math.min(width, loopEnd);
            g.setColor(deck.loop() ? accent.darker().darker().darker() : new Color(28, 37, 46));
            if (right > left) g.fillRect(x + left, y, right - left, height);
        }
        for (int column = 0; column < width; column++) {
            long from = (long)(start + span * column / width), until = (long)(start + span * (column + 1) / width);
            int envelope = deck.waveform().envelope(from, Math.max(from + 1, until));
            g.setColor(new Color(deck.waveform().color(from, Math.max(from + 1, until))));
            int top = center - Math.round((short)(envelope >>> 16) / 32768f * (height / 2 - 1));
            int bottom = center - Math.round((short)envelope / 32768f * (height / 2 - 1));
            g.drawLine(x + column, top, x + column, bottom);
        }
        DjBeatGrid grid = deck.beats();
        if (grid.bpm() > 0) {
            long first = (long)Math.ceil((Math.max(0, start) - grid.offset()) / grid.period());
            for (long beat = first;; beat++) {
                double sample = grid.offset() + beat * grid.period();
                if (sample >= start + span || sample >= deck.duration()) break;
                int column = (int)Math.round((sample - deck.position()) * width / span) + width / 2;
                if (column < 0 || column >= width) continue;
                g.setColor(new Color(94, 116, 138));
                g.drawLine(x + column, y, x + column, y + height - 1);
                g.setColor(new Color(183, 205, 222));
                g.drawLine(x + column, y, x + column, y + 1);
            }
        }
        // Keep the waveform and beat ticks readable inside the shaded selection.
        // IN is already visible while the user is still choosing OUT.
        g.setColor(deck.loop() ? accent : accent.darker());
        if (hasLoop) {
            int left = Math.max(0, loopStart), right = Math.min(width - 1, loopEnd);
            if (right >= left) g.drawLine(x + left, y + height - 1, x + right, y + height - 1);
        }
        if (deck.loopIn() >= 0 && loopStart >= 0 && loopStart < width) {
            g.drawLine(x + loopStart, y + 2, x + loopStart, y + height - 1);
            g.drawLine(x + loopStart, y + 2, x + Math.min(width - 1, loopStart + 3), y + 2);
        }
        if (hasLoop && loopEnd >= 0 && loopEnd < width) {
            g.drawLine(x + loopEnd, y + 2, x + loopEnd, y + height - 1);
            g.drawLine(x + Math.max(0, loopEnd - 3), y + 2, x + loopEnd, y + 2);
        }
        int playhead = width / 2;
        g.setColor(WHITE); g.drawLine(x + playhead, y, x + playhead, y + height - 1);
    }
    private static int column(long sample, long position, double span, int width) {
        double column = (sample - (double)position) * width / span + width / 2;
        return (int)Math.round(Math.max(-1, Math.min(width, column)));
    }
    private static String time(long samples) { long seconds = samples / 48000; return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
}
