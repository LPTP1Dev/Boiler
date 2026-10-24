package net.somewhatcity.boiler.core.dj;

import java.util.Arrays;

/** Immutable PCM extrema; bounded storage and no file access on the render thread. */
public final class DjWaveform {
    public static final DjWaveform EMPTY = new DjWaveform(new short[0], new short[0], new short[3][0], 480, 0);
    private final short[] minima, maxima;
    private final short[][] bands;
    private final long bucketSamples, samples;

    private DjWaveform(short[] minima, short[] maxima, short[][] bands, long bucketSamples, long samples) {
        this.minima = minima; this.maxima = maxima; this.bucketSamples = bucketSamples; this.samples = samples;
        this.bands = bands;
    }
    public int size() { return minima.length; }
    public long samples() { return samples; }
    /** Packed signed 16-bit minimum (low half) and maximum (high half). */
    public int envelope(long from, long until) {
        from = Math.max(0, from); until = Math.min(samples, until);
        if (until <= from || minima.length == 0) return 0;
        int first = (int)(from / bucketSamples), last = (int)((until - 1) / bucketSamples);
        short min = Short.MAX_VALUE, max = Short.MIN_VALUE;
        for (int i = first; i <= last; i++) { min = (short)Math.min(min, minima[i]); max = (short)Math.max(max, maxima[i]); }
        return (min & 65535) | (max << 16);
    }
    /** RGB blend: bass red, mids green, treble blue, based on actual band energy. */
    public int color(long from, long until) {
        from = Math.max(0, from); until = Math.min(samples, until);
        if (until <= from || minima.length == 0) return 0x293641;
        int first = (int)(from / bucketSamples), last = (int)((until - 1) / bucketSamples);
        double[] power = new double[3];
        for (int band = 0; band < 3; band++) for (int i = first; i <= last; i++) power[band] = Math.max(power[band], bands[band][i]);
        double total = 0; for (int band = 0; band < 3; band++) { power[band] *= power[band]; total += power[band]; }
        if (total == 0) return 0x293641;
        int r = (int)((240 * power[0] + 55 * power[1] + 65 * power[2]) / total);
        int g = (int)((65 * power[0] + 225 * power[1] + 125 * power[2]) / total);
        int b = (int)((65 * power[0] + 95 * power[1] + 250 * power[2]) / total);
        return r << 16 | g << 8 | b;
    }
    public static final class Builder {
        private static final int CAPACITY = 32768;
        private final short[] minima = new short[CAPACITY], maxima = new short[CAPACITY];
        private final short[][] bands = new short[3][CAPACITY];
        private final double[] energy = new double[3];
        private double low, upper;
        private static final double LOW = 1 - Math.exp(-2 * Math.PI * 250 / 48000);
        private static final double HIGH = 1 - Math.exp(-2 * Math.PI * 4000 / 48000);
        private long bucketSamples = 480, count, samples;
        private int size;
        private short min = Short.MAX_VALUE, max = Short.MIN_VALUE;
        public void add(short value) {
            min = (short)Math.min(min, value); max = (short)Math.max(max, value); samples++;
            double sample = value / 32768.0;
            low += LOW * (sample - low); upper += HIGH * (sample - upper);
            energy[0] += low * low; energy[1] += (upper - low) * (upper - low); energy[2] += (sample - upper) * (sample - upper);
            if (++count == bucketSamples) flush();
        }
        private void flush() {
            for (int band = 0; band < 3; band++) { bands[band][size] = (short)Math.min(32767, Math.sqrt(energy[band] / count) * 32767); energy[band] = 0; }
            minima[size] = min; maxima[size++] = max; count = 0; min = Short.MAX_VALUE; max = Short.MIN_VALUE;
            if (size == CAPACITY) {
                for (int i = 0; i < CAPACITY / 2; i++) {
                    minima[i] = (short)Math.min(minima[i * 2], minima[i * 2 + 1]);
                    maxima[i] = (short)Math.max(maxima[i * 2], maxima[i * 2 + 1]);
                    for (int band = 0; band < 3; band++) bands[band][i] = (short)Math.max(bands[band][i * 2], bands[band][i * 2 + 1]);
                }
                size /= 2; bucketSamples *= 2;
            }
        }
        public DjWaveform build() {
            if (count > 0) flush();
            short[][] levels = new short[3][]; for (int band = 0; band < 3; band++) levels[band] = Arrays.copyOf(bands[band], size);
            return new DjWaveform(Arrays.copyOf(minima, size), Arrays.copyOf(maxima, size), levels, bucketSamples, samples);
        }
    }
}
