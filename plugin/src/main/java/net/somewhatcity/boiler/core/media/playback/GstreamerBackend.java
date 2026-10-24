/*
 * Copyright (c) 2024.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.somewhatcity.boiler.core.media.playback;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.Util;
import net.somewhatcity.boiler.core.media.PlaybackState;
import net.somewhatcity.boiler.core.sources.BgrxFrames;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.PlayBin;
import org.freedesktop.gstreamer.event.SeekFlags;
import org.freedesktop.gstreamer.query.SeekingQuery;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Set;

public final class GstreamerBackend implements PlaybackBackend {
    private final Object frameLock=new Object();
    private volatile boolean stopped, ended;
    private boolean paused, live, loop;
    private BufferedImage image;
    private PlayBin player;
    private AppSink videoSink, audioSink;
    private PlaybackOutput output;
    private volatile NativeMediaControls controls;
    private volatile PlaybackState state=PlaybackState.EMPTY;

    @Override public void start(IBoilerDisplay display, JsonObject data, PlaybackOutput output) {
        this.output=output;
        live=data.has("live") && data.get("live").getAsBoolean();
        loop=!live && data.has("loop") && data.get("loop").getAsBoolean();
        if(!Util.ensureGstreamer()) throw new IllegalStateException("GStreamer ist nicht installiert oder konnte nicht geladen werden");
        videoSink=new AppSink("BoilerVideo-"+display.id());
        videoSink.set("emit-signals",true); videoSink.set("max-buffers",2); videoSink.set("drop",true);
        videoSink.setCaps(Caps.fromString("video/x-raw,format=BGRx"));
        videoSink.connect((AppSink.NEW_SAMPLE) sink -> {
            Sample sample=sink.pullSample(); if(sample==null) return FlowReturn.OK;
            Buffer buffer=null; boolean mapped=false;
            try {
                if(stopped || ended) return FlowReturn.OK;
                Structure caps=sample.getCaps().getStructure(0);
                int width=caps.getInteger("width"), height=caps.getInteger("height");
                buffer=sample.getBuffer(); ByteBuffer bytes=buffer.map(false); if(bytes==null) return FlowReturn.OK;
                mapped=true;
                synchronized(frameLock) {
                    if(stopped) return FlowReturn.OK;
                    if(image==null || image.getWidth()!=width || image.getHeight()!=height) image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
                    BgrxFrames.copy(bytes,image,bytes.remaining()/height);
                }
                return FlowReturn.OK;
            } catch(RuntimeException error) { output.failed(error); return FlowReturn.ERROR; }
            finally { if(mapped) buffer.unmap(); sample.dispose(); }
        });
        audioSink=new AppSink("BoilerAudio-"+display.id());
        audioSink.set("emit-signals",true); audioSink.set("max-buffers",4);
        audioSink.setCaps(Caps.fromString("audio/x-raw,format=S16LE,layout=interleaved,channels=1,rate=48000"));
        audioSink.connect((AppSink.NEW_SAMPLE) sink -> {
            Sample sample=sink.pullSample(); if(sample==null) return FlowReturn.OK;
            Buffer buffer=null; boolean mapped=false;
            try {
                if(stopped || ended) return FlowReturn.OK;
                buffer=sample.getBuffer(); ByteBuffer bytes=buffer.map(false); if(bytes==null) return FlowReturn.OK;
                mapped=true; byte[] pcm=new byte[bytes.remaining()]; bytes.get(pcm); output.play(pcm);
                return FlowReturn.OK;
            } catch(RuntimeException error) { output.failed(error); return FlowReturn.ERROR; }
            finally { if(mapped) buffer.unmap(); sample.dispose(); }
        });
        player=new PlayBin("BoilerPlayer-"+display.id()); player.setVideoSink(videoSink); player.setAudioSink(audioSink);
        player.getBus().connect((Bus.ERROR) (source,code,message) -> { if(!stopped) output.failed(new IllegalStateException(message)); });
        player.getBus().connect((Bus.EOS) source -> {
            NativeMediaControls current=controls;
            if(current!=null) current.execute(() -> {
                if(stopped) return;
                if(loop) { output.clear(); player.seekSimple(Format.TIME,EnumSet.of(SeekFlags.FLUSH),0); }
                else { ended=true; state=new PlaybackState(state.duration(),state.duration(),false,output.levels()); output.ended(); }
            });
        });
        String url=data.get("url").getAsString();
        player.setURI(MediaLocator.uri(url));
        controls=new NativeMediaControls(BoilerPlugin.getPlugin(),new NativeMediaControls.Backend() {
            @Override public Set<RemoteAction> probe() {
                long duration=Math.max(0,player.queryDuration(Format.TIME)/1000), position=Math.max(0,player.queryPosition(Format.TIME)/1000);
                state=new PlaybackState(position,duration,paused,output.levels());
                if(ended) return Set.of();
                var actions=EnumSet.of(RemoteAction.VOLUME_DOWN,RemoteAction.VOLUME_UP,RemoteAction.MUTE);
                if(!live && duration>0) {
                    actions.add(RemoteAction.PLAY_PAUSE);
                    SeekingQuery query=new SeekingQuery(Format.TIME);
                    try { if(player.query(query) && query.isSeekable()) actions.addAll(Set.of(RemoteAction.SEEK_BACKWARD,RemoteAction.SEEK_FORWARD)); }
                    finally { query.dispose(); }
                }
                return actions;
            }
            @Override public void action(RemoteAction action) {
                if(action==RemoteAction.PLAY_PAUSE) { paused=!paused; output.suspended(paused); if(paused) player.pause(); else player.play(); }
                else if(action==RemoteAction.SEEK_FORWARD || action==RemoteAction.SEEK_BACKWARD) {
                    output.clear();
                    long target=Math.max(0,Math.min(player.queryDuration(Format.TIME)-1,player.queryPosition(Format.TIME)+(action==RemoteAction.SEEK_FORWARD ? 10_000_000_000L : -10_000_000_000L)));
                    player.seekSimple(Format.TIME,EnumSet.of(SeekFlags.FLUSH,SeekFlags.KEY_UNIT),target);
                    synchronized(frameLock) { image=null; }
                }
            }
            @Override public void close() { dispose(); }
        },output::failed);
        controls.execute(() -> player.play());
    }
    @Override public PlaybackState playbackState() { PlaybackState current=state; return new PlaybackState(current.position(),current.duration(),current.paused(),output==null ? new float[24] : output.levels()); }
    @Override public Set<RemoteAction> remoteActions() { return stopped || ended || controls==null ? Set.of() : controls.actions(); }
    @Override public void action(RemoteAction action) { if(controls!=null && !stopped) controls.action(action); }
    @Override public void draw(Graphics2D graphics,Rectangle viewport) { synchronized(frameLock) { graphics.drawImage(image,0,0,viewport.width,viewport.height,null); } }
    @Override public void close() {
        if(stopped) return; stopped=true;
        if(output!=null) output.close();
        if(controls!=null) controls.close(); else dispose();
        synchronized(frameLock) { image=null; }
    }
    private void dispose() {
        try { if(player!=null) { player.stop(); player.dispose(); } }
        finally { try { if(videoSink!=null) videoSink.dispose(); } finally { if(audioSink!=null) audioSink.dispose(); } }
    }
}
