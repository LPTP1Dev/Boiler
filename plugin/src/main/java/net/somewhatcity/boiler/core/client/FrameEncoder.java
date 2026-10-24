package net.somewhatcity.boiler.core.client;

import net.somewhatcity.boiler.stream.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared encoder per display, including independent recovery frames for slow/new subscribers. */
public final class FrameEncoder implements AutoCloseable {
    private final int id,width,height;
    private final long generation;
    private final String mode;
    private final AtomicBoolean forceKey=new AtomicBoolean(true);
    private volatile boolean videoAllowed=true;
    private int[] previous;
    private long sequence;
    private int busy,calm;
    private H264Codec video;
    private boolean videoUnavailable;
    public FrameEncoder(int id,long generation,int width,int height,String mode) {
        StreamProtocol.checkSize(width,height);this.id=id;this.generation=generation;this.width=width;this.height=height;this.mode=mode;
    }
    public void requestKeyframe() {forceKey.set(true);}
    public void videoAllowed(boolean allowed) {videoAllowed=allowed;}
    public StreamProtocol.Frame encode(int[] pixels) {
        int left=width,top=height,right=-1,bottom=-1,changed=0;
        if(previous!=null) for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int i=y*width+x;
            if((pixels[i]&0xffffff)!=(previous[i]&0xffffff)) {changed++;left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
        }
        else {changed=pixels.length;left=top=0;right=width-1;bottom=height-1;}
        boolean key=forceKey.getAndSet(false) || previous==null || sequence%30==0;
        boolean wantVideo=mode.equals("h264");
        if(mode.equals("auto")) {
            busy=changed>pixels.length/3 ? busy+1 : 0;
            calm=changed<pixels.length/50 ? calm+1 : 0;
            wantVideo=video!=null ? calm<30 : busy>=6;
        }
        if(changed==0 && !key && !(video!=null && !wantVideo)) return null;
        wantVideo &= videoAllowed && !videoUnavailable && width%2==0 && height%2==0;
        if(wantVideo && video==null) {
            try {video=new H264Codec(width,height,true);key=true;}
            catch(Throwable e) {videoUnavailable=true;key=true;java.util.logging.Logger.getLogger("Boiler").warning("H.264 unavailable; using lossless RGB: "+e.getMessage());}
        } else if(!wantVideo && video!=null) {video.close();video=null;key=true;}
        byte[] data;
        if(video!=null) {
            try {data=video.encode(pixels,key);left=top=0;right=width-1;bottom=height-1;}
            catch(RuntimeException e) {video.close();video=null;videoUnavailable=true;key=true;data=null;}
        } else data=null;
        if(video==null) {
            if(key) {left=top=0;right=width-1;bottom=height-1;}
            data=StreamProtocol.compress(pixels,width,left,top,right-left+1,bottom-top+1);
        }
        long base=key ? 0 : sequence;
        previous=pixels;
        return new StreamProtocol.Frame(id,generation,++sequence,base,video==null ? StreamProtocol.RGB : StreamProtocol.H264,key,
                width,height,left,top,right-left+1,bottom-top+1,data);
    }
    @Override public void close() {if(video!=null) {video.close();video=null;}previous=null;}
}
