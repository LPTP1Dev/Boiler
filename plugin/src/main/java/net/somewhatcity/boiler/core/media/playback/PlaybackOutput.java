package net.somewhatcity.boiler.core.media.playback;

import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.audio.BAudioPlayer;
import net.somewhatcity.boiler.core.media.AudioSpectrum;
import java.util.function.Supplier;

/** Shared PCM output. Native callbacks never own voice-chat players or visualization policy. */
public final class PlaybackOutput implements AutoCloseable {
    public interface Listener { void ended(); void failed(String message); }
    private final Supplier<BAudioPlayer> factory;
    private final Listener listener;
    private final boolean visualizer;
    private volatile BAudioPlayer audio;
    private volatile boolean closed, paused;
    private float[] levels = new float[24];
    public PlaybackOutput(IBoilerDisplay display, boolean live, boolean visualizer, Listener listener) {
        this(() -> BoilerPlugin.getPlugin().callOnMain(() -> new BAudioPlayer(display, live)), visualizer, listener);
    }
    public PlaybackOutput(Supplier<BAudioPlayer> factory, boolean visualizer, Listener listener) {
        this.factory = factory; this.visualizer = visualizer; this.listener = listener;
    }
    public void open() {
        if (closed || audio != null) return;
        BAudioPlayer created = factory.get();
        synchronized (this) {
            if (closed || audio != null) created.stop();
            else { audio = created; created.suspended(paused); }
        }
    }
    public void play(byte[] pcm) {
        if (closed || paused) return;
        open();
        if (closed || paused) return;
        if (visualizer) {
            float[] spectrum = AudioSpectrum.analyze(pcm);
            synchronized (this) {
                if (closed || paused) return;
                for (int i=0;i<spectrum.length;i++) spectrum[i]=spectrum[i]*.7f+levels[i]*.3f;
                levels=spectrum;
            }
        }
        BAudioPlayer current=audio;
        if (current!=null && !closed && !paused) current.play(pcm);
    }
    public synchronized float[] levels() { return levels.clone(); }
    public void clear() {
        synchronized (this) { levels=new float[24]; }
        BAudioPlayer current=audio; if(current!=null) current.clear();
    }
    public void suspended(boolean value) {
        paused=value; BAudioPlayer current=audio; if(current!=null) current.suspended(value);
    }
    public int queued() { BAudioPlayer current=audio; return current==null ? 0 : current.getAudioQueueSize(); }
    public void ended() { if(!closed) listener.ended(); }
    public void failed(Throwable error) { if(!closed) listener.failed(error.getMessage()==null ? error.toString() : error.getMessage()); }
    @Override public void close() {
        BAudioPlayer current;
        synchronized (this) { if(closed) return; closed=true; levels=new float[24]; current=audio; }
        if(current!=null) current.stop();
    }
}
