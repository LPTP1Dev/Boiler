package net.somewhatcity.boiler.core;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.core.audio.BAudioPlayer;
import net.somewhatcity.boiler.core.dj.*;
import net.somewhatcity.boiler.core.sources.DJControllerSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DJControllerSourceTest {
    @TempDir Path directory;
    private Path wave(String name) throws Exception {
        Path path = directory.resolve("media").resolve(name); Files.createDirectories(path.getParent());
        ByteBuffer bytes = ByteBuffer.allocate(96044).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes()).putInt(96036).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1)
                .putInt(48000).putInt(96000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(96000);
        for (int i=0;i<48000;i++) bytes.putShort((short)(10000*Math.sin(2*Math.PI*440*i/48000)));
        return Files.write(path, bytes.array());
    }
    private static Object field(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static DjEngine engine(DJControllerSource source) throws Exception { return (DjEngine)field(source,"engine"); }
    @SuppressWarnings("unchecked") private static String loading(DJControllerSource source, int deck) throws Exception { return ((AtomicReferenceArray<String>)field(source,"loading")).get(deck); }
    private static void click(DJControllerSource source, org.bukkit.entity.Player player, int deck, String id) {
        var r = DjLayout.CONTROLS.stream().filter(c -> c.deck()==deck && c.id().equals(id)).findFirst().orElseThrow().bounds();
        source.onClick(player,(int)r.getCenterX(),(int)r.getCenterY(),false);
    }
    private static void select(SourceTestEnvironment env, DJControllerSource source, org.bukkit.entity.Player player, int deck, String path) throws Exception {
        click(source,player,deck,"load"); env.await(() -> readyBrowser(source));
        for (String segment : path.split("/")) {
            var b=currentBrowser(source);
            int index=-1;
            for (int i=0;i<b.entries().size();i++) if (b.entries().get(i).name().equals(segment)) { index=i; break; }
            assertTrue(index>=0,"Missing browser entry: "+segment);
            for (int i=0;i<Math.floorMod(index-b.selected(),b.entries().size());i++) source.onKey(player,"ARROW_DOWN");
            source.onKey(player,"ENTER");
            env.await(() -> currentBrowser(source)==null || readyBrowser(source));
        }
    }
    @Test void overlayLoadsEachDeckAndDoesNotInterruptPlayback() throws Exception {
        wave("Album/track with spaces.wav"); wave("B.wav");
        try (SourceTestEnvironment env = new SourceTestEnvironment(); MockedConstruction<BAudioPlayer> audios = mockConstruction(BAudioPlayer.class)) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile()); when(env.display.width()).thenReturn(256);
            var player = env.player(); DJControllerSource source = new DJControllerSource();
            try {
                source.load(env.display,new JsonObject()); DjEngine engine = engine(source);
                select(env,source,player,0,"Album/track with spaces.wav");
                env.await(() -> engine.view().decks().get(0).name().equals("track with spaces.wav"));
                assertFalse(engine.view().decks().get(0).playing());
                click(source,player,0,"play"); env.await(() -> engine.view().decks().get(0).position()>0);
                long before=engine.view().decks().get(0).position();
                click(source,player,1,"load");
                env.await(() -> source.remoteActions().contains(net.somewhatcity.boiler.api.util.RemoteAction.OK));
                env.await(() -> engine.view().decks().get(0).position()>before);
                source.onKey(player,"ESCAPE");
                select(env,source,player,1,"B.wav");
                env.await(() -> engine.view().decks().get(1).name().equals("B.wav"));
                assertEquals("track with spaces.wav",engine.view().decks().get(0).name());
                click(source,player,1,"play"); env.await(() -> engine.view().decks().get(1).playing());
                verify(audios.constructed().getFirst(),atLeastOnce()).play(any());
            } finally { source.unload(); }
            env.await(() -> !((Thread)uncheckedField(source,"worker")).isAlive());
            assertTrue(env.dialogs.isEmpty()); verify(audios.constructed().getFirst()).stop();
        }
    }
    private static Object uncheckedField(Object object,String name) { try { return field(object,name); } catch (Exception e) { throw new AssertionError(e); } }
    @Test void loadingChecksPermissionsPathsInvalidFilesAndPreservesOldTrack() throws Exception {
        wave("valid.wav"); Files.writeString(directory.resolve("media/broken.mp3"),"not audio");
        try (SourceTestEnvironment env = new SourceTestEnvironment(); var audios = mockConstruction(BAudioPlayer.class)) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile()); when(env.display.width()).thenReturn(256);
            var player=env.player(); DJControllerSource source=new DJControllerSource();
            try {
                source.load(env.display,new JsonObject()); DjEngine engine=engine(source);
                click(source,player,0,"load"); env.await(() -> readyBrowser(source));
                when(player.hasPermission("boiler.command.source")).thenReturn(false);
                source.onKey(player,"ARROW_DOWN"); source.onKey(player,"ENTER");
                assertEquals("Kein Track",engine.view().decks().get(0).name());
                assertNotNull(currentBrowser(source));
                source.onKey(player,"ESCAPE");
                when(player.hasPermission("boiler.command.source")).thenReturn(true);
                select(env,source,player,0,"valid.wav");
                env.await(() -> engine.view().decks().get(0).name().equals("valid.wav"));
                for (String invalid : new String[]{"broken.mp3"}) {
                    select(env,source,player,0,invalid);
                    env.await(() -> { try { return loading(source,0).equals("Ladefehler"); } catch (Exception e) { throw new AssertionError(e); } });
                    assertEquals("valid.wav",engine.view().decks().get(0).name());
                }
                click(source,player,1,"load"); assertTrue(env.dialogs.isEmpty());
            } finally { source.unload(); }
            assertTrue(env.dialogs.isEmpty());
        }
    }
    @Test void scrollOnlyChangesTargetAndHonorsRightsAndReset() throws Exception {
        try (SourceTestEnvironment env=new SourceTestEnvironment(); var audios=mockConstruction(BAudioPlayer.class)) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile()); when(env.display.width()).thenReturn(256);
            var player=env.player(); DJControllerSource source=new DJControllerSource();
            try {
                source.load(env.display,new JsonObject()); DjEngine engine=engine(source);
                var r = DjLayout.CONTROLS.stream().filter(c -> c.deck()==1 && c.id().equals("tempo")).findFirst().orElseThrow().bounds();
                source.onScroll(player,r.x+2,r.y+2,1);
                env.await(() -> engine.view().decks().get(1).tempo()>.4);
                assertEquals(0,engine.view().decks().get(0).tempo());
                source.onScroll(player,0,0,1); assertEquals(0,engine.view().decks().get(0).tempo());
                when(player.hasPermission("boiler.command.action")).thenReturn(false); source.onScroll(player,r.x+2,r.y+2,1);
                when(player.hasPermission("boiler.command.action")).thenReturn(true); source.onClick(player,r.x+2,r.y+2,true);
                env.await(() -> Math.abs(engine.view().decks().get(1).tempo())<.01);
            } finally { source.unload(); }
        }
    }
    @Test void browserFiltersFilesNavigatesFoldersAndPersistsOnlyDirectories() throws Exception {
        wave("Album/song.wav"); Files.writeString(directory.resolve("media/image.png"),"image");
        try (SourceTestEnvironment env=new SourceTestEnvironment(); var audios=mockConstruction(BAudioPlayer.class)) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile()); when(env.display.width()).thenReturn(256);
            var player=env.player(); DJControllerSource source=new DJControllerSource();
            try {
                source.load(env.display,new JsonObject()); DjEngine engine=engine(source);
                click(source,player,0,"load");
                env.await(() -> readyBrowser(source));
                var browser=(net.somewhatcity.boiler.core.media.MediaBrowser.View)field(source,"browser");
                assertEquals(1,browser.entries().size()); assertEquals("Album",browser.entries().getFirst().name());
                source.onKey(player,"ENTER"); env.await(() -> readyBrowser(source) && currentBrowser(source).directory().equals("Album"));
                source.onKey(player,"ENTER"); env.await(() -> engine.view().decks().get(0).name().equals("song.wav"));
                var captor=org.mockito.ArgumentCaptor.forClass(JsonObject.class);
                verify(env.display,atLeastOnce()).saveSourceData(captor.capture());
                var data=captor.getValue(); assertEquals("Album",data.get("directory0").getAsString()); assertEquals(2,data.size());
            } finally { source.unload(); }
        }
    }
    private static net.somewhatcity.boiler.core.media.MediaBrowser.View currentBrowser(DJControllerSource source) {
        return (net.somewhatcity.boiler.core.media.MediaBrowser.View)uncheckedField(source,"browser");
    }
    private static boolean readyBrowser(DJControllerSource source) { var b=currentBrowser(source); return b!=null && !b.loading(); }
    @Test void newerLoadWinsAndUnloadRejectsLatePreparedTrack() throws Exception {
        wave("first.wav"); wave("second.wav");
        try (SourceTestEnvironment env=new SourceTestEnvironment(); var audios=mockConstruction(BAudioPlayer.class)) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile()); when(env.display.width()).thenReturn(256);
            var player=env.player(); DJControllerSource source=new DJControllerSource();
            try {
                source.load(env.display,new JsonObject()); DjEngine engine=engine(source);
                select(env,source,player,0,"first.wav");
                select(env,source,player,0,"second.wav");
                env.await(() -> engine.view().decks().get(0).name().equals("second.wav"));
                select(env,source,player,1,"first.wav");
                // Do not drain main-thread callbacks until after unloading the source.
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
                while (!loading(source,1).equals("Lade 100%") && System.nanoTime()<deadline) Thread.sleep(5);
                assertEquals("Lade 100%",loading(source,1)); source.unload(); env.drain();
                assertEquals("Kein Track",engine.view().decks().get(1).name());
                env.await(() -> !((Thread)uncheckedField(source,"worker")).isAlive());
                assertTrue(((java.util.Set<?>)field(source,"awaitingInstall")).isEmpty());
            } finally { source.unload(); }
        }
    }
    @Test void smartTvHomeIncludesDjControllerAsToolsAppWithoutStartupParameters() throws Exception {
        try (SourceTestEnvironment env=new SourceTestEnvironment()) {
            env.register("djcontroller",DJControllerSource.class);
            var tv=new net.somewhatcity.boiler.core.sources.SmartTVSource();
            try {
                tv.load(env.display,new JsonObject());
                var menu=uncheckedField(tv,"menu");
                var view=(net.somewhatcity.boiler.core.smarttv.SmartTvMenu.View)uncheckedField(menu,"view");
                assertTrue(view.sources().contains("djcontroller"));
                assertEquals(3,net.somewhatcity.boiler.core.smarttv.SmartTvStyle.category("djcontroller"));
                assertEquals("DJ Controller",net.somewhatcity.boiler.core.smarttv.SmartTvStyle.label("djcontroller"));
            } finally { tv.unload(); }
        }
    }
    @Test void overlayUsesScaledClicksBlocksBackgroundAndClosesWithoutDialog() throws Exception {
        wave("song.wav");
        try (SourceTestEnvironment env=new SourceTestEnvironment(); var audios=mockConstruction(BAudioPlayer.class)) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile()); when(env.display.width()).thenReturn(640); when(env.display.height()).thenReturn(360);
            var player=env.player(); var source=new DJControllerSource(); var layout=new DjLayout(640,360);
            java.util.function.Consumer<java.awt.Rectangle> tap = r -> source.onClick(player,
                    (int)(layout.left+r.getCenterX()*layout.scale), (int)(layout.top+r.getCenterY()*layout.scale), false);
            try {
                source.load(env.display,new JsonObject()); var engine=engine(source);
                var load=DjLayout.CONTROLS.stream().filter(c -> c.deck()==1 && c.id().equals("load")).findFirst().orElseThrow();
                tap.accept(load.bounds()); env.await(() -> readyBrowser(source)); assertTrue(env.dialogs.isEmpty());
                var tempo=DjLayout.CONTROLS.stream().filter(c -> c.deck()==0 && c.id().equals("tempo")).findFirst().orElseThrow();
                source.onClick(player,(int)(layout.left+tempo.bounds().getCenterX()*layout.scale),(int)(layout.top+tempo.bounds().getCenterY()*layout.scale),true);
                assertNotNull(currentBrowser(source)); assertEquals(0,engine.view().decks().get(0).tempo());
                tap.accept(DjBrowser.CLOSE); assertNull(currentBrowser(source));
                tap.accept(load.bounds()); env.await(() -> readyBrowser(source)); tap.accept(DjBrowser.row(0));
                env.await(() -> engine.view().decks().get(1).name().equals("song.wav"));
                assertNull(currentBrowser(source)); assertTrue(env.dialogs.isEmpty());
            } finally { source.unload(); }
        }
    }
    @Test void syncButtonReachesEngineAndUnknownBeatDoesNotChangeTempo() throws Exception {
        wave("A.wav"); wave("B.wav");
        try (SourceTestEnvironment env=new SourceTestEnvironment(); var audios=mockConstruction(BAudioPlayer.class)) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile()); when(env.display.width()).thenReturn(256);
            var source=new DJControllerSource(); var player=env.player();
            try {
                source.load(env.display,new JsonObject()); var engine=engine(source);
                select(env,source,player,0,"A.wav"); select(env,source,player,1,"B.wav");
                env.await(() -> engine.view().decks().get(1).name().equals("B.wav"));
                click(source,player,1,"lead"); click(source,player,0,"sync");
                env.await(() -> engine.view().decks().get(0).error().equals("SYNC: kein Beat"));
                assertEquals(0,engine.view().decks().get(0).tempo(),.0001);
            } finally { source.unload(); }
        }
    }
}
