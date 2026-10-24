package net.somewhatcity.boiler.core.media.playback;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.audio.PcmVolume;
import net.somewhatcity.boiler.core.media.PlaybackState;
import java.awt.*;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Owns one cancellable playback; a replacement always gets a fresh instance. */
public final class PlaybackSession implements AutoCloseable {
    private final BoilerPlugin plugin;
    private final IBoilerDisplay display;
    private final Runnable changed;
    private final Object lock=new Object();
    private final PlaybackOutput output;
    private volatile PlaybackBackend backend;
    private volatile boolean closed, terminal, loaded;
    private volatile String status="Lade Medium ...";
    private Future<?> loading;
    public PlaybackSession(BoilerPlugin plugin,IBoilerDisplay display,JsonObject data,Supplier<PlaybackBackend> factory,Runnable changed) {
        this.plugin=plugin; this.display=display; this.changed=changed;
        output=new PlaybackOutput(display,flag(data,"live"),flag(data,"visualizer"),new PlaybackOutput.Listener() {
            @Override public void ended() { finish("Ende der Datei"); }
            @Override public void failed(String message) { finish("Wiedergabefehler: "+message); }
        });
        JsonObject parameters=data.deepCopy();
        try { loading=plugin.runtime().loaders().submit(() -> load(parameters,factory)); }
        catch(java.util.concurrent.RejectedExecutionException error) { loaded=true; finish("Wiedergabe gerade beschäftigt. Bitte erneut versuchen."); }
    }
    private static boolean flag(JsonObject data,String key) { return data.has(key) && data.get(key).getAsBoolean(); }
    private void load(JsonObject parameters,Supplier<PlaybackBackend> factory) {
        PlaybackBackend candidate=null;
        try {
            if(closed) return;
            candidate=factory.get();
            if(closed || Thread.currentThread().isInterrupted()) return;
            candidate.start(display,parameters,output);
            synchronized(lock) {
                if(closed) return;
                backend=candidate; candidate=null;
                if(!terminal) status="";
            }
        } catch(Exception | LinkageError error) { if(!closed) output.failed(error); }
        finally {
            try { if(candidate!=null) candidate.close(); }
            finally { loaded=true; notifyChanged(); }
        }
    }
    private void finish(String message) {
        synchronized(lock) { if(closed || terminal) return; terminal=true; status=message; }
        if(loaded) notifyChanged();
    }
    private void notifyChanged() { plugin.onMain(() -> { if(!closed) changed.run(); }); }
    public boolean terminal() { return terminal; }
    public String status() { return status; }
    public PlaybackState playbackState() { synchronized(lock) { return backend==null ? PlaybackState.EMPTY : backend.playbackState(); } }
    public Set<RemoteAction> remoteActions() { synchronized(lock) { return closed || terminal || backend==null ? Set.of() : backend.remoteActions(); } }
    public void action(RemoteAction action) {
        synchronized(lock) {
            if(closed || terminal || backend==null || !backend.remoteActions().contains(action)) return;
            if(PcmVolume.change(display,action)) { output.clear(); return; }
            backend.action(action);
        }
    }
    public void draw(Graphics2D graphics,Rectangle viewport) { synchronized(lock) { if(!closed && backend!=null) backend.draw(graphics,viewport); } }
    @Override public void close() {
        PlaybackBackend old;
        synchronized(lock) { if(closed) return; closed=true; old=backend; backend=null; }
        output.close();
        Future<?> pending=loading; if(pending!=null) pending.cancel(true);
        if(old!=null) old.close();
    }
}
