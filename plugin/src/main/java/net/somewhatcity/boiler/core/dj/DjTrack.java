package net.somewhatcity.boiler.core.dj;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.function.IntConsumer;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

/** Prepared audio stays on disk; each deck owns its file and bounded read buffer. */
public final class DjTrack implements AutoCloseable {
    public final String name;
    public final long samples;
    public final DjWaveform waveform;
    public final DjBeatGrid beats;
    private final Path cache;
    private final FileChannel channel;
    private final ByteBuffer read = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);

    private DjTrack(String name, Path cache, DjWaveform waveform, DjBeatGrid beats) throws IOException {
        this.name = name; this.cache = cache; this.waveform = waveform;
        this.beats = beats;
        channel = FileChannel.open(cache, StandardOpenOption.READ);
        samples = channel.size() / 2;
    }

    public static DjTrack prepare(Path path, IntConsumer progress) throws Exception {
        Path cache = Files.createTempFile("boiler-dj-", ".pcm");
        boolean success = false;
        DjWaveform.Builder waveform = new DjWaveform.Builder();
        DjBeatGrid.Builder beats = new DjBeatGrid.Builder();
        try {
            try (FFmpegFrameGrabber decoder = new FFmpegFrameGrabber(path.toString());
                 FileChannel out = FileChannel.open(cache, StandardOpenOption.WRITE)) {
                decoder.setAudioChannels(1); decoder.setSampleRate(48000); decoder.setSampleFormat(AV_SAMPLE_FMT_S16);
                decoder.start();
                if (!decoder.hasAudio()) throw new IOException("Datei enthält kein Audio");
                ByteBuffer bytes = ByteBuffer.allocate(32768).order(ByteOrder.LITTLE_ENDIAN);
                Frame frame;
                while ((frame = decoder.grabSamples()) != null) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (frame.samples == null || !(frame.samples[0] instanceof ShortBuffer samples)) continue;
                    ShortBuffer input = samples.duplicate();
                    while (input.hasRemaining()) {
                        bytes.clear();
                        int count = Math.min(input.remaining(), bytes.capacity() / 2);
                        for (int i = 0; i < count; i++) { short sample = input.get(); bytes.putShort(sample); waveform.add(sample); beats.add(sample); }
                        bytes.flip(); while (bytes.hasRemaining()) out.write(bytes);
                    }
                    if (decoder.getLengthInTime() > 0)
                        progress.accept((int)Math.min(99, 100 * frame.timestamp / decoder.getLengthInTime()));
                }
                if (out.size() == 0) throw new IOException("Keine Audiodaten decodiert");
            }
            DjTrack track = new DjTrack(path.getFileName().toString(), cache, waveform.build(), beats.build());
            success = true; progress.accept(100); return track;
        } finally { if (!success) Files.deleteIfExists(cache); }
    }

    public int read(long position, float[] target, int offset, int count) throws IOException {
        read.clear(); read.limit(Math.min(count * 2, read.capacity()));
        int total = 0;
        while (read.hasRemaining()) {
            int got = channel.read(read, position * 2 + total);
            if (got < 0) break;
            if (got == 0) break;
            total += got;
        }
        read.flip(); int n = read.remaining() / 2;
        for (int i = 0; i < n; i++) target[offset + i] = read.getShort() / 32768f;
        return n;
    }

    @Override public void close() throws IOException {
        try { channel.close(); } finally { Files.deleteIfExists(cache); }
    }
}
