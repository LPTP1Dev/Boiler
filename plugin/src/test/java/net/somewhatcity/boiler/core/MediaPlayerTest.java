package net.somewhatcity.boiler.core;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.IBoilerSource;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.media.MediaLibrary;
import net.somewhatcity.boiler.core.media.MediaBrowser;
import net.somewhatcity.boiler.core.sources.MediaPlayerSource;
import net.somewhatcity.boiler.core.media.playback.*;
import net.somewhatcity.boiler.core.media.PlaybackState;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaPlayerTest {
    @Test void libraryCreatesRootSortsFoldersAndConfinesAllPaths(@TempDir Path directory) throws Exception {
        MediaLibrary library=new MediaLibrary(directory);
        Path root=directory.resolve("media");
        Files.createDirectory(root.resolve("Album"));
        Files.writeString(root.resolve("z.MP3"),"audio"); Files.writeString(root.resolve("a.png"),"image"); Files.writeString(root.resolve("notes.txt"),"other");
        var entries=library.list("");
        assertEquals(List.of("Album","a.png","notes.txt","z.MP3"),entries.stream().map(MediaLibrary.Entry::name).toList());
        assertEquals(MediaLibrary.Kind.DIRECTORY,entries.get(0).kind()); assertEquals(MediaLibrary.Kind.IMAGE,entries.get(1).kind());
        assertEquals(MediaLibrary.Kind.OTHER,entries.get(2).kind()); assertEquals(MediaLibrary.Kind.AUDIO,entries.get(3).kind());
        assertEquals(MediaLibrary.Kind.VIDEO,MediaLibrary.kind(Path.of("movie.MKV")));
        assertThrows(java.io.IOException.class,() -> library.resolve("../secret.txt"));
        assertThrows(java.io.IOException.class,() -> library.resolve(directory.toString()));
        assertThrows(java.io.IOException.class,() -> library.list("a.png"));
        assertEquals("Album",MediaLibrary.parent("Album/Disc1")); assertEquals("",MediaLibrary.parent("Album"));
    }

    @Test void symlinkToOutsideTheMediaRootIsNeverListedOrResolved(@TempDir Path directory) throws Exception {
        MediaLibrary library=new MediaLibrary(directory);
        Path outside=directory.resolve("outside.mp4"); Files.writeString(outside,"outside");
        Path link=directory.resolve("media/link.mp4");
        try { Files.createSymbolicLink(link,outside); }
        catch(java.io.IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: "+unavailable.getMessage()); return;
        }
        assertThrows(java.io.IOException.class,() -> library.resolve("link.mp4")); assertTrue(library.list("").isEmpty());
    }

    @Test void localFilesUseExistingPlayersAndBackStopReplayKeepLibraryAlive(@TempDir Path directory) throws Exception {
        try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile());
            env.register("image",Leaf.class);
            Leaf.loads.set(0); Leaf.unloads.set(0); Leaf.actions.clear();
            Path root=directory.resolve("media"); Files.createDirectories(root.resolve("Album"));
            Files.writeString(root.resolve("Album/track with spaces.mp3"),"audio");
            AtomicReference<JsonObject> saved=new AtomicReference<>();
            doAnswer(invocation -> { saved.set(invocation.getArgument(0)); return null; }).when(env.display).saveSourceData(any());
            var sender=env.player(); MediaPlayerSource source=new FakePlayerSource();
            try {
                source.load(env.display,new JsonObject()); env.await(() -> saved.get()!=null);
                source.onRemoteAction(sender,RemoteAction.OK); env.await(() -> "Album".equals(saved.get().get("directory").getAsString()));
                source.onRemoteAction(sender,RemoteAction.OK); env.await(() -> Leaf.loads.get()==1 && source.remoteActions().contains(RemoteAction.PLAY_PAUSE));
                assertEquals(root.resolve("Album/track with spaces.mp3").toRealPath().toString(),Leaf.last.data.get("url").getAsString());
                assertEquals("ffmpeg",((FakePlayerSource)source).selectedBackend);
                assertFalse(Leaf.last.data.get("live").getAsBoolean());
                source.onRemoteAction(sender,RemoteAction.SEEK_FORWARD); assertEquals(List.of(RemoteAction.SEEK_FORWARD),Leaf.actions);
                source.onRemoteAction(sender,RemoteAction.STOP); assertEquals(1,Leaf.unloads.get());
                source.onRemoteAction(sender,RemoteAction.PLAY_PAUSE); env.await(() -> Leaf.loads.get()==2 && source.remoteActions().contains(RemoteAction.SEEK_FORWARD));
                source.onKey(sender,"ESCAPE"); assertEquals(2,Leaf.unloads.get());
                env.await(() -> !source.remoteActions().contains(RemoteAction.STOP));
                source.onKey(sender,"ARROW_LEFT"); env.await(() -> saved.get().get("directory").getAsString().isEmpty());
                assertFalse(saved.get().has("url"));
            } finally { source.unload(); }
            assertEquals(2,Leaf.unloads.get());
        }
    }

    @Test void imageClicksCheckPermissionsAndLateFileResultsCannotPlayAfterUnload(@TempDir Path directory) throws Exception {
        try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile()); env.register("image",Leaf.class);
            Leaf.loads.set(0); Leaf.unloads.set(0);
            Files.createDirectories(directory.resolve("media")); Files.writeString(directory.resolve("media/a.png"),"image");
            AtomicInteger scans=new AtomicInteger(); doAnswer(invocation -> { scans.incrementAndGet(); return null; }).when(env.display).saveSourceData(any());
            var sender=env.player(); MediaPlayerSource source=new MediaPlayerSource(); source.load(env.display,new JsonObject());
            env.await(() -> scans.get()==1);
            Rectangle row=new MediaBrowser.Layout(128,128).row(0);
            when(sender.hasPermission("boiler.command.source")).thenReturn(false);
            source.onClick(sender,row.x+2,row.y+2,false); assertEquals(0,Leaf.loads.get());
            when(sender.hasPermission("boiler.command.source")).thenReturn(true);
            source.onClick(sender,row.x+2,row.y+2,false); env.await(() -> Leaf.loads.get()==1);
            assertTrue(Leaf.last.data.get("url").getAsString().startsWith("file:"));
            source.onKey(sender,"ESCAPE"); env.await(() -> scans.get()==2);
            source.onRemoteAction(sender,RemoteAction.OK); source.unload();
            env.drain(); assertEquals(1,Leaf.loads.get());
            assertTrue(source.remoteActions().isEmpty());
        }
    }

    @Test void browserLayoutsShareClickRowsAndGeneratePixelPreviews() throws Exception {
        var entries=List.of(new MediaLibrary.Entry("Music","Musik",MediaLibrary.Kind.DIRECTORY,0),new MediaLibrary.Entry("a.png","Urlaub.png",MediaLibrary.Kind.IMAGE,123456),new MediaLibrary.Entry("b.mp4","Sommer am See.mp4",MediaLibrary.Kind.VIDEO,12345678),new MediaLibrary.Entry("c.mp3","Lieblingslied.mp3",MediaLibrary.Kind.AUDIO,1234567));
        Path reports=Path.of("build/reports/mediaplayer-preview"); Files.createDirectories(reports);
        for(int[] size:new int[][]{{128,128},{384,128},{384,256},{256,384},{768,512}}) {
            MediaBrowser.Layout layout=new MediaBrowser.Layout(size[0],size[1]);
            for(int row=0;row<layout.rows();row++) assertTrue(new Rectangle(0,0,size[0],size[1]).contains(layout.row(row)));
            BufferedImage image=new BufferedImage(size[0],size[1],BufferedImage.TYPE_INT_RGB); Graphics2D g=image.createGraphics();
            try { MediaBrowser.draw(g,size[0],size[1],new MediaBrowser.View("",entries,1,"",false)); } finally { g.dispose(); }
            javax.imageio.ImageIO.write(image,"png",reports.resolve(size[0]+"x"+size[1]+".png").toFile());
        }
    }

    @Test void realLocalPngCanBeDisplayedAndEofCanBeRestarted(@TempDir Path directory) throws Exception {
        try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile());
            env.register("image",net.somewhatcity.boiler.core.sources.ImageSource.class);
            Files.createDirectories(directory.resolve("media"));
            BufferedImage png=new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB); Graphics2D paint=png.createGraphics();
            paint.setColor(Color.RED); paint.fillRect(0,0,16,16); paint.dispose();
            javax.imageio.ImageIO.write(png,"png",directory.resolve("media/red image.png").toFile());
            AtomicInteger scans=new AtomicInteger(); doAnswer(invocation -> { scans.incrementAndGet(); return null; }).when(env.display).saveSourceData(any());
            MediaPlayerSource source=new MediaPlayerSource(); var sender=env.player();
            try {
                source.load(env.display,new JsonObject()); env.await(() -> scans.get()==1);
                source.onRemoteAction(sender,RemoteAction.OK);
                BufferedImage output=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);
                env.await(() -> { Graphics2D g=output.createGraphics(); try { source.draw(g,new Rectangle(0,0,128,128)); } finally { g.dispose(); } return (output.getRGB(64,64)&0xffffff)==0xff0000; });
                source.onRemoteAction(sender,RemoteAction.BACK); assertFalse(source.remoteActions().contains(RemoteAction.STOP));
            } finally { source.unload(); }
        }
    }

    public static class FakePlayerSource extends MediaPlayerSource {
        volatile String selectedBackend;
        @Override protected PlaybackBackend createBackend(String name) { selectedBackend=name; return new LeafBackend(); }
    }
    public static class LeafBackend implements PlaybackBackend {
        static volatile LeafBackend last;
        private Leaf leaf;
        PlaybackOutput output;
        @Override public void start(IBoilerDisplay display,JsonObject data,PlaybackOutput output) { this.output=output; last=this; leaf=new Leaf(); leaf.load(display,data); }
        @Override public void close() { if(leaf!=null) { leaf.unload(); leaf=null; } }
        @Override public void action(RemoteAction action) { Leaf.actions.add(action); }
        @Override public Set<RemoteAction> remoteActions() { return Set.of(RemoteAction.PLAY_PAUSE,RemoteAction.SEEK_FORWARD); }
        @Override public PlaybackState playbackState() { return new PlaybackState(500_000,1_000_000,false,new float[24]); }
        @Override public void draw(Graphics2D graphics,Rectangle viewport) {}
    }
    public static class Leaf implements IBoilerSource {
        static final AtomicInteger loads=new AtomicInteger(),unloads=new AtomicInteger();
        static final List<RemoteAction> actions=new java.util.concurrent.CopyOnWriteArrayList<>();
        static volatile Leaf last;
        IBoilerDisplay display; JsonObject data;
        @Override public void load(IBoilerDisplay display,JsonObject data) { this.display=display; this.data=data.deepCopy(); last=this; loads.incrementAndGet(); }
        @Override public void unload() { unloads.incrementAndGet(); }
        @Override public Set<RemoteAction> remoteActions() { return Set.of(RemoteAction.PLAY_PAUSE,RemoteAction.SEEK_FORWARD); }
        @Override public void onRemoteAction(CommandSender sender,RemoteAction action) { actions.add(action); }
    }
}
