package net.somewhatcity.boiler.core;

import com.google.gson.JsonObject;
import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import net.somewhatcity.boiler.api.IBoilerSource;
import net.somewhatcity.boiler.api.IDisplayManager;
import net.somewhatcity.boiler.api.ISourceManager;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.core.display.ImplBoilerDisplay;
import net.somewhatcity.boiler.core.display.UpdateIntervalManager;
import net.somewhatcity.boiler.core.runtime.BoilerRuntime;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.awt.*;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DisplayLifecycleTest {
    private MockedStatic<Bukkit> bukkit;
    private BoilerRuntime runtime;
    private BoilerPlugin previous;
    private MapEngineApi previousEngine;
    private ImplBoilerDisplay display;
    private IMapDisplay map;
    private IDrawingSpace space;
    private UpdateIntervalManager intervals;
    private World world;
    private final ConcurrentLinkedQueue<Runnable> main = new ConcurrentLinkedQueue<>();
    private final Thread testThread = Thread.currentThread();

    @BeforeEach void setup() throws Exception {
        previous = BoilerPlugin.getPlugin();
        previousEngine = BoilerPlugin.MAP_ENGINE;
        runtime = new BoilerRuntime();
        BoilerPlugin plugin = mock(BoilerPlugin.class);
            org.bukkit.Server server = mock(org.bukkit.Server.class);
            when(plugin.getServer()).thenReturn(server);
            when(server.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("BoilerTest"));
            when(plugin.displayManager()).thenReturn(mock(net.somewhatcity.boiler.api.IDisplayManager.class));
        Field singleton = BoilerPlugin.class.getDeclaredField("plugin");
        singleton.setAccessible(true);
        singleton.set(null, plugin);
        when(plugin.runtime()).thenReturn(runtime);
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(0);
            if (Thread.currentThread() == testThread) action.run(); else main.add(action);
            return null;
        }).when(plugin).onMain(any());
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        MapEngineApi engine = mock(MapEngineApi.class, RETURNS_DEEP_STUBS);
        BoilerPlugin.MAP_ENGINE = engine;
        map = mock(IMapDisplay.class);
        when(map.pixelWidth()).thenReturn(128);
        when(map.pixelHeight()).thenReturn(128);
        when(engine.displayProvider().createBasic(any(), any(), any())).thenReturn(map);
        space = mock(IDrawingSpace.class, RETURNS_DEEP_STUBS);
        when(engine.pipeline().createDrawingSpace(map)).thenReturn(space);
        intervals = mock(UpdateIntervalManager.class);
        when(plugin.intervalManager()).thenReturn(intervals);
        ISourceManager sources = mock(ISourceManager.class);
        when(plugin.sourceManager()).thenReturn(sources);
        doReturn(PlainSource.class).when(sources).source("plain");
        doReturn(SlowSource.class).when(sources).source("slow");
        world = mock(World.class);
        display = new ImplBoilerDisplay(1, new Location(world, 0, 0, 0), new Location(world, 1, 1, 0), BlockFace.NORTH);
        display.persistent(false);
        SlowSource.entered = new CountDownLatch(1);
        SlowSource.release = new CountDownLatch(1);
        SlowSource.unloads.set(0);
        PlainSource.draws.set(0);
        PlainSource.unloads.set(0);
        PlainSource.blockDraw = false;
    }

    @AfterEach void teardown() throws Exception {
        SlowSource.release.countDown();
        PlainSource.releaseDraw.countDown();
        if (display != null) display.remove();
        runtime.close();
        bukkit.close();
        Field singleton = BoilerPlugin.class.getDeclaredField("plugin");
        singleton.setAccessible(true);
        singleton.set(null, previous);
        BoilerPlugin.MAP_ENGINE = previousEngine;
    }

    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        PerformancePrimitivesTest.await(() -> {
            Runnable action;
            while ((action = main.poll()) != null) action.run();
            return condition.getAsBoolean();
        });
    }
    private Player viewer() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        display.viewers().add(player);
        return player;
    }

    @Test void modAndVanillaViewersUseSeparateOutputsAndDisconnectRestoresMaps() throws Exception {
        var plugin=BoilerPlugin.getPlugin();
        var streaming=mock(net.somewhatcity.boiler.core.client.ClientStreamServer.class);
        when(plugin.streamServer()).thenReturn(streaming);
        when(map.width()).thenReturn(1);when(map.height()).thenReturn(1);
        when(map.box()).thenReturn(new org.bukkit.util.BoundingBox(0,0,0,1,1,0));
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        Player mod=mock(Player.class),vanilla=mock(Player.class);
        for(Player player:java.util.List.of(mod,vanilla)) {
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getLocation()).thenReturn(new Location(world,0,0,-2));
        }
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(mod,vanilla));
        when(streaming.available(mod)).thenReturn(true);
        when(streaming.canAttach(eq(mod),anyInt(),anyInt(),anyInt())).thenReturn(true);
        when(streaming.attach(eq(mod),any())).thenReturn(true);
        when(streaming.subscribed(eq(mod),any())).thenReturn(true);
        when(streaming.ready(any())).thenReturn(true);
        display.settings().addProperty("clientScale",2);
        display.source("plain",new JsonObject());await(()->display.source() instanceof PlainSource);
        display.tick();display.render();
        verify(map,never()).spawn(mod);verify(map).spawn(vanilla);
        assertEquals(256,display.frameWidth());assertEquals(256,display.frameHeight());
        await(()->!mockingDetails(streaming).getInvocations().stream().filter(i->i.getMethod().getName().equals("publish")).toList().isEmpty());
        verify(space.ctx(),atLeastOnce()).receivers(argThat(players->players.contains(vanilla)&&!players.contains(mod)));
        when(streaming.available(mod)).thenReturn(false);display.tick();
        verify(streaming).detach(mod,display.id());verify(map).spawn(mod);
        assertEquals(128,display.frameWidth());
    }

    @Test void smartTvWithPersistedSouthZeroRotationUsesTheModOutput() throws Exception {
        display.remove();
        var plugin=BoilerPlugin.getPlugin();
        var streaming=mock(net.somewhatcity.boiler.core.client.ClientStreamServer.class);
        when(plugin.streamServer()).thenReturn(streaming);
        when(map.width()).thenReturn(7);when(map.height()).thenReturn(4);
        when(map.pixelWidth()).thenReturn(896);when(map.pixelHeight()).thenReturn(512);
        when(map.box()).thenReturn(new org.bukkit.util.BoundingBox(-18,64,-11,-11,68,-11));
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        display=new ImplBoilerDisplay(7,new Location(world,-18,64,-11),new Location(world,-12,67,-11),BlockFace.SOUTH);
        display.persistent(false);
        Player mod=mock(Player.class);when(mod.getUniqueId()).thenReturn(UUID.randomUUID());
        when(mod.getLocation()).thenReturn(new Location(world,-15,65,-9));
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(mod));
        when(streaming.available(mod)).thenReturn(true);when(streaming.canAttach(eq(mod),anyInt(),anyInt(),anyInt())).thenReturn(true);
        when(streaming.attach(eq(mod),any())).thenReturn(true);when(streaming.subscribed(eq(mod),any())).thenReturn(true);when(streaming.ready(any())).thenReturn(true);
        var sources=plugin.sourceManager();
        doReturn(net.somewhatcity.boiler.core.sources.SmartTVSource.class).when(sources).source("smarttv");
        JsonObject rotation=new JsonObject();rotation.addProperty("yaw",0);rotation.addProperty("pitch",0);
        display.settings().add("rotation",rotation);display.settings().addProperty("visualDirection","SOUTH");
        display.source("smarttv",new JsonObject());await(()->display.source() instanceof net.somewhatcity.boiler.core.sources.SmartTVSource);
        display.tick();display.render();
        await(()->mockingDetails(streaming).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("publish")));
        verify(streaming).attach(eq(mod),any());verify(map,never()).spawn(mod);
        org.mockito.ArgumentCaptor<net.somewhatcity.boiler.stream.StreamProtocol.Frame> frame=org.mockito.ArgumentCaptor.forClass(net.somewhatcity.boiler.stream.StreamProtocol.Frame.class);
        verify(streaming,atLeastOnce()).publish(any(),frame.capture());
        assertEquals(896,frame.getValue().width());assertEquals(512,frame.getValue().height());
        int[] pixels=new int[896*512];net.somewhatcity.boiler.stream.StreamProtocol.applyRgb(frame.getAllValues().getFirst(),pixels);
        assertTrue(java.util.Arrays.stream(pixels).distinct().count()>16,"SmartTV must produce the composed color menu");
        rotation.addProperty("yaw",45);display.tick();
        verify(streaming).detach(mod,7);verify(map).spawn(mod);
    }

    @Test void nettyInputsOpenAndCloseDialogsOnlyOnTheServerThread() throws Exception {
        BoilerPlugin plugin = BoilerPlugin.getPlugin();
        org.bukkit.Server server = plugin.getServer();
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),anyLong(),anyLong())).thenReturn(mock(org.bukkit.scheduler.BukkitTask.class));
        java.util.List<String> commands = new java.util.ArrayList<>();
        when(server.dispatchCommand(any(),anyString())).thenAnswer(invocation -> {
            assertSame(testThread,Thread.currentThread(),"Dialog command reached Netty");
            commands.add(invocation.getArgument(1)); return true;
        });
        var inputs = new net.somewhatcity.boiler.core.smarttv.SmartTvInputs(plugin);
        when(plugin.smartTvInputs()).thenReturn(inputs);
        ISourceManager sources = plugin.sourceManager();
        doReturn(DialogInputSource.class).when(sources).source("dialog-test");
        display.source("dialog-test",new JsonObject());
        await(() -> display.source() instanceof DialogInputSource);
        DialogInputSource source = (DialogInputSource) display.source();
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("DialogTester");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
        try {
            Thread netty = new Thread(() -> {
                try (MockedStatic<Bukkit> workerBukkit = mockStatic(Bukkit.class)) {
                    workerBukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
                    display.onClick(player,5,5,false);
                    display.onKey(player,"SHIFT");
                    display.onInput(player,"text");
                    display.onScroll(player,5,5,1);
                    display.onRemoteAction(player,net.somewhatcity.boiler.api.util.RemoteAction.HOME);
                }
            },"Netty-test");
            netty.start(); netty.join(2000); assertFalse(netty.isAlive());
            assertTrue(source.events.isEmpty()); assertTrue(commands.isEmpty());
            await(() -> source.events.size() == 5);
            assertEquals(java.util.List.of("click","SHIFT","text","scroll","HOME"),source.events);
            assertEquals(2,commands.size());
            assertTrue(commands.get(0).startsWith("minecraft:dialog show "));
            assertTrue(commands.get(1).startsWith("minecraft:dialog clear "));
        } finally { inputs.close(); }
    }

    @Test void queuedNettyInputIsDiscardedAfterSourceChangeOrRemoval() throws Exception {
        ISourceManager sources = BoilerPlugin.getPlugin().sourceManager();
        doReturn(DialogInputSource.class).when(sources).source("dialog-test");
        display.source("dialog-test",new JsonObject()); await(() -> display.source() instanceof DialogInputSource);
        DialogInputSource old = (DialogInputSource) display.source();
        enqueueNettyKey();
        display.source("plain",new JsonObject()); await(() -> display.source() instanceof PlainSource);
        assertTrue(old.events.isEmpty());
        display.source("dialog-test",new JsonObject()); await(() -> display.source() instanceof DialogInputSource);
        DialogInputSource removed = (DialogInputSource) display.source();
        enqueueNettyKey(); display.remove(); await(() -> main.isEmpty());
        assertTrue(removed.events.isEmpty());
    }

    private void enqueueNettyKey() throws Exception {
        Thread netty = new Thread(() -> {
            try (MockedStatic<Bukkit> workerBukkit = mockStatic(Bukkit.class)) {
                workerBukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
                display.onKey(mock(Player.class),"stale");
            }
        });
        netty.start(); netty.join(2000); assertFalse(netty.isAlive());
    }

    public static class DialogInputSource implements IBoilerSource {
        final java.util.List<String> events = new java.util.ArrayList<>();
        @Override public void load(IBoilerDisplay display,JsonObject data) {}
        @Override public void unload() {}
        @Override public java.util.Set<net.somewhatcity.boiler.api.util.RemoteAction> remoteActions() { return java.util.Set.of(net.somewhatcity.boiler.api.util.RemoteAction.HOME); }
        @Override public void onClick(org.bukkit.command.CommandSender sender,int x,int y,boolean right) {
            events.add("click");
            var inputs = BoilerPlugin.getPlugin().smartTvInputs();
            inputs.request(this,(Player)sender,input -> {},() -> {});
            inputs.show(this,(Player)sender,"Test","Text","","");
        }
        @Override public void onKey(org.bukkit.command.CommandSender sender,String key) { events.add(key); }
        @Override public void onInput(org.bukkit.command.CommandSender sender,String value) { events.add(value); }
        @Override public void onScroll(org.bukkit.command.CommandSender sender,int x,int y,int delta) { events.add("scroll"); }
        @Override public void onRemoteAction(org.bukkit.command.CommandSender sender,net.somewhatcity.boiler.api.util.RemoteAction action) {
            events.add(action.name()); BoilerPlugin.getPlugin().smartTvInputs().cancelPlayer((Player)sender);
        }
    }

    @Test void skipsDrawAndFlushWithoutEligibleRecipients() throws Exception {
        display.source("plain", new JsonObject());
        await(() -> display.source() instanceof PlainSource);
        display.render();
        assertEquals(0, PlainSource.draws.get());
        Player player = viewer();
        when(intervals.getInterval(player)).thenReturn(60_000);
        display.render();
        display.render();
        assertEquals(1, PlainSource.draws.get());
        verify(space, times(1)).flush();
        display.renderPaused(true);
        display.render();
        assertEquals(1, PlainSource.draws.get());
    }

    @Test void differentViewerIntervalsAndRejoinAreIndependent() throws Exception {
        display.source("plain", new JsonObject());
        await(() -> display.source() instanceof PlainSource);
        Player slow = viewer(), fast = viewer();
        when(intervals.getInterval(slow)).thenReturn(60_000);
        display.render();
        display.render();
        var receivers = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(space.ctx(), times(2)).receivers(receivers.capture());
        assertEquals(2, receivers.getAllValues().get(0).size());
        assertEquals(java.util.Set.of(fast), receivers.getAllValues().get(1));
        display.viewers().remove(slow);
        display.render();
        display.viewers().add(slow);
        display.render();
        verify(space.ctx(), atLeastOnce()).receivers(java.util.Set.of(slow, fast));
    }

    @Test void rendersNeverOverlapAndUnloadingWaitsForRendering() throws Exception {
        display.source("plain", new JsonObject());
        await(() -> display.source() instanceof PlainSource);
        viewer();
        PlainSource.blockDraw = true;
        PlainSource.enteredDraw = new CountDownLatch(1);
        PlainSource.releaseDraw = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(display::render);
            assertTrue(PlainSource.enteredDraw.await(2, TimeUnit.SECONDS));
            executor.submit(display::render).get(2, TimeUnit.SECONDS);
            assertEquals(1, PlainSource.draws.get());
            PlainSource.releaseDraw.countDown();
            first.get(2, TimeUnit.SECONDS);
        }
        display.remove();
        display.render();
        display.remove();
        assertEquals(1, PlainSource.unloads.get());
        verify(map, times(1)).destroy();
    }

    @Test void obsoleteLoadingSourceCannotPublishOrSwitchBackLater() throws Exception {
        display.source("slow", new JsonObject());
        assertTrue(SlowSource.entered.await(2, TimeUnit.SECONDS));
        IBoilerDisplay obsolete = SlowSource.captured;
        display.source("plain", new JsonObject());
        SlowSource.release.countDown();
        await(() -> display.source() instanceof PlainSource);
        assertEquals(1, SlowSource.unloads.get());
        obsolete.source("slow", new JsonObject());
        assertEquals("plain", display.sourceName());
    }

    @Test void removalDuringLoadPreventsPublication() throws Exception {
        display.source("slow", new JsonObject());
        assertTrue(SlowSource.entered.await(2, TimeUnit.SECONDS));
        display.remove();
        SlowSource.release.countDown();
        await(() -> SlowSource.unloads.get() == 1);
        assertNull(display.source());
        display.remove();
        verify(map, times(1)).destroy();
    }

    @Test void changingRenderPeriodReschedulesImmediately() throws Exception {
        display.source("plain", new JsonObject());
        await(() -> display.source() instanceof PlainSource);
        viewer();
        JsonObject settings = new JsonObject();
        settings.addProperty("renderPeriod", 1000);
        display.settings(settings);
        await(() -> PlainSource.draws.get() >= 1);
        int first = PlainSource.draws.get();
        Thread.sleep(60);
        assertEquals(first, PlainSource.draws.get());
        JsonObject faster = new JsonObject();
        faster.addProperty("renderPeriod", 5);
        display.settings(faster);
        await(() -> PlainSource.draws.get() >= first + 4);
        display.renderPaused(true);
    }

    @Test void compositeSourceChangesClientRenderingModeWithoutReplacingTheOuterSource() throws Exception {
        ISourceManager sources = BoilerPlugin.getPlugin().sourceManager();
        doReturn(SwitchingSource.class).when(sources).source("composite");
        SwitchingSource.mode = null;
        display.source("composite", new JsonObject());
        await(() -> display.source() instanceof SwitchingSource);
        IBoilerSource outer = display.source();
        Field type = ImplBoilerDisplay.class.getDeclaredField("sourceType"); type.setAccessible(true);
        SwitchingSource.mode = net.somewhatcity.boiler.api.util.SourceType.CLIENT;
        display.tick();
        assertEquals(net.somewhatcity.boiler.api.util.SourceType.CLIENT, type.get(display));
        SwitchingSource.mode = null;
        display.tick();
        assertNull(type.get(display));
        assertEquals("composite", display.sourceName());
        assertSame(outer, display.source());
    }

    public static class SwitchingSource implements IBoilerSource, net.somewhatcity.boiler.core.display.CompositeSource {
        static volatile net.somewhatcity.boiler.api.util.SourceType mode;
        @Override public void load(IBoilerDisplay display, JsonObject data) {}
        @Override public void unload() {}
        @Override public net.somewhatcity.boiler.api.util.SourceType effectiveSourceType() { return mode; }
    }

    @Test void settingsAppOptionsUseTheExistingPipelineAndPersistence() throws Exception {
        display.source("plain", new JsonObject());
        await(() -> display.source() instanceof PlainSource);
        net.somewhatcity.boiler.core.smarttv.DisplaySetting.DITHER.apply(display, "true");
        net.somewhatcity.boiler.core.smarttv.DisplaySetting.BUFFER.apply(display, "false");
        net.somewhatcity.boiler.core.smarttv.DisplaySetting.BUNDLE.apply(display, "true");
        verify(space.ctx(), atLeastOnce()).converter(de.pianoman911.mapengine.api.util.Converter.FLOYD_STEINBERG);
        verify(space.ctx(), atLeastOnce()).buffering(false);
        verify(space.ctx(), atLeastOnce()).bundling(true);
        verify(BoilerPlugin.getPlugin().displayManager(), atLeastOnce()).saveDisplay(display);
        assertEquals("plain", display.sourceName());
    }

    public static class PlainSource implements IBoilerSource {
        static AtomicInteger draws = new AtomicInteger(), unloads = new AtomicInteger();
        static volatile boolean blockDraw;
        static CountDownLatch enteredDraw = new CountDownLatch(0), releaseDraw = new CountDownLatch(0);
        @Override public void load(IBoilerDisplay display, JsonObject data) {}
        @Override public void unload() { unloads.incrementAndGet(); }
        @Override public void draw(Graphics2D graphics, Rectangle viewport) {
            draws.incrementAndGet();
            if (blockDraw) {
                enteredDraw.countDown();
                try { releaseDraw.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }
    public static class SlowSource implements IBoilerSource {
        static CountDownLatch entered, release;
        static AtomicInteger unloads = new AtomicInteger();
        static volatile IBoilerDisplay captured;
        @Override public void load(IBoilerDisplay display, JsonObject data) {
            captured = display;
            entered.countDown();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; }
                catch (InterruptedException ignored) { /* emulate native code completing after interruption */ }
            }
        }
        @Override public void unload() { unloads.incrementAndGet(); }
    }
}
