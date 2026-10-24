package net.somewhatcity.boiler.core.sources;

import com.google.gson.JsonObject;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import net.somewhatcity.boiler.api.IBoilerSource;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.BoilerConfig;
import net.somewhatcity.boiler.core.media.playback.*;
import net.somewhatcity.boiler.core.Util;
import net.somewhatcity.boiler.core.display.RasterRenderer;
import net.somewhatcity.boiler.core.display.SourceSession;
import net.somewhatcity.boiler.core.media.MediaBrowser;
import net.somewhatcity.boiler.core.media.MediaLibrary;
import net.somewhatcity.boiler.core.media.AudioPlayerView;
import net.somewhatcity.boiler.core.media.PlaybackState;
import net.somewhatcity.boiler.core.runtime.LatestTaskQueue;
import org.bukkit.command.CommandSender;
import java.awt.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static net.somewhatcity.boiler.core.smarttv.SmartTvStyle.*;

/** The common media browser and player; decoders are internal backend sessions. */
public class MediaPlayerSource implements IBoilerSource,CancellableLoad {
    private BoilerPlugin plugin;
    private IBoilerDisplay display;
    private MediaLibrary library;
    private SourceSession player; // ImageIO only. AV backends are never registered as sources.
    private volatile PlaybackSession playback;
    private JsonObject directData;
    private boolean imagePlaying;
    private LatestTaskQueue io;
    private final AtomicLong generation=new AtomicLong();
    private final RasterRenderer raster=new RasterRenderer();
    private volatile boolean closed,playing,transportStopped;
    private volatile MediaLibrary.Entry file;
    private volatile String playbackStatus="";
    private volatile PlaybackState lastPlayback=PlaybackState.EMPTY;
    private volatile MediaBrowser.View view=new MediaBrowser.View("",List.of(),0,"Lade Medien ...",true);
    private final IBoilerSource frame=new IBoilerSource() {
        @Override public void load(IBoilerDisplay display,JsonObject data) {}
        @Override public void unload() {}
        @Override public void draw(Graphics2D g,Rectangle viewport) { MediaPlayerSource.this.draw(g,viewport); }
    };

