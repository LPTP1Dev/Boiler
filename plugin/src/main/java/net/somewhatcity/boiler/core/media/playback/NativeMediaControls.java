package net.somewhatcity.boiler.core.media.playback;

import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.BoilerPlugin;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Queries, commands and final native disposal are serialized outside the server and render threads. */
public final class NativeMediaControls implements AutoCloseable {
    public interface Backend {
        Set<RemoteAction> probe();
        void action(RemoteAction action);
        void close();
    }
    private final BoilerPlugin plugin;
    private final Backend backend;
    private final java.util.function.Consumer<Throwable> failure;
    private final Object nativeLock = new Object();
    private record Operation(RemoteAction action, Runnable job) {}
    private final ArrayDeque<Operation> commands = new ArrayDeque<>();
    private final ScheduledFuture<?> poll;
    private volatile Set<RemoteAction> actions = Set.of();
    private boolean scheduled;
    private volatile boolean closed;

    public NativeMediaControls(BoilerPlugin plugin, Backend backend) {
        this(plugin,backend,error -> {});
    }
    public NativeMediaControls(BoilerPlugin plugin, Backend backend, java.util.function.Consumer<Throwable> failure) {
        this.plugin = plugin; this.backend = backend; this.failure=failure;
        poll = plugin.runtime().audio().scheduleAtFixedRate(this::schedule, 0, 250, TimeUnit.MILLISECONDS);
    }
    public Set<RemoteAction> actions() { return closed ? Set.of() : actions; }
    public synchronized void action(RemoteAction action) {
        if (closed || commands.size() >= 32) return;
        commands.add(new Operation(action, null)); schedule();
    }
    public synchronized void execute(Runnable job) {
        if (closed || commands.size() >= 32) return;
        commands.add(new Operation(null, job)); schedule();
    }
    private synchronized void schedule() {
        if (closed || scheduled) return;
        scheduled = true;
        try { plugin.runtime().io().execute(this::drain); }
        catch (java.util.concurrent.RejectedExecutionException e) { scheduled = false; }
    }
    private void drain() {
        try {
            synchronized (nativeLock) {
                while (true) {
                    Operation operation;
                    synchronized (this) { if (closed) return; operation = commands.poll(); }
                    Set<RemoteAction> latest = Set.copyOf(backend.probe());
                    if (closed) return;
                    actions = latest;
                    if (operation == null) break;
                    if (operation.job() != null) operation.job().run();
                    else if (actions.contains(operation.action())) backend.action(operation.action());
                }
            }
        } catch (Exception | LinkageError e) {
            failure.accept(e);
            actions = Set.of(); plugin.getLogger().log(Level.WARNING, "Native media control failed", e);
        } finally {
            synchronized (this) { scheduled = false; if (!commands.isEmpty() && !closed) schedule(); }
        }
    }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; commands.clear(); actions = Set.of(); }
        poll.cancel(false);
        // No further actions can enter the backend; wait for an in-flight query only on an I/O worker.
        Runnable dispose = () -> {
            synchronized (nativeLock) {
                try { backend.close(); }
                catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Closing native media", e); }
            }
        };
        try { plugin.runtime().startWorker("native-dispose", dispose); }
        catch (java.util.concurrent.RejectedExecutionException e) {
            // A late cancellation can outlive the runtime; native disposal still must not block main.
            Thread.ofPlatform().daemon().name("Boiler-late-native-dispose").start(dispose);
        }
    }
}
