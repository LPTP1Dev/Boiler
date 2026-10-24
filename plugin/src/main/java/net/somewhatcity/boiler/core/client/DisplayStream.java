package net.somewhatcity.boiler.core.client;

import net.somewhatcity.boiler.stream.StreamProtocol;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** At most one owned pixel snapshot and encode operation per display. */
public final class DisplayStream implements AutoCloseable {
    public final StreamProtocol.Display display;
    private final FrameEncoder encoder;
    private final ClientStreamServer server;
    private final Executor executor;
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean closed;
    private long lastFrame;
    public DisplayStream(StreamProtocol.Display display,String mode,ClientStreamServer server,Executor executor) {
        this.display=display;this.server=server;this.executor=executor;
        encoder=new FrameEncoder(display.id(),display.generation(),display.width(),display.height(),mode);
    }
    public boolean wantsFrame(long now,int fps) {return !closed && !busy.get() && now-lastFrame>=1_000_000_000L/fps;}
    public void submit(BufferedImage image) {
        if(closed || !busy.compareAndSet(false,true)) return;
        lastFrame=System.nanoTime();
        // RasterRenderer reuses its backing image immediately after this call.
        try {
            int[] copy=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
            executor.execute(()-> {
            try {if(!closed) {encoder.videoAllowed(server.videoSupported(this));StreamProtocol.Frame frame=encoder.encode(copy);if(frame!=null) server.publish(this,frame);}}
            catch(Exception e) {server.fail(this,e);}
            finally {synchronized(this) {busy.set(false);if(closed) encoder.close();}}
        });} catch(RuntimeException e) {synchronized(this){busy.set(false);if(closed)encoder.close();else requestKeyframe();}}
    }
    public void requestKeyframe() {encoder.requestKeyframe();}
    @Override public synchronized void close() {closed=true;if(!busy.get()) encoder.close();}
}
