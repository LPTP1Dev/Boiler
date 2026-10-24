package net.somewhatcity.boiler.core;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.*;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.CommandArgumentType;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.smarttv.SmartTvMenu;
import net.somewhatcity.boiler.core.smarttv.SmartTvSettings;
import net.somewhatcity.boiler.core.smarttv.SmartTvStyle;
import net.somewhatcity.boiler.core.smarttv.DisplaySetting;
import net.somewhatcity.boiler.core.sources.SmartTVSource;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmartTVSourceTest {
    private SourceTestEnvironment env;
    private SmartTVSource tv;
    private Player player;
    private final AtomicReference<JsonObject> saved = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        env = new SourceTestEnvironment();
        env.register("mediaplayer", Recording.class); env.register("youtube-tv", Parameters.class);
        env.registry.register("error", Recording.class, false);
        env.registry.register("default", Recording.class, false);
        env.register("smarttv", SmartTVSource.class);
        player = env.player();
        Recording.loads.set(0); Recording.unloads.set(0); Recording.keys.clear(); Recording.inputs.clear(); Recording.last = null;
        doAnswer(invocation -> { saved.set(((JsonObject) invocation.getArgument(0)).deepCopy()); return null; })
                .when(env.display).saveSourceData(any());
        doAnswer(invocation -> { tv.onInput(invocation.getArgument(0), invocation.getArgument(1)); return null; })
                .when(env.display).onInput(any(), anyString());
        tv = new SmartTVSource(); tv.load(env.display, new JsonObject());
    }
    @AfterEach void teardown() throws Exception { tv.unload(); env.close(); }

    private void awaitPlayback(int loads) throws Exception {
        env.await(() -> {
            tv.onKey(player, "TEST");
            return Recording.loads.get() == loads && Recording.keys.contains("TEST");
        });
        Recording.keys.clear();
    }

    private boolean dialog(Player sender, String input) { return env.submitDialog(sender,input); }

    @Test void homeAndRecentAppsOnlyIncludeAllowedRegisteredSources() throws Exception {
        env.register("browser", Browser.class);
        for (String source : List.of("image", "youtube", "remote", "keyboard", "custom"))
            env.register(source, Recording.class);
        JsonObject state = new JsonObject();
        state.addProperty("selected", "image");
        var recent = new com.google.gson.JsonArray();
        for (String source : List.of("image", "browser", "custom", "mediaplayer", "youtube-tv")) recent.add(source);
        state.add("recent", recent);
        tv.unload(); tv = new SmartTVSource(); tv.load(env.display, state);

        var menuField = SmartTVSource.class.getDeclaredField("menu"); menuField.setAccessible(true);
        var viewField = SmartTvMenu.class.getDeclaredField("view"); viewField.setAccessible(true);
        var view = (SmartTvMenu.View) viewField.get(menuField.get(tv));
        assertEquals(List.of("browser", "mediaplayer", "youtube-tv", SmartTvStyle.SETTINGS), view.sources());
        assertEquals(List.of("browser", "mediaplayer", "youtube-tv"), view.recent());
        assertEquals(Recording.class, env.registry.source("image"));
        tv.onRemoteAction(player, RemoteAction.OK); awaitPlayback(1);
        assertEquals("browser", Recording.last.display.sourceName());
    }

    @Test void navigatesTvAppsAndStopsPlaybackExactlyOnceOnHome() throws Exception {
        tv.onKey(player, "SPACE"); awaitPlayback(1);
        assertEquals("mediaplayer", Recording.last.display.sourceName());
        assertEquals("mediaplayer", saved.get().get("selected").getAsString());
        tv.onKey(player, "W"); assertEquals(List.of("W"), Recording.keys);
        tv.onRemoteAction(player, RemoteAction.HOME);
        assertEquals(1, Recording.unloads.get()); assertFalse(Recording.keys.contains("SHIFT"));
        tv.onKey(player, "A"); tv.onKey(player, "A"); tv.onKey(player, "SPACE");
        assertEquals(1, Recording.loads.get()); // Wraps to yt-tv; hidden/default/smarttv never appear.
        assertTrue(dialog(player, "abbrechen"));
        tv.onKey(player, "D"); tv.onKey(player, "D"); tv.onKey(player, "SPACE"); awaitPlayback(2);
        assertEquals("mediaplayer", Recording.last.display.sourceName());
    }

    @Test void privateDialogsValidateInputRejectOtherPlayersAndRememberParameters() throws Exception {
        tv.onKey(player, "D"); tv.onKey(player, "SPACE");
        Player other = env.player();
        assertFalse(dialog(other, "not a parameter"));
        tv.onInput(other, "https://wrong.invalid");
        assertTrue(dialog(player, "https://example.invalid/video"));
        dialog(player, "not-an-integer");
        dialog(player, "8"); dialog(player, "not-a-boolean"); dialog(player, "true");
        awaitPlayback(1);
        assertEquals("youtube-tv", Recording.last.display.sourceName());
        JsonObject data = Recording.last.data;
        assertEquals("https://example.invalid/video", data.get("url").getAsString());
        assertEquals(8, data.get("buffer").getAsInt()); assertTrue(data.get("loop").getAsBoolean());
        assertEquals(data, saved.get().getAsJsonObject("sources").getAsJsonObject("youtube-tv"));
        assertEquals("youtube-tv", saved.get().getAsJsonArray("recent").get(0).getAsString());
        tv.onRemoteAction(player, RemoteAction.HOME); tv.onKey(player, "SPACE"); awaitPlayback(2);
        assertEquals(data, Recording.last.data);
        JsonObject restored = saved.get().deepCopy();
        tv.unload(); tv = new SmartTVSource(); tv.load(env.display, restored);
        assertEquals(2, Recording.loads.get()); // Restart opens the menu, not a media process.
        tv.onKey(player, "SPACE"); awaitPlayback(3);
        assertEquals(data, Recording.last.data);
    }

    @Test void permissionsAreCheckedForActionsAndSourceChanges() throws Exception {
        when(player.hasPermission("boiler.command.source")).thenReturn(false);
        tv.onKey(player, "SPACE"); assertEquals(0, Recording.loads.get());
        when(player.hasPermission("boiler.command.source")).thenReturn(true);
        when(player.hasPermission("boiler.command.action")).thenReturn(false);
        tv.onKey(player, "SPACE"); assertEquals(0, Recording.loads.get());
        when(player.hasPermission("boiler.command.action")).thenReturn(true);
        tv.onKey(player, "D"); tv.onKey(player, "SPACE");
        dialog(player, "https://example.invalid");
        when(player.hasPermission("boiler.command.source")).thenReturn(false);
        dialog(player, "4");
        assertFalse(dialog(player, "normal chat again"));
        assertEquals(0, Recording.loads.get());
    }

    @Test void appKeysAreUnmodifiedAndOnlyTypedHomeLeavesPlayback() throws Exception {
        tv.onRemoteAction(player, RemoteAction.OK); awaitPlayback(1);
        tv.onKey(player,"SHIFT"); tv.onKey(player,"ESCAPE"); tv.onKey(player,"HOME");
        tv.onRemoteAction(player,RemoteAction.BACK); tv.onRemoteAction(player,RemoteAction.OK);
        assertEquals(List.of("SHIFT","ESCAPE","HOME","ESCAPE","ENTER"),Recording.keys);
        assertEquals(0,Recording.unloads.get());
        IBoilerDisplay old = Recording.last.display;
        tv.onRemoteAction(player,RemoteAction.HOME); tv.onRemoteAction(player,RemoteAction.HOME);
        old.source("mediaplayer",new JsonObject()); env.drain();
        assertEquals(1,Recording.loads.get()); assertEquals(1,Recording.unloads.get());
        assertFalse(tv.remoteActions().contains(RemoteAction.STOP));
    }

    @Test void stopCanRestartAndHomeProtectsAnotherPlayersPrivateTextInput() throws Exception {
        tv.onRemoteAction(player,RemoteAction.OK); awaitPlayback(1);
        tv.onRemoteAction(player,RemoteAction.TEXT_INPUT);
        tv.onRemoteAction(env.player(),RemoteAction.HOME);
        assertEquals(0,Recording.unloads.get());
        assertTrue(dialog(player,"text for the app"));
        assertEquals(List.of("text for the app"),Recording.inputs);
        tv.onRemoteAction(player,RemoteAction.TEXT_INPUT);
        tv.onRemoteAction(player,RemoteAction.HOME);
        assertFalse(dialog(player,"ordinary chat"));
        tv.onRemoteAction(player,RemoteAction.OK); awaitPlayback(2);
        tv.onRemoteAction(player,RemoteAction.STOP);
        assertEquals(2,Recording.unloads.get());
        assertTrue(tv.remoteActions().contains(RemoteAction.PLAY_PAUSE));
        tv.onKey(player,"SHIFT"); assertTrue(Recording.keys.isEmpty());
        tv.onRemoteAction(player,RemoteAction.PLAY_PAUSE); awaitPlayback(3);
        assertEquals("mediaplayer",Recording.last.display.sourceName());
        tv.onRemoteAction(player,RemoteAction.HOME);
        assertEquals(3,Recording.unloads.get());
    }

    @Test void homeCancelsLoadingAndFailedAppsWithoutLateResurrection() throws Exception {
        SourceSessionTest.Slow.entered = new java.util.concurrent.CountDownLatch(1);
        SourceSessionTest.Slow.release = new java.util.concurrent.CountDownLatch(1);
        SourceSessionTest.Slow.unloads.set(0);
        env.register("browser",SourceSessionTest.Slow.class);
        try {
            tv.onRemoteAction(player,RemoteAction.OK);
            assertTrue(SourceSessionTest.Slow.entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            tv.onRemoteAction(player,RemoteAction.HOME);
            SourceSessionTest.Slow.release.countDown();
            env.await(() -> SourceSessionTest.Slow.unloads.get() == 1);
            assertFalse(tv.remoteActions().contains(RemoteAction.STOP));
            SourceSessionTest.Slow.captured.source("mediaplayer",new JsonObject()); env.drain();
            assertEquals(0,Recording.loads.get());
            env.register("browser",SourceSessionTest.Broken.class);
            SourceSessionTest.Broken.unloads.set(0);
            tv.onRemoteAction(player,RemoteAction.OK);
            env.await(() -> SourceSessionTest.Broken.unloads.get() == 1);
            tv.onRemoteAction(player,RemoteAction.HOME);
            assertFalse(tv.remoteActions().contains(RemoteAction.STOP));
        } finally { SourceSessionTest.Slow.release.countDown(); }
    }

    @Test void homeFromPausedOrStoppedAppsAndInternalRedirectReleasesTextCapture() throws Exception {
        env.register("mediaplayer",MediaRecording.class);
        tv.onRemoteAction(player,RemoteAction.OK); awaitPlayback(1);
        tv.onRemoteAction(player,RemoteAction.PLAY_PAUSE);
        assertTrue(((MediaRecording)Recording.last).paused);
        tv.onRemoteAction(player,RemoteAction.HOME);
        assertEquals(1,Recording.unloads.get());
        tv.onRemoteAction(player,RemoteAction.OK); awaitPlayback(2);
        tv.onRemoteAction(player,RemoteAction.TEXT_INPUT);
        Recording.last.display.source("mediaplayer",new JsonObject());
        env.await(() -> Recording.loads.get() == 3);
        assertFalse(dialog(player,"normal after redirect"));
        tv.onRemoteAction(player,RemoteAction.STOP);
        assertTrue(tv.remoteActions().contains(RemoteAction.PLAY_PAUSE));
        tv.onRemoteAction(player,RemoteAction.HOME);
        assertFalse(tv.remoteActions().contains(RemoteAction.PLAY_PAUSE));
        assertEquals(3,Recording.unloads.get());
    }

    @Test void closingSessionAndUnloadReleaseDialog() {
        tv.onKey(player, "D"); tv.onKey(player, "SPACE");
        env.inputs.cancelPlayer(player);
        assertFalse(dialog(player, "normal"));
        tv.onKey(player, "SPACE");
        tv.unload();
        assertFalse(dialog(player, "normal"));
    }

    @Test void clickHitTestingUsesTheSameTilesAsPainting() throws Exception {
        SmartTvMenu.Layout layout = SmartTvMenu.layout(128, 128);
        Rectangle first = layout.tile(0);
        tv.onClick(player, first.x + 3, first.y + 3, false);
        awaitPlayback(1); assertEquals("mediaplayer", Recording.last.display.sourceName());
    }

    @Test void parsesEveryArgumentTypeAndRejectsInvalidOrNonFiniteValues() {
        for (CreateArgument argument : AllTypes.class.getAnnotation(CreateCommandArguments.class).arguments()) {
            JsonObject data = new JsonObject();
            String value = switch (argument.type()) {
                case INTEGER -> "-42";
                case BOOLEAN -> "false";
                case DOUBLE -> "1.25";
                default -> "value with spaces";
            };
            SmartTVSource.parse(value, argument, data);
            assertTrue(data.has(argument.name()));
            if (argument.type() == CommandArgumentType.DOUBLE) {
                assertThrows(IllegalArgumentException.class, () -> SmartTVSource.parse("NaN", argument, data));
                assertThrows(IllegalArgumentException.class, () -> SmartTVSource.parse("Infinity", argument, data));
            }
            if (argument.type() == CommandArgumentType.INTEGER)
                assertThrows(IllegalArgumentException.class, () -> SmartTVSource.parse("2147483648", argument, data));
        }
    }

    @Test void rendersReadableMenuAtMapAndWideDisplaySizes() throws Exception {
        SmartTvMenu menu = new SmartTvMenu();
        List<String> apps = List.of("browser", "mediaplayer", "youtube-tv", SmartTvStyle.SETTINGS);
        menu.view(new SmartTvMenu.View(apps, 1, "", false, List.of("browser", "mediaplayer", "youtube-tv"), 0, SmartTvMenu.Focus.APPS, 0, null));
        Path directory = Path.of("build", "reports", "smarttv-preview"); Files.createDirectories(directory);
        for (int[] size : new int[][]{{128,128}, {384,128}, {384,256}, {256,384}, {768,512}, {1024,576}}) {
            int width = size[0], height = size[1];
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try { menu.draw(graphics, new Rectangle(0, 0, width, height)); } finally { graphics.dispose(); }
            javax.imageio.ImageIO.write(image, "png", directory.resolve("menu-" + width + "x" + height + ".png").toFile());
            SmartTvMenu.Layout layout = SmartTvMenu.layout(width, height);
            for (int slot = 0; slot < layout.pageSize(); slot++) {
                Rectangle tile = layout.tile(slot);
                assertTrue(new Rectangle(0, 0, width, height).contains(tile));
                assertEquals(slot, layout.hit(tile.x + 1, tile.y + 1, 0, 100));
            }
            SmartTvSettings.View settings = new SmartTvSettings.View(Arrays.stream(DisplaySetting.values()).map(option -> option.value(env.display)).toList(), 0);
            menu.view(new SmartTvMenu.View(apps, 1, "", false, List.of("browser", "mediaplayer", "youtube-tv"), 0, SmartTvMenu.Focus.APPS, 0, settings));
            graphics = image.createGraphics();
            try { menu.draw(graphics, new Rectangle(0, 0, width, height)); } finally { graphics.dispose(); }
            javax.imageio.ImageIO.write(image, "png", directory.resolve("settings-" + width + "x" + height + ".png").toFile());
            menu.view(new SmartTvMenu.View(apps, 1, "", false, List.of("browser", "mediaplayer", "youtube-tv"), 0, SmartTvMenu.Focus.APPS, 0, null));
        }
    }

    @Test void settingsAppWorksWithoutSourcePermissionAndPreservesSourceData() {
        when(player.hasPermission("boiler.command.source")).thenReturn(false);
        tv.onKey(player, "A"); tv.onKey(player, "SPACE"); // Settings is the last app.
        tv.onKey(player, "SPACE");
        assertTrue(env.display.settings().get("dither").getAsBoolean());
        assertNull(saved.get()); assertEquals(0, Recording.loads.get());
        when(player.hasPermission("boiler.command.settings")).thenReturn(false);
        tv.onKey(player, "SPACE");
        assertTrue(env.display.settings().get("dither").getAsBoolean());
        tv.onRemoteAction(player, RemoteAction.BACK);
        when(player.hasPermission("boiler.command.settings")).thenReturn(true);
        tv.onKey(player, "SPACE"); tv.onKey(player, "SPACE");
        assertFalse(env.display.settings().get("dither").getAsBoolean());
    }

    @Test void preciseSettingsInputIsPrivateValidatedAndCancelledWithTheSession() {
        tv.onKey(player, "A"); tv.onKey(player, "SPACE");
        for (int index = 0; index < DisplaySetting.RENDER_PERIOD.ordinal(); index++) tv.onKey(player, "S");
        tv.onKey(player, "SPACE");
        tv.onInput(env.player(), "1"); assertFalse(env.display.settings().has("renderPeriod"));
        assertTrue(dialog(player, "NaN"));
        dialog(player, "0"); assertFalse(env.display.settings().has("renderPeriod"));
        dialog(player, "33"); assertEquals(33, env.display.settings().get("renderPeriod").getAsInt());
        assertFalse(dialog(player, "normal"));
        tv.onKey(player, "SPACE"); env.inputs.cancelPlayer(player);
        assertFalse(dialog(player, "normal"));
    }

    @Test void browserLaunchesGoogleWithoutAUrlDialogEvenWithRememberedUrl() throws Exception {
        env.register("browser",Browser.class);
        JsonObject state = new JsonObject(), parameters = new JsonObject(), browser = new JsonObject();
        browser.addProperty("url","https://old.example.invalid"); parameters.add("browser",browser);
        state.add("sources",parameters); state.addProperty("selected","browser");
        tv.unload(); tv = new SmartTVSource(); tv.load(env.display,state);
        tv.onRemoteAction(player,RemoteAction.OK); awaitPlayback(1);
        assertEquals("browser",Recording.last.display.sourceName());
        assertEquals("https://www.google.com/",Recording.last.data.get("url").getAsString());
        assertTrue(env.dialogs.isEmpty());
        tv.onRemoteAction(player,RemoteAction.HOME);
        Rectangle tile = SmartTvMenu.layout(128,128).tile(0); // Browser remains selected after Home.
        tv.onClick(player,tile.x+2,tile.y+2,true); awaitPlayback(2);
        assertEquals("https://www.google.com/",Recording.last.data.get("url").getAsString());
        assertTrue(env.dialogs.isEmpty());
    }

    @Test void largeHomeFiltersAppsAndOpensSettingsUsingSidebarOrRemote() throws Exception {
        when(env.display.width()).thenReturn(384); when(env.display.height()).thenReturn(256);
        env.register("youtube-tv", Recording.class);
        tv.onKey(player, "W"); tv.onKey(player, "D"); tv.onKey(player, "SPACE"); // Video filter.
        tv.onKey(player, "SPACE"); awaitPlayback(1);
        assertEquals("mediaplayer", Recording.last.display.sourceName());
        tv.onRemoteAction(player, RemoteAction.HOME);
        SmartTvMenu.Layout layout = SmartTvMenu.layout(384,256);
        Rectangle gear = layout.sidebar(2);
        tv.onClick(player, gear.x + 2, gear.y + 2, false);
        tv.onKey(player, "SPACE"); assertTrue(env.display.settings().get("dither").getAsBoolean());
        tv.onRemoteAction(player, RemoteAction.BACK);
        tv.onKey(player, "W"); tv.onKey(player, "W"); tv.onKey(player, "W"); tv.onKey(player, "SPACE"); // Sidebar wraps Home -> Settings.
        tv.onKey(player, "SPACE"); assertFalse(env.display.settings().get("dither").getAsBoolean());
    }

    @Test void settingsOverlayKeepsTheSameAppRenderingAndPlayingAudioAndCapturesUiInput() throws Exception {
        env.register("mediaplayer",OverlayRecording.class);
        tv.onRemoteAction(player,RemoteAction.OK); awaitPlayback(1);
        OverlayRecording app = (OverlayRecording)Recording.last;
        tv.onRemoteAction(player,RemoteAction.SETTINGS);
        assertEquals(0,Recording.unloads.get()); assertSame(app,Recording.last);
        assertArrayEquals(new byte[]{1,2},tv.provide20msAudio());
        tv.onRemoteAction(player,RemoteAction.OK);
        assertTrue(env.display.settings().get("dither").getAsBoolean());
        assertTrue(Recording.keys.isEmpty());
        tv.onKey(player,"ARROW_DOWN"); tv.onScroll(player,10,10,1); tv.onInput(player,"do not send to app");
        tv.onCursorMove(player,2,2); assertTrue(Recording.keys.isEmpty()); assertTrue(Recording.inputs.isEmpty()); assertEquals(0,app.cursors);
        BufferedImage image = new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try { tv.draw(graphics,new Rectangle(0,0,128,128)); tv.draw(graphics,new Rectangle(0,0,128,128)); }
        finally { graphics.dispose(); }
        assertEquals(2,app.frames);
        var space = mock(de.pianoman911.mapengine.api.drawing.IDrawingSpace.class);
        tv.draw(space);
        verify(space,times(2)).image(any(BufferedImage.class),eq(0),eq(0)); // App first, overlay second.
        verify(space,times(1)).clear(); // Overlay must never erase custom drawing.
        tv.onRemoteAction(player,RemoteAction.BACK);
        assertEquals(1,Recording.loads.get()); assertEquals(0,Recording.unloads.get());
        tv.onKey(player,"SHIFT"); assertEquals(List.of("SHIFT"),Recording.keys);
        tv.onCursorMove(player,2,2); assertEquals(1,app.cursors);
        tv.onRemoteAction(player,RemoteAction.HOME); assertEquals(1,Recording.unloads.get());
    }

    @Test void settingsOverlayCanToggleAndCloseWithEscapeWithoutRestartingClientApp() throws Exception {
        env.register("mediaplayer",ClientRecording.class);
        tv.onRemoteAction(player,RemoteAction.OK); awaitPlayback(1);
        assertEquals(net.somewhatcity.boiler.api.util.SourceType.CLIENT,tv.effectiveSourceType());
        tv.onRemoteAction(player,RemoteAction.SETTINGS); assertNull(tv.effectiveSourceType());
        tv.onKey(player,"ESCAPE");
        assertEquals(net.somewhatcity.boiler.api.util.SourceType.CLIENT,tv.effectiveSourceType());
        tv.onRemoteAction(player,RemoteAction.SETTINGS); tv.onRemoteAction(player,RemoteAction.SETTINGS);
        assertEquals(1,Recording.loads.get()); assertEquals(0,Recording.unloads.get());
        assertEquals(net.somewhatcity.boiler.api.util.SourceType.CLIENT,tv.effectiveSourceType());
    }

    @Test void forwardsDragLifecycleToPlayingApp() throws Exception {
        env.register("mediaplayer",OverlayRecording.class);
        tv.onRemoteAction(player,RemoteAction.OK); awaitPlayback(1);
        var app=(OverlayRecording)Recording.last;
        for(var phase:net.somewhatcity.boiler.api.util.DragPhase.values())tv.onDrag(player,10,20,false,phase);
        assertEquals(3,app.drags);
        tv.onRemoteAction(player,RemoteAction.SETTINGS);
        tv.onDrag(player,10,20,false,net.somewhatcity.boiler.api.util.DragPhase.MOVE);
        assertEquals(3,app.drags);
    }

    public static class OverlayRecording extends Recording {
        int frames,cursors,drags;
        @Override public void onDrag(CommandSender sender,int x,int y,boolean right,net.somewhatcity.boiler.api.util.DragPhase phase) { drags++; }
        @Override public void draw(Graphics2D graphics,Rectangle viewport) { frames++; graphics.setColor(new Color(20*frames,80,140)); graphics.fillRect(0,0,viewport.width,viewport.height); }
        @Override public byte[] provide20msAudio() { return new byte[]{1,2}; }
        @Override public void onCursorMove(CommandSender sender,int x,int y) { cursors++; }
    }

    @Test void mediaAppOwnsFileTransportAndSurvivesSettingsOverlay(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        when(env.plugin.getDataFolder()).thenReturn(directory.toFile());
        env.register("mediaplayer",MediaPlayerTest.FakePlayerSource.class);
        MediaPlayerTest.Leaf.loads.set(0); MediaPlayerTest.Leaf.unloads.set(0);
        Files.createDirectories(directory.resolve("media")); Files.writeString(directory.resolve("media/movie.mp4"),"video");
        JsonObject state=new JsonObject(); state.addProperty("selected","mediaplayer");
        tv.unload(); tv=new SmartTVSource(); tv.load(env.display,state);
        tv.onRemoteAction(player,RemoteAction.OK);
        env.await(() -> saved.get()!=null && saved.get().getAsJsonObject("sources").has("mediaplayer") && saved.get().getAsJsonObject("sources").getAsJsonObject("mediaplayer").has("directory"));
        tv.onRemoteAction(player,RemoteAction.OK); env.await(() -> MediaPlayerTest.Leaf.loads.get()==1 && tv.remoteActions().contains(RemoteAction.PLAY_PAUSE));
        tv.onRemoteAction(player,RemoteAction.SETTINGS); tv.onRemoteAction(player,RemoteAction.BACK);
        assertEquals(0,MediaPlayerTest.Leaf.unloads.get());
        tv.onRemoteAction(player,RemoteAction.STOP); assertEquals(1,MediaPlayerTest.Leaf.unloads.get());
        tv.onRemoteAction(player,RemoteAction.PLAY_PAUSE); env.await(() -> MediaPlayerTest.Leaf.loads.get()==2 && tv.remoteActions().contains(RemoteAction.SEEK_FORWARD));
        MediaPlayerTest.LeafBackend.last.output.ended();
        env.await(() -> MediaPlayerTest.Leaf.unloads.get()==2 && tv.remoteActions().contains(RemoteAction.PLAY_PAUSE));
        tv.onRemoteAction(player,RemoteAction.PLAY_PAUSE); env.await(() -> MediaPlayerTest.Leaf.loads.get()==3 && tv.remoteActions().contains(RemoteAction.SEEK_FORWARD));
        tv.onRemoteAction(player,RemoteAction.HOME); assertEquals(3,MediaPlayerTest.Leaf.unloads.get());
    }
    @SourceConfig(sourceType=net.somewhatcity.boiler.api.util.SourceType.CLIENT)
    public static class ClientRecording extends Recording {}

    public static class Recording implements IBoilerSource {
        static final AtomicInteger loads = new AtomicInteger(), unloads = new AtomicInteger();
        static final List<String> keys = new CopyOnWriteArrayList<>();
        static final List<String> inputs = new CopyOnWriteArrayList<>();
        static volatile Recording last;
        IBoilerDisplay display;
        JsonObject data;
        @Override public void load(IBoilerDisplay display, JsonObject data) {
            this.display = display; this.data = data.deepCopy(); last = this; loads.incrementAndGet();
        }
        @Override public void unload() { unloads.incrementAndGet(); }
        @Override public void onKey(CommandSender sender, String key) { keys.add(key); }
        @Override public void onInput(CommandSender sender, String input) { inputs.add(input); }
    }
    @CreateCommandArguments(arguments = {
            @CreateArgument(name = "url", type = CommandArgumentType.GREEDY_STRING),
            @CreateArgument(name = "buffer", type = CommandArgumentType.INTEGER),
            @CreateArgument(name = "loop", type = CommandArgumentType.BOOLEAN)
    })
    public static class Parameters extends Recording {}
    @CreateCommandArguments(arguments = {@CreateArgument(name = "url", type = CommandArgumentType.GREEDY_STRING)})
    public static class Browser extends Recording {}
    public static class MediaRecording extends Recording {
        boolean paused;
        @Override public java.util.Set<RemoteAction> remoteActions() { return java.util.Set.of(RemoteAction.PLAY_PAUSE); }
        @Override public void onRemoteAction(CommandSender sender,RemoteAction action) { if (action == RemoteAction.PLAY_PAUSE) paused = !paused; }
    }
    @CreateCommandArguments(arguments = {
            @CreateArgument(name = "string", type = CommandArgumentType.STRING),
            @CreateArgument(name = "greedy", type = CommandArgumentType.GREEDY_STRING),
            @CreateArgument(name = "integer", type = CommandArgumentType.INTEGER),
            @CreateArgument(name = "bool", type = CommandArgumentType.BOOLEAN),
            @CreateArgument(name = "double", type = CommandArgumentType.DOUBLE)
    })
    static class AllTypes {}
}
