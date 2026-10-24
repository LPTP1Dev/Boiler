package net.somewhatcity.boiler.core;

import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.media.playback.NativeMediaControls;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class NativeMediaControlsTest {
    @Test void queriesActionsAndDisposalAreSerializedOffTheServerThread() throws Exception {
        try (SourceTestEnvironment env = new SourceTestEnvironment()) {
            Thread main = Thread.currentThread();
            AtomicReference<Set<RemoteAction>> supported = new AtomicReference<>(Set.of(RemoteAction.PLAY_PAUSE));
            AtomicInteger executed = new AtomicInteger(), closed = new AtomicInteger(), concurrent = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            NativeMediaControls controls = new NativeMediaControls(env.plugin,new NativeMediaControls.Backend() {
                private void check() {
                    if (Thread.currentThread() == main || concurrent.incrementAndGet() != 1) failure.set(new AssertionError("Native work ran concurrently or on main"));
                    concurrent.decrementAndGet();
                }
                @Override public Set<RemoteAction> probe() { check(); return supported.get(); }
                @Override public void action(RemoteAction action) { check(); executed.incrementAndGet(); }
                @Override public void close() { check(); closed.incrementAndGet(); }
            });
            try {
                env.await(() -> controls.actions().contains(RemoteAction.PLAY_PAUSE));
                controls.action(RemoteAction.SEEK_FORWARD); controls.action(RemoteAction.PLAY_PAUSE);
                env.await(() -> executed.get() == 1);
                supported.set(Set.of(RemoteAction.SEEK_FORWARD));
                env.await(() -> controls.actions().contains(RemoteAction.SEEK_FORWARD));
                controls.action(RemoteAction.SEEK_FORWARD); env.await(() -> executed.get() == 2);
            } finally { controls.close(); controls.close(); }
            env.await(() -> closed.get() == 1);
            controls.action(RemoteAction.SEEK_FORWARD); assertTrue(controls.actions().isEmpty());
            assertEquals(2,executed.get()); assertNull(failure.get());
        }
    }
}
