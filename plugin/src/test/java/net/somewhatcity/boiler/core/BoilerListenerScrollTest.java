package net.somewhatcity.boiler.core;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.util.MapTraceResult;
import net.somewhatcity.boiler.api.IDisplayManager;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.core.listener.BoilerListener;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoilerListenerScrollTest {
    private final BoilerListener listener = new BoilerListener();
    private final Player player = mock(Player.class);
    private final MapEngineApi mapEngine = mock(MapEngineApi.class);
    private final MapTraceResult trace = mock(MapTraceResult.class, RETURNS_DEEP_STUBS);
    private final IBoilerDisplay display = mock(IBoilerDisplay.class);
    private final List<Integer> steps = new ArrayList<>();
    private BoilerPlugin previousPlugin;
    private MapEngineApi previousEngine;
    private Field singleton;

    @BeforeEach void setup() throws Exception {
        previousPlugin = BoilerPlugin.getPlugin(); previousEngine = BoilerPlugin.MAP_ENGINE;
        singleton = BoilerPlugin.class.getDeclaredField("plugin"); singleton.setAccessible(true);
        BoilerPlugin plugin = mock(BoilerPlugin.class); singleton.set(null, plugin);
        BoilerPlugin.MAP_ENGINE = mapEngine;
        IDisplayManager displays = mock(IDisplayManager.class); when(plugin.displayManager()).thenReturn(displays);
        when(mapEngine.traceDisplayInView(player, 10)).thenReturn(trace);
        when(displays.display(trace.display())).thenReturn(display);
        when(trace.viewPos().x()).thenReturn(27); when(trace.viewPos().y()).thenReturn(63);
        doAnswer(invocation -> { steps.add(invocation.getArgument(3)); return null; }).when(display).onScroll(eq(player), eq(27), eq(63), anyInt());
    }
    @AfterEach void restore() throws Exception { singleton.set(null, previousPlugin); BoilerPlugin.MAP_ENGINE = previousEngine; }

    @Test void allSingleStepsAndSkippedStepsKeepDirectionAcrossEverySlot() {
        for (int start = 0; start < 9; start++) for (int amount = -4; amount <= 4; amount++) {
            steps.clear(); listener.onItemHeld(new PlayerItemHeldEvent(player, start, Math.floorMod(start + amount, 9)));
            assertEquals(java.util.Collections.nCopies(Math.abs(amount), Integer.signum(amount)), steps, "start=" + start + ", amount=" + amount);
        }
    }
    @Test void fastForwardScrollingDoesNotBounceWhenItWrapsAndCanReverseImmediately() {
        int[] slots = {7, 0, 3, 6, 0, 2, 5, 8, 1};
        for (int i = 1; i < slots.length; i++) listener.onItemHeld(new PlayerItemHeldEvent(player, slots[i - 1], slots[i]));
        assertEquals(java.util.Collections.nCopies(21, 1), steps);
        steps.clear(); listener.onItemHeld(new PlayerItemHeldEvent(player, 1, 8));
        assertEquals(List.of(-1, -1), steps);
    }
    @Test void skippedSlotsTraceOnceAndRetainTheAimedCoordinates() {
        listener.onItemHeld(new PlayerItemHeldEvent(player, 7, 1));
        verify(mapEngine).traceDisplayInView(player, 10);
        verify(display, times(3)).onScroll(player, 27, 63, 1);
    }
    @Test void unchangedSlotOrMissingTargetDoesNotSendScroll() {
        listener.onItemHeld(new PlayerItemHeldEvent(player, 3, 3));
        verifyNoInteractions(mapEngine);
        when(mapEngine.traceDisplayInView(player, 10)).thenReturn(null);
        listener.onItemHeld(new PlayerItemHeldEvent(player, 3, 4));
        verifyNoInteractions(display);
    }
}
