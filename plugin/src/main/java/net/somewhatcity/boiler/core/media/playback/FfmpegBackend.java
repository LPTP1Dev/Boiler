package net.somewhatcity.boiler.core.media.playback;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.media.PlaybackState;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Native access belongs to the decoder; transport controls invalidate both sides of its bounded queue. */
public class FfmpegBackend implements PlaybackBackend {
    private record Media(BufferedImage image, byte[] audio, long position) {}
    private volatile boolean running, ready, live, hasAudio, seekable, playbackEnded;
    private volatile Thread decoder, playback;
    private volatile PlaybackOutput audio;
    private final Object audioLock = new Object(), control = new Object(), imageLock = new Object();
    private MediaQueue<Media> queue;
    private BufferedImage image;
    private IBoilerDisplay display;
    private volatile boolean paused;
    private long pauseStarted, pausedNanos, requestedSeek = -1;
    private volatile long positionMicros, durationMicros;
    private final java.util.concurrent.ConcurrentLinkedQueue<BufferedImage> reusableImages = new java.util.concurrent.ConcurrentLinkedQueue<>();

    protected int defaultBuffer() { return 10; }
    public long positionMicros() { return positionMicros; }
    public boolean paused() { return paused; }
    public PlaybackState playbackState() {
        synchronized(control) { return new PlaybackState(positionMicros,durationMicros,paused,audio == null ? new float[24] : audio.levels()); }
    }

    @Override public void start(IBoilerDisplay display, JsonObject data, PlaybackOutput output) {
        this.audio = output;
        this.display = display;
        live = data.has("live") ? data.get("live").getAsBoolean() : false;
        queue = new MediaQueue<>(data.has("buffer") ? data.get("buffer").getAsInt() : defaultBuffer(), live, media -> recycle(media.image()));
        running = true;
        decoder = BoilerPlugin.getPlugin().runtime().startWorker("decode-" + display.id(), () -> decode(data));
        playback = BoilerPlugin.getPlugin().runtime().startWorker("playback-" + display.id(), this::play);
    }

    @Override public Set<RemoteAction> remoteActions() {
        if (!running || !ready || playbackEnded) return Set.of();
        EnumSet<RemoteAction> actions = EnumSet.noneOf(RemoteAction.class);
        if (!live) actions.add(RemoteAction.PLAY_PAUSE);
        if (seekable && !live) { actions.add(RemoteAction.SEEK_BACKWARD); actions.add(RemoteAction.SEEK_FORWARD); }
        if (hasAudio) actions.addAll(Set.of(RemoteAction.VOLUME_DOWN, RemoteAction.VOLUME_UP, RemoteAction.MUTE));
        return Set.copyOf(actions);
    }

    @Override public void action(RemoteAction action) {
        if (!remoteActions().contains(action)) return;
        synchronized (control) {
            if (action == RemoteAction.PLAY_PAUSE) {
                paused = !paused;
                if (paused) pauseStarted = System.nanoTime(); else pausedNanos += System.nanoTime() - pauseStarted;
                if (audio != null) audio.suspended(paused);
            } else if (action == RemoteAction.SEEK_BACKWARD || action == RemoteAction.SEEK_FORWARD) {
                requestedSeek = Math.max(0, Math.min(Math.max(0, durationMicros - 1), positionMicros + (action == RemoteAction.SEEK_FORWARD ? 10_000_000 : -10_000_000)));
                queue.reset();
                if (audio != null) audio.clear();
                synchronized (imageLock) { recycle(image); image = null; }
            }
            control.notifyAll();
        }
    }