    @Override public void load(IBoilerDisplay display,JsonObject data) {
        plugin=BoilerPlugin.getPlugin(); this.display=display;
        try { library=new MediaLibrary(plugin.getDataFolder().toPath()); }
        catch(java.io.IOException error) { throw new IllegalStateException("Medienordner ist nicht verfügbar",error); }
        plugin.callOnMain(() -> {
            if(closed) return null;
            player=new SourceSession(plugin,display,this::changed,(name,values) -> {});
            io=new LatestTaskQueue(plugin.runtime().io());
            if(data!=null && data.has("url")) openDirect(data);
            else browse(data!=null && data.has("directory") ? data.get("directory").getAsString() : "");
            return null;
        });
    }
    private boolean allowed(CommandSender sender) { return !closed && sender.hasPermission("boiler.command.action"); }
    private void changed() {
        if(closed) return;
        PlaybackSession current=playback;
        if(playing && !transportStopped && current!=null && current.terminal()) {
            lastPlayback=current.playbackState(); playbackStatus=current.status();
            transportStopped=true; current.close(); playback=null;
        }
        if(playing && imagePlaying && !transportStopped && "error".equals(player.sourceName())) {
            JsonObject error=player.sourceData();
            playbackStatus=error.has("message") ? error.get("message").getAsString() : "Wiedergabefehler";
            if(playbackStatus.equals("End of content")) playbackStatus="Ende der Datei";
            if(error.has("durationMicros")) lastPlayback=new PlaybackState(error.get("positionMicros").getAsLong(),error.get("durationMicros").getAsLong(),false,new float[24]);
            transportStopped=true; player.stop();
        }
        display.tick();
    }
    private void browse(String directory) {
        if(closed) return;
        playing=false; transportStopped=false; file=null; directData=null;
        long request=generation.incrementAndGet(); stopPlayback();
        view=new MediaBrowser.View(directory,List.of(),0,"Lade Ordner ...",true);
        submit(() -> {
            try {
                Path folder=library.resolve(directory); List<MediaLibrary.Entry> entries=library.list(directory);
                String relative=library.relative(folder);
                plugin.onMain(() -> { if(current(request)) {
                    view=new MediaBrowser.View(relative,entries,0,"",false);
                    JsonObject state=new JsonObject(); state.addProperty("directory",relative); display.saveSourceData(state);
                }});
            } catch(Exception error) { plugin.onMain(() -> { if(current(request)) view=new MediaBrowser.View("",List.of(),0,"Ordner nicht lesbar. R: neu laden",false); }); }
        });
    }
    private boolean current(long request) { return !closed && generation.get()==request; }
    private void submit(Runnable job) {
        try { io.submit(job); }
        catch(java.util.concurrent.RejectedExecutionException error) { view=new MediaBrowser.View(view.directory(),view.entries(),view.selected(),"Medien gerade beschaeftigt. R: erneut versuchen",false); }
    }
    private void open(CommandSender sender,MediaLibrary.Entry entry) {
        if(entry.kind()==MediaLibrary.Kind.DIRECTORY) { browse(entry.path()); return; }
        if(entry.kind()==MediaLibrary.Kind.OTHER) { Util.sendErrMsg(sender,"Dieses Dateiformat wird nicht unterstützt."); return; }
        if(!sender.hasPermission("boiler.command.source")) { Util.sendErrMsg(sender,"Keine Berechtigung zum Starten der Quelle."); return; }
        long request=generation.incrementAndGet();
        submit(() -> {
            try {
                Path path=library.resolve(entry.path());
                if(!Files.isRegularFile(path) || MediaLibrary.kind(path)!=entry.kind()) throw new java.io.IOException("Datei wurde entfernt oder geändert");
                plugin.onMain(() -> { if(current(request) && sender.hasPermission("boiler.command.action") && sender.hasPermission("boiler.command.source")) {
                    file=entry; playing=true; transportStopped=false; playbackStatus=""; lastPlayback=PlaybackState.EMPTY;
                    JsonObject data=new JsonObject();
                    data.addProperty("url",entry.kind()==MediaLibrary.Kind.IMAGE ? "file:"+path : path.toString());
                    data.addProperty("buffer",10); data.addProperty("live",false);
                    data.addProperty("visualizer",entry.kind()==MediaLibrary.Kind.AUDIO);
                    startPlayback(data,entry.kind()==MediaLibrary.Kind.IMAGE);
                }});
            } catch(Exception error) { plugin.onMain(() -> { if(current(request)) {
                Util.sendErrMsg(sender,"Datei kann nicht geöffnet werden: %s",error.getMessage()); browse(view.directory());
            }}); }
        });
    }
    protected PlaybackBackend createBackend(String name) { return PlaybackBackends.create(name); }
    private void startPlayback(JsonObject data,boolean image) {
        stopPlayback();
        imagePlaying=image;
        if(image) player.source("image",data);
        else {
            String selected=BoilerConfig.mediaPlayerBackend;
            playback=new PlaybackSession(plugin,display,data,() -> createBackend(selected),this::changed);
            changed();
        }
    }
    private void stopPlayback() {
        imagePlaying=false;
        PlaybackSession old=playback; playback=null;
        if(old!=null) old.close();
        if(player!=null) player.stop();
    }
    private void openDirect(JsonObject data) {
        generation.incrementAndGet(); directData=data.deepCopy();
        String url=data.get("url").getAsString();
        if(url.isBlank()) throw new IllegalArgumentException("Medien-URL darf nicht leer sein");
        String name=url; MediaLibrary.Kind kind=MediaLibrary.Kind.VIDEO;
        try {
            java.net.URI uri=MediaLocator.uri(url);
            String path=uri.getPath();
            if(path!=null && !path.isBlank()) {
                Path leaf=Path.of(path).getFileName(); if(leaf!=null) name=leaf.toString();
                MediaLibrary.Kind detected=MediaLibrary.kind(Path.of(path));
                if(detected==MediaLibrary.Kind.AUDIO || detected==MediaLibrary.Kind.IMAGE) kind=detected;
            }
        } catch(IllegalArgumentException ignored) { /* Native backends also accept platform paths. */ }
        file=new MediaLibrary.Entry(url,name,kind,0);
        playing=true; transportStopped=false; playbackStatus=""; lastPlayback=PlaybackState.EMPTY;
        JsonObject request=data.deepCopy();
        if(kind==MediaLibrary.Kind.IMAGE) request.addProperty("url",MediaLocator.uri(url).toString());
        request.addProperty("visualizer",kind==MediaLibrary.Kind.AUDIO);
        if(!request.has("live")) {
            String lower=url.toLowerCase(Locale.ROOT);
            request.addProperty("live",lower.startsWith("rtsp:") || lower.startsWith("rtsps:") || lower.startsWith("udp:"));
        }
        if(!data.has("keepLastSourceData") || !data.get("keepLastSourceData").getAsBoolean()) display.saveSourceData(directData.deepCopy());
        startPlayback(request,kind==MediaLibrary.Kind.IMAGE);
    }
    private String status() { PlaybackSession current=playback; return current==null ? player.status() : current.status(); }
    private Set<RemoteAction> actions() { PlaybackSession current=playback; return current==null ? player.remoteActions() : current.remoteActions(); }
    private void back() { if(playing) browse(view.directory()); else if(!view.directory().isEmpty()) browse(MediaLibrary.parent(view.directory())); }
    private void navigate(int offset) {
        if(view.loading() || view.entries().isEmpty()) return;
        view=new MediaBrowser.View(view.directory(),view.entries(),Math.floorMod(view.selected()+offset,view.entries().size()),view.status(),false);
    }
    @Override public void onKey(CommandSender sender,String key) {
        if(!allowed(sender)) return;
        String input=key.toUpperCase(Locale.ROOT);
        if(input.equals("ESCAPE")) { back(); return; }
        if(playing) {
            switch(input) {
                case "SPACE","ENTER" -> onRemoteAction(sender,RemoteAction.PLAY_PAUSE);
                case "ARROW_LEFT" -> delegate(sender,RemoteAction.SEEK_BACKWARD);
                case "ARROW_RIGHT" -> delegate(sender,RemoteAction.SEEK_FORWARD);
                default -> { if(!transportStopped && playback==null) player.withSource(source -> source.onKey(sender,key)); }
            }
            return;
        }
        switch(input) {
            case "W","ARROW_UP" -> navigate(-1);
            case "S","ARROW_DOWN" -> navigate(1);
            case "A","ARROW_LEFT" -> back();
            case "PAGE_UP" -> navigate(-new MediaBrowser.Layout(display.width(),display.height()).rows());
            case "PAGE_DOWN" -> navigate(new MediaBrowser.Layout(display.width(),display.height()).rows());
            case "R" -> browse(view.directory());
            case "D","ARROW_RIGHT","ENTER","SPACE" -> { if(!view.loading() && !view.entries().isEmpty()) open(sender,view.entries().get(view.selected())); }
        }
    }
    @Override public void onClick(CommandSender sender,int x,int y,boolean right) {
        if(!allowed(sender)) return;
        if(playing) {
            if(y>=display.height()-13) { back(); return; }
            if(!transportStopped) {
                if(playback!=null) delegate(sender,RemoteAction.PLAY_PAUSE);
                else player.withSource(source -> source.onClick(sender,x,y,right));
            }
            return;
        }
        MediaBrowser.Layout layout=new MediaBrowser.Layout(display.width(),display.height());
        if(layout.back().contains(x,y)) { back(); return; }
        if(layout.refresh().contains(x,y)) { browse(view.directory()); return; }
        if(layout.previous().contains(x,y)) { navigate(-layout.rows()); return; }
        if(layout.next().contains(x,y)) { navigate(layout.rows()); return; }
        if(view.loading()) return;
        int page=view.selected()/layout.rows();
        for(int slot=0;slot<layout.rows();slot++) if(layout.row(slot).contains(x,y)) {
            int index=page*layout.rows()+slot;
            if(index<view.entries().size()) { view=new MediaBrowser.View(view.directory(),view.entries(),index,view.status(),false); open(sender,view.entries().get(index)); }
            return;
        }
    }
    @Override public void onScroll(CommandSender sender,int x,int y,int delta) { if(allowed(sender)) { if(playing) { if(playback!=null && delta!=0) delegate(sender,delta>0 ? RemoteAction.VOLUME_UP : RemoteAction.VOLUME_DOWN); else player.withSource(source -> source.onScroll(sender,x,y,delta)); } else navigate(Integer.signum(delta)); } }
    @Override public void onInput(CommandSender sender,String text) { if(allowed(sender) && playing && !transportStopped) player.withSource(source -> source.onInput(sender,text)); }
    @Override public void onCursorMove(CommandSender sender,int x,int y) { if(allowed(sender) && playing && !transportStopped) player.withSource(source -> source.onCursorMove(sender,x,y)); }
    @Override public Set<RemoteAction> remoteActions() {
        if(closed) return Set.of();
        EnumSet<RemoteAction> actions=EnumSet.of(RemoteAction.BACK,RemoteAction.OK);
        if(playing) { actions.add(RemoteAction.STOP); if(transportStopped) actions.add(RemoteAction.PLAY_PAUSE); else actions.addAll(actions()); }
        return Set.copyOf(actions);
    }
    @Override public void onRemoteAction(CommandSender sender,RemoteAction action) {
        if(!allowed(sender)) return;
        switch(action) {
            case BACK -> back();
            case OK -> onKey(sender,"ENTER");
            case STOP -> { if(playing) { capturePlayback(); generation.incrementAndGet(); transportStopped=true; playbackStatus="Gestoppt"; stopPlayback(); } }
            case PLAY_PAUSE -> { if(transportStopped && file!=null) { if(directData!=null) { if(sender.hasPermission("boiler.command.source")) openDirect(directData); } else open(sender,file); } else delegate(sender,action); }
            default -> delegate(sender,action);
        }
    }
    private void delegate(CommandSender sender,RemoteAction action) {
        if(!playing || transportStopped || !actions().contains(action)) return;
        PlaybackSession current=playback;
        if(current!=null) current.action(action); else player.withSource(source -> source.onRemoteAction(sender,action));
    }
    private void capturePlayback() {
        PlaybackSession current=playback;
        if(!transportStopped && current!=null) lastPlayback=current.playbackState();
    }
    private void drawPlayback(Graphics2D g,Rectangle viewport) {
        capturePlayback();
        pixels(g); g.setColor(new Color(12,17,27)); g.fillRect(0,0,viewport.width,viewport.height);
        if(file!=null && file.kind()==MediaLibrary.Kind.AUDIO) {
            AudioPlayerView.draw(g,viewport.width,viewport.height,file.name(),transportStopped ? playbackStatus+" - Play: neu starten" : status(),lastPlayback,transportStopped);
        } else {
        if(!transportStopped) { PlaybackSession current=playback; if(current!=null) current.draw(g,viewport); else player.draw(g,viewport); }
        if(file!=null && (transportStopped || !status().isEmpty() || (playback==null && player.source()==null))) {
            int scale=Math.max(1,Math.min(viewport.width/128,viewport.height/128));
            int center=file.kind()==MediaLibrary.Kind.VIDEO ? viewport.height/3 : viewport.height/2;
            icon(g,file.kind()==MediaLibrary.Kind.IMAGE ? "picture" : "play",viewport.width/2-16*scale,center-36*scale,32*scale,new Color(112,210,222));
            font(g,Font.PLAIN,8*scale); g.setColor(WHITE);
            int baseline=center+8*scale;
            for(String line:AudioPlayerView.titleLines(g,file.name(),viewport.width-12)) { AudioPlayerView.centered(g,line,baseline,viewport.width,6); baseline+=10*scale; }
            font(g,Font.PLAIN,8); g.setColor(MUTED);
            AudioPlayerView.centered(g,transportStopped ? playbackStatus+" - Play: neu starten" : status(),viewport.height-(file.kind()==MediaLibrary.Kind.VIDEO ? 52 : 22),viewport.width,6);
        }
        if(file!=null && file.kind()==MediaLibrary.Kind.VIDEO) {
            g.setColor(new Color(0,0,0,190)); int panel=50*Math.max(1,Math.min(viewport.width/384,viewport.height/256));
            g.fillRect(0,viewport.height-panel,viewport.width,panel); AudioPlayerView.progress(g,viewport.width,viewport.height,lastPlayback);
        }
        }
        font(g,Font.PLAIN,8); g.setColor(new Color(0,0,0,190)); g.fillRect(0,viewport.height-13,viewport.width,13);
        g.setColor(WHITE); AudioPlayerView.centered(g,"Zurueck / Esc: Dateien",viewport.height-4,viewport.width,4);
    }
    @Override public void draw(Graphics2D g,Rectangle viewport) { if(playing) drawPlayback(g,viewport); else MediaBrowser.draw(g,viewport.width,viewport.height,view); }
    @Override public void draw(IDrawingSpace space) { raster.draw(frame,space,display.width(),display.height()); }
    @Override public byte[] provide20msAudio() { return !closed && playing && !transportStopped ? player.audio() : null; }
    @Override public void cancelLoad() { closed=true; generation.incrementAndGet(); if(io!=null) io.close(); PlaybackSession current=playback; if(current!=null) current.close(); }
    @Override public void unload() { cancelLoad(); stopPlayback(); if(player!=null) player.close(); raster.clear(); }
}
