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
    // The encoder keeps the previous frame for its delta; with one encode in flight two buffers suffice.
    private final int[][] buffers=new int[2][];
    private int nextBuffer;
    public DisplayStream(StreamProtocol.Display display,String mode,ClientStreamServer server,Executor executor) {
        this.display=display;this.server=server;this.executor=executor;
        encoder=new FrameEncoder(display.id(),display.generation(),display.width(),display.height(),mode);
    }
    // Skipping the encode (instead of dropping its output) keeps every subscriber's delta chain intact.
    public boolean wantsFrame(long now,int fps) {return !closed && !busy.get() && now-lastFrame>=1_000_000_000L/fps && server.ready(this);}
    public void submit(BufferedImage image) {
        if(closed || !busy.compareAndSet(false,true)) return;
        lastFrame=System.nanoTime();
        // RasterRenderer reuses its backing image immediately after this call.
        try {
            int[] copy=snapshot(image);
            executor.execute(()-> {
            try {if(!closed) {encoder.videoAllowed(server.videoSupported(this));StreamProtocol.Frame frame=encoder.encode(copy);if(frame!=null) server.publish(this,frame);}}
            catch(Exception e) {server.fail(this,e);}
            finally {synchronized(this) {busy.set(false);if(closed) encoder.close();}}
        });} catch(java.util.concurrent.RejectedExecutionException e) {
            // Nothing was encoded, so the delta chain is intact and no keyframe is needed.
            synchronized(this){busy.set(false);if(closed)encoder.close();}
        } catch(RuntimeException e) {synchronized(this){busy.set(false);if(closed)encoder.close();else requestKeyframe();}}
    }
    private int[] snapshot(BufferedImage image) {
        int width=image.getWidth(),height=image.getHeight();
        int[] target=buffers[nextBuffer];
        if(target==null || target.length!=width*height) target=buffers[nextBuffer]=new int[width*height];
        nextBuffer^=1;
        if((image.getType()==BufferedImage.TYPE_INT_ARGB || image.getType()==BufferedImage.TYPE_INT_RGB)
                && image.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferInt data
                && image.getRaster().getSampleModel() instanceof java.awt.image.SinglePixelPackedSampleModel model
                && model.getScanlineStride()==width && image.getRaster().getSampleModelTranslateX()==0
                && image.getRaster().getSampleModelTranslateY()==0 && data.getOffset()==0 && data.getNumBanks()==1) {
            System.arraycopy(data.getData(),0,target,0,width*height);
            return target;
        }
        return image.getRGB(0,0,width,height,target,0,width);
    }
    public void requestKeyframe() {encoder.requestKeyframe();}
    @Override public synchronized void close() {closed=true;if(!busy.get()) encoder.close();}
}