    private void decode(JsonObject data) {
        boolean loop = !live && data.has("loop") && data.get("loop").getAsBoolean();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(data.get("url").getAsString());
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.setAudioChannels(1); grabber.setSampleRate(48000); grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24); grabber.setTimeout(5000); grabber.setOption("rw_timeout", "5000000");
            // Frame threading can buffer many video frames while audio is already returned.
            // A bounded interleaved queue then presents video in catch-up bursts. Slice
            // threading retains codec parallelism without that extra frame pipeline delay.
            grabber.setVideoOption("thread_type", "slice");
            grabber.start();
            durationMicros = Math.max(0, grabber.getLengthInTime());
            var context = grabber.getFormatContext();
            seekable = !live && durationMicros > 0 && context.pb() != null && !context.pb().isNull() && (context.pb().seekable() & 1) != 0;
            hasAudio = grabber.hasAudio();
            if (hasAudio && running) audio.open();
            ready = true;
            long loopOffset = 0, lastTimestamp = 0, lastDuration = 20_000;
            boolean received = false;
            while (running && !Thread.currentThread().isInterrupted()) {
                long seek, generation;
                synchronized (control) { seek = requestedSeek; requestedSeek = -1; generation = queue.generation(); }
                if (seek >= 0) {
                    grabber.setTimestamp(seek);
                    loopOffset = 0; lastTimestamp = seek; received = false;
                }
                Frame frame = grabber.grabFrame();
                if (frame == null) {
                    if (loop && received) {
                        loopOffset = Math.max(lastTimestamp + lastDuration, loopOffset + durationMicros);
                        received = false; grabber.restart(); continue;
                    }
                    synchronized (control) {
                        if (generation != queue.generation()) continue;
                        queue.finish();
                        while (running && requestedSeek < 0 && !playbackEnded) control.wait();
                        if (requestedSeek >= 0) continue;
                    }
                    break;
                }
                received = true;
                long timestamp = Math.max(lastTimestamp, loopOffset + Math.max(0, frame.timestamp)); lastTimestamp = timestamp;
                BufferedImage nextImage = null;
                byte[] samples = null;
                if (frame.image != null) {
                    double rate = grabber.getFrameRate();
                    lastDuration = Double.isFinite(rate) && rate > 0 ? Math.max(1, Math.round(1_000_000 / rate)) : 20_000;
                    frame.imageChannels = 3; // The explicitly requested BGR24 excludes stride padding.
                    BufferedImage converted = converter.getBufferedImage(frame);
                    nextImage = reusableImages.poll();
                    int frameWidth=Math.max(display.width(),display.frameWidth()),frameHeight=Math.max(display.height(),display.frameHeight());
                    if (nextImage == null || nextImage.getWidth() != frameWidth || nextImage.getHeight() != frameHeight)
                        nextImage = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = nextImage.createGraphics();
                    try { graphics.drawImage(converted, 0, 0, nextImage.getWidth(), nextImage.getHeight(), null); }
                    finally { graphics.dispose(); }
                }
                if (frame.samples != null && frame.samples[0] instanceof ShortBuffer buffer) {
                    ShortBuffer input = buffer.duplicate();
                    ByteBuffer output = ByteBuffer.allocate(input.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
                    output.asShortBuffer().put(input); samples = output.array();
                    lastDuration = Math.max(1, samples.length / 2L * 1_000_000 / 48_000);
                }
                if (nextImage != null || samples != null) queue.put(new Media(nextImage, samples, Math.max(0, frame.timestamp)), timestamp, generation);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception | LinkageError e) { if (running) audio.failed(e); }
        finally { queue.finish(); }
    }

    private void play() {
        long origin = 0, firstTimestamp = 0, initialPaused = 0, activeGeneration = -1;
        boolean started = false;
        try {
            while (running) {
                long beforeTake = queue.generation();
                MediaQueue.Entry<Media> entry = queue.take();
                if (entry == null) {
                    synchronized (control) { while (running && paused) control.wait(); }
                    if (!running) return;
                    if (beforeTake != queue.generation()) continue;
                    while (running && !paused && audio != null && audio.queued() > 0 && beforeTake == queue.generation()) Thread.sleep(20);
                    synchronized (control) {
                        if (paused || beforeTake != queue.generation() || requestedSeek >= 0) continue;
                        playbackEnded = true; control.notifyAll();
                        if(durationMicros>0) positionMicros=durationMicros;
                    }
                    long endedGeneration = beforeTake;
                    if (running && queue.generation() == endedGeneration) audio.ended();
                    return;
                }
                Media media = entry.value();
                boolean discard = false;
                synchronized (control) {
                    if (!started || activeGeneration != entry.generation() || entry.discontinuity()) {
                        origin = System.nanoTime(); firstTimestamp = entry.timestampMicros();
                        initialPaused = pausedNanos + (paused ? origin - pauseStarted : 0); started = true;
                        activeGeneration = entry.generation();
                        if (entry.discontinuity() && audio != null) audio.clear();
                    }
                    while (running) {
                        if (entry.generation() != queue.generation()) { discard = true; break; }
                        if (paused) { control.wait(); continue; }
                        long remaining = origin + (entry.timestampMicros() - firstTimestamp) * 1000 + pausedNanos - initialPaused - System.nanoTime();
                        if (remaining <= 0) break;
                        TimeUnit.NANOSECONDS.timedWait(control, Math.min(remaining, 20_000_000));
                    }
                    if (!running) { recycle(media.image()); return; }
                    if (!discard) {
                        positionMicros = media.position();
                        if (media.image() != null) synchronized (imageLock) { BufferedImage previous = image; image = media.image(); recycle(previous); }
                    }
                }
                if (discard) { recycle(media.image()); continue; }
                PlaybackOutput player = audio;
                if (media.audio() != null && player != null && !paused && entry.generation() == queue.generation()) player.play(media.audio());
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception | LinkageError e) { if (running) audio.failed(e); }
    }

    @Override public void close() {
        running = false;
        if (queue != null) queue.cancel();
        synchronized (control) { control.notifyAll(); }
        if (decoder != null) decoder.interrupt();
        if (playback != null) playback.interrupt();
        synchronized (audioLock) { if (audio != null) audio.close(); }
        synchronized (imageLock) { image = null; }
        synchronized (reusableImages) { reusableImages.clear(); }
    }
    private void recycle(BufferedImage previous) { synchronized (reusableImages) { if (running && previous != null) reusableImages.offer(previous); } }
    @Override public void draw(Graphics2D graphics, Rectangle viewport) { synchronized (imageLock) { graphics.drawImage(image, 0, 0, viewport.width, viewport.height, null); } }
}
