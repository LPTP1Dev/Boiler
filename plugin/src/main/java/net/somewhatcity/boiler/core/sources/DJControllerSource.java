package net.somewhatcity.boiler.core.sources;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.IBoilerSource;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.Util;
import net.somewhatcity.boiler.core.audio.BAudioPlayer;
import net.somewhatcity.boiler.core.dj.*;
import net.somewhatcity.boiler.core.media.*;
import org.bukkit.command.CommandSender;
import java.awt.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Two local decks with a modal file browser drawn over the controller. */
public final class DJControllerSource implements IBoilerSource, CancellableLoad {
    private BoilerPlugin plugin;
    private IBoilerDisplay display;
    private MediaLibrary library;
    private final DjEngine engine = new DjEngine();
    private final AtomicLongArray loads = new AtomicLongArray(2);
    private final AtomicReferenceArray<String> loading = new AtomicReferenceArray<>(new String[]{"", ""});
    private final FutureTask<?>[] preparations = new FutureTask<?>[2];
    private final String[] directories = {"", ""};
    private final Set<DjTrack> awaitingInstall = ConcurrentHashMap.newKeySet();
    private final AtomicLong browseGeneration = new AtomicLong();
    private volatile MediaBrowser.View browser;
    private volatile int browserDeck;
    private volatile boolean closed;
    private Thread worker;
    private BAudioPlayer audio;

    @Override public void load(IBoilerDisplay display, JsonObject data) {
        plugin = BoilerPlugin.getPlugin(); this.display = display;
        try { library = new MediaLibrary(plugin.getDataFolder().toPath()); }
        catch (Exception error) { throw new IllegalStateException("Medienordner nicht verfügbar", error); }
        for (int i = 0; i < 2; i++) if (data != null && data.has("directory" + i)) directories[i] = data.get("directory" + i).getAsString();
        plugin.callOnMain(() -> {
            if (closed) return null;
            synchronized (this) {
                if (closed) return null;
                audio = createAudio(display);
                try {
                    worker = plugin.runtime().startWorker("dj-" + display.id(), () -> {
                        try { engine.run(audio::play, () -> {}); }
                        finally { audio.stop(); }
                    });
                } catch (RuntimeException error) { audio.stop(); throw error; }
            }
            return null;
        });
    }
    private BAudioPlayer createAudio(IBoilerDisplay display) { return new BAudioPlayer(display, true); }
    private boolean allowed(CommandSender sender) { return !closed && sender.hasPermission("boiler.command.action"); }
    private boolean canLoad(CommandSender sender) { return allowed(sender) && sender.hasPermission("boiler.command.source"); }

