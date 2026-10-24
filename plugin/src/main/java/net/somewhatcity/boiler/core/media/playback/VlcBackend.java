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
import com.sun.jna.Pointer;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.media.PlaybackState;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.Set;

public final class VlcBackend implements PlaybackBackend {
    private final Object frameLock=new Object();
    private MediaPlayerFactory factory;
    private EmbeddedMediaPlayer player;
    private BufferedImage image;
    private int[] pixels;
    private int width,height;
    private PlaybackOutput output;
    private volatile NativeMediaControls controls;
    private volatile PlaybackState state=PlaybackState.EMPTY;
    private volatile boolean stopped,ended;
    private boolean live,loop;
    @Override public void start(IBoilerDisplay display,JsonObject data,PlaybackOutput output) {
        this.output=output;
        live=data.has("live") && data.get("live").getAsBoolean(); loop=!live && data.has("loop") && data.get("loop").getAsBoolean();
        width=Math.max(display.width(),display.frameWidth()); height=Math.max(display.height(),display.frameHeight());
        image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB); pixels=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
        factory=VlcNativeLoader.createFactory("--no-video-title-show"); player=factory.mediaPlayers().newEmbeddedMediaPlayer();
        player.audio().callback("S16L",48000,1,new AudioCallbackAdapter() {
            @Override public void play(MediaPlayer mediaPlayer,Pointer samples,int count,long pts) { if(!stopped && !ended) output.play(samples.getByteArray(0,count*2)); }
            @Override public void flush(MediaPlayer mediaPlayer,long pts) { output.clear(); }
        });
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(new BufferFormatCallbackAdapter() {
            @Override public BufferFormat getBufferFormat(int sourceWidth,int sourceHeight) { return new RV32BufferFormat(width,height); }
        },(mediaPlayer,buffers,format) -> {
            synchronized(frameLock) {
                if(stopped || ended || image==null) return;
                buffers[0].duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(pixels);
            }
        },true));
        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override public void error(MediaPlayer mediaPlayer) { if(!stopped) output.failed(new IllegalStateException("VLC konnte das Medium nicht abspielen")); }
            @Override public void finished(MediaPlayer mediaPlayer) {
                NativeMediaControls current=controls;
                if(current!=null) current.execute(() -> {
                    if(stopped) return;
                    if(loop) { output.clear(); player.controls().setTime(0); player.controls().play(); }
                    else { ended=true; state=new PlaybackState(state.duration(),state.duration(),false,output.levels()); output.ended(); }
                });
            }
        });
        controls=new NativeMediaControls(BoilerPlugin.getPlugin(),new NativeMediaControls.Backend() {
            @Override public Set<RemoteAction> probe() {
                state=new PlaybackState(Math.max(0,player.status().time())*1000,Math.max(0,player.status().length())*1000,!player.status().isPlaying(),output.levels());
                if(ended) return Set.of();
                var actions=EnumSet.of(RemoteAction.VOLUME_DOWN,RemoteAction.VOLUME_UP,RemoteAction.MUTE);
                if(!live && player.status().canPause()) actions.add(RemoteAction.PLAY_PAUSE);
                if(!live && player.status().isSeekable() && state.duration()>0) actions.addAll(Set.of(RemoteAction.SEEK_BACKWARD,RemoteAction.SEEK_FORWARD));
                return actions;
            }
            @Override public void action(RemoteAction action) {
                if(action==RemoteAction.PLAY_PAUSE) { boolean pause=player.status().isPlaying(); output.suspended(pause); player.controls().setPause(pause); }
                else if(action==RemoteAction.SEEK_FORWARD || action==RemoteAction.SEEK_BACKWARD) {
                    output.clear(); player.controls().setTime(Math.max(0,Math.min(player.status().length()-1,player.status().time()+(action==RemoteAction.SEEK_FORWARD ? 10_000 : -10_000))));
                    synchronized(frameLock) { if(image!=null) { Graphics2D g=image.createGraphics(); try { g.setColor(Color.BLACK); g.fillRect(0,0,width,height); } finally { g.dispose(); } } }
                }
            }
            @Override public void close() { dispose(); }
        },output::failed);
        controls.execute(() -> { if(!player.media().play(data.get("url").getAsString())) output.failed(new IllegalStateException("VLC konnte das Medium nicht öffnen")); });
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
    private void dispose() { try { if(player!=null) player.release(); } finally { if(factory!=null) factory.release(); } }
}