    private void browse(int deck, String directory) {
        browserDeck = deck; long request = browseGeneration.incrementAndGet();
        browser = new MediaBrowser.View(directory, List.of(), 0, "Lade Ordner ...", true);
        try { plugin.runtime().io().execute(() -> {
            try {
                List<MediaLibrary.Entry> entries = library.list(directory).stream()
                        .filter(e -> e.kind() == MediaLibrary.Kind.DIRECTORY || e.kind() == MediaLibrary.Kind.AUDIO).toList();
                String relative = library.relative(library.resolve(directory));
                plugin.onMain(() -> {
                    if (closed || request != browseGeneration.get()) return;
                    directories[deck] = relative; saveDirectories();
                    browser = new MediaBrowser.View(relative, entries, 0, "", false); display.tick();
                });
            } catch (Exception error) { plugin.onMain(() -> {
                if (!closed && request == browseGeneration.get()) browser = new MediaBrowser.View("", List.of(), 0, "Ordner nicht lesbar. R: neu laden", false);
            }); }
        }); } catch (RejectedExecutionException error) { browser = new MediaBrowser.View(directory, List.of(), 0, "Beschaeftigt. R: neu laden", false); }
    }
    private void saveDirectories() {
        JsonObject state = new JsonObject(); for (int i = 0; i < 2; i++) state.addProperty("directory" + i, directories[i]);
        display.saveSourceData(state);
    }
    private synchronized void prepare(CommandSender sender, int deck, String relative) {
        if (!canLoad(sender)) return;
        long request = loads.incrementAndGet(deck);
        if (preparations[deck] != null) preparations[deck].cancel(true);
        loading.set(deck, "Lade ...");
        FutureTask<Void> task = new FutureTask<>(() -> {
            DjTrack track = null;
            try {
                if (Path.of(relative).isAbsolute()) throw new java.io.IOException("Bitte einen relativen Pfad eingeben");
                Path path = library.resolve(relative);
                if (!Files.isRegularFile(path) || MediaLibrary.kind(path) != MediaLibrary.Kind.AUDIO) throw new java.io.IOException("Bitte eine Audiodatei aus media wählen");
                track = DjTrack.prepare(path, value -> { if (!closed && request == loads.get(deck)) loading.set(deck, "Lade " + value + "%"); });
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                DjTrack prepared = track;
                synchronized (this) {
                    if (closed || request != loads.get(deck)) return null;
                    awaitingInstall.add(prepared); track = null;
                }
                try {
                    plugin.onMain(() -> {
                        synchronized (this) {
                            if (!awaitingInstall.remove(prepared)) return;
                            if (closed || request != loads.get(deck) || !canLoad(sender)) { closeLater(prepared); if (request == loads.get(deck)) loading.set(deck, ""); return; }
                            engine.install(deck, prepared); loading.set(deck, ""); display.tick();
                        }
                    });
                } catch (RuntimeException error) {
                    if (awaitingInstall.remove(prepared)) close(prepared);
                    throw error;
                }
            } catch (Exception error) {
                if (!closed && request == loads.get(deck)) plugin.onMain(() -> {
                    if (!closed && request == loads.get(deck)) { loading.set(deck, "Ladefehler"); Util.sendErrMsg(sender, "Track kann nicht geladen werden: %s", error.getMessage()); }
                });
            } finally { if (track != null) close(track); }
            return null;
        });
        preparations[deck] = task;
        try { plugin.runtime().startWorker("dj-load-" + display.id() + "-" + deck, task); }
        catch (RejectedExecutionException error) { task.cancel(true); loading.set(deck, "Beschaeftigt"); }
    }
    private static void close(DjTrack track) { try { track.close(); } catch (Exception ignored) {} }
    private void closeLater(DjTrack track) {
        try { plugin.runtime().startWorker("dj-cache-dispose", () -> close(track)); }
        catch (RejectedExecutionException shutdown) { close(track); }
    }
    private void leaveBrowser() { browseGeneration.incrementAndGet(); browser = null; display.tick(); }
    private void navigate(int delta) {
        MediaBrowser.View b = browser; if (b == null || b.loading() || b.entries().isEmpty()) return;
        browser = new MediaBrowser.View(b.directory(), b.entries(), Math.floorMod(b.selected() + delta, b.entries().size()), b.status(), false);
    }
    private void open(CommandSender sender, MediaLibrary.Entry entry) {
        if (entry.kind() == MediaLibrary.Kind.DIRECTORY) browse(browserDeck, entry.path());
        else if (canLoad(sender)) { int deck = browserDeck; leaveBrowser(); prepare(sender, deck, entry.path()); }
    }
    @Override public void onClick(CommandSender sender, int x, int y, boolean right) {
        if (!allowed(sender)) return;
        MediaBrowser.View b = browser;
        if (b != null) {
            var point = new DjLayout(display.width(), display.height()).point(x, y);
            if (DjBrowser.CLOSE.contains(point)) { leaveBrowser(); return; }
            if (DjBrowser.BACK.contains(point)) { if (b.directory().isEmpty()) leaveBrowser(); else browse(browserDeck, MediaLibrary.parent(b.directory())); return; }
            if (DjBrowser.REFRESH.contains(point)) { browse(browserDeck, b.directory()); return; }
            if (DjBrowser.PREVIOUS.contains(point)) { navigate(-DjBrowser.ROWS); return; }
            if (DjBrowser.NEXT.contains(point)) { navigate(DjBrowser.ROWS); return; }
            if (DjBrowser.OPEN.contains(point)) { if (!b.loading() && !b.entries().isEmpty()) open(sender, b.entries().get(b.selected())); return; }
            if (!b.loading()) for (int slot = 0; slot < DjBrowser.ROWS; slot++) if (DjBrowser.row(slot).contains(point)) {
                int index = b.selected() / DjBrowser.ROWS * DjBrowser.ROWS + slot;
                if (index < b.entries().size()) open(sender, b.entries().get(index)); return;
            }
            return;
        }
        DjLayout.Control c = new DjLayout(display.width(), display.height()).hit(x, y); if (c == null) return;
        switch (c.id()) {
            case "load" -> { if (canLoad(sender)) browse(c.deck(), directories[c.deck()]); }
            case "play", "cue", "in", "out", "loop", "lead", "sync" -> engine.transport(c.deck(), c.id());
            case "jog" -> { if (right) engine.transport(c.deck(), "cue"); }
            default -> { if (right) engine.adjust(c.deck(), c.id(), 0, true); }
        }
    }
    @Override public void onScroll(CommandSender sender, int x, int y, int delta) {
        if (!allowed(sender) || delta == 0) return;
        if (browser != null) { navigate(Integer.signum(delta)); return; }
        DjLayout.Control c = new DjLayout(display.width(), display.height()).hit(x, y); if (c == null) return;
        if (c.id().equals("jog")) engine.jog(c.deck(), Integer.signum(delta));
        else engine.adjust(c.deck(), c.id(), Integer.signum(delta), false);
    }
    @Override public void onKey(CommandSender sender, String key) {
        if (!allowed(sender) || browser == null) return;
        MediaBrowser.View b = browser;
        switch (key.toUpperCase(Locale.ROOT)) {
            case "ESCAPE" -> leaveBrowser();
            case "A", "ARROW_LEFT" -> { if (b.directory().isEmpty()) leaveBrowser(); else browse(browserDeck, MediaLibrary.parent(b.directory())); }
            case "W", "ARROW_UP" -> navigate(-1);
            case "S", "ARROW_DOWN" -> navigate(1);
            case "PAGE_UP" -> navigate(-DjBrowser.ROWS);
            case "PAGE_DOWN" -> navigate(DjBrowser.ROWS);
            case "R" -> browse(browserDeck, b.directory());
            case "ENTER", "SPACE", "D", "ARROW_RIGHT" -> { if (!b.loading() && !b.entries().isEmpty()) open(sender, b.entries().get(b.selected())); }
        }
    }
    @Override public void draw(Graphics2D g, Rectangle viewport) {
        MediaBrowser.View b = browser;
        DjView.draw(g, viewport.width, viewport.height, engine.view(), new String[]{loading.get(0), loading.get(1)});
        if (b != null) DjBrowser.draw(g, viewport.width, viewport.height, browserDeck, b);
    }
    @Override public Set<RemoteAction> remoteActions() { return !closed && browser != null ? Set.of(RemoteAction.BACK, RemoteAction.OK) : Set.of(); }
    @Override public void onRemoteAction(CommandSender sender, RemoteAction action) {
        if (!allowed(sender) || browser == null) return;
        if (action == RemoteAction.BACK) onKey(sender, "ARROW_LEFT");
        if (action == RemoteAction.OK) onKey(sender, "ENTER");
    }
    @Override public synchronized void cancelLoad() {
        closed = true; loads.incrementAndGet(0); loads.incrementAndGet(1); browseGeneration.incrementAndGet();
        for (FutureTask<?> preparation : preparations) if (preparation != null) preparation.cancel(true);
        for (DjTrack track : awaitingInstall) closeLater(track); awaitingInstall.clear();
        engine.close(); if (worker != null) worker.interrupt();
    }
    @Override public void unload() {
        cancelLoad();
        if (worker == null) engine.dispose();
    }
}
