package net.somewhatcity.boiler.core.dj;

/** A fixed beat grid estimated from rhythmic onsets during local track preparation. */
public record DjBeatGrid(double bpm, double offset) {
    public static final DjBeatGrid UNKNOWN = new DjBeatGrid(0, 0);
    public double period() { return 48000 * 60 / bpm; }
    public double phase(double sample) { double beat = (sample - offset) / period(); return beat - Math.floor(beat); }
    /** Nearest whole beat inside the track; -1 if this range contains no beat. */
    public long snap(double sample, long min, long max) {
        if (bpm <= 0) return (long)Math.max(min, Math.min(max, sample));
        double first = Math.ceil((min - offset) / period()), last = Math.floor((max - offset) / period());
        if (first > last) return -1;
        double beat = Math.max(first, Math.min(last, Math.round((sample - offset) / period())));
        return Math.round(offset + beat * period());
    }

    public static final class Builder {
        // Analyse up to two minutes, independently of waveform downsampling.
        private final float[] onsets = new float[12000];
        private int size, count;
        private double low, energy, previous;
        public void add(short sample) {
            if (size == onsets.length) return;
            double value = sample / 32768.0;
            low += .0233 * (value - low);
            energy += low * low + .15 * value * value;
            if (++count == 480) {
                double level = Math.sqrt(energy / count);
                onsets[size++] = (float)Math.max(0, level - previous);
                previous = level; count = 0; energy = 0;
            }
        }
        public DjBeatGrid build() throws InterruptedException {
            if (size < 400) return UNKNOWN;
            double norm = 0; for (int i = 0; i < size; i++) norm += onsets[i] * onsets[i];
            if (norm < 1e-7) return UNKNOWN;
            // Resolve tempo over the whole analysis window, including harmonics:
            // subdivisions can dominate a single beat-frequency component.
            double[] spectrum = spectrum();
            double[] scores = new double[10001];
            double best = 0, bpm = 0;
            int fftSize = (spectrum.length - 1) * 2;
            for (int candidate = 8000; candidate <= 18000; candidate++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                double tempo = candidate / 100.0, score = 0;
                double weight = 1;
                for (int harmonic = 1; harmonic <= 4; harmonic++) {
                    double bin = tempo / 60 * harmonic * fftSize / 100;
                    int index = (int)bin; double fraction = bin - index;
                    score += weight * (spectrum[index] * (1 - fraction) + spectrum[index + 1] * fraction);
                    weight *= .5;
                }
                scores[candidate - 8000] = score;
                if (score > best) { best = score; bpm = tempo; }
            }
            java.util.Arrays.sort(scores);
            if (best <= scores[scores.length / 2] * 2.5 || best < Math.sqrt(norm * size) * .02) return UNKNOWN;

            // Fold onsets onto the beat and select the strongest local cluster.
            // A circular mean would cancel valid kicks against offbeat hi-hats.
            double period = 6000 / bpm;
            double[] phases = new double[128], smooth = new double[128];
            for (int i = 0; i < size; i++) {
                double position = (i / period - Math.floor(i / period)) * phases.length;
                int index = (int)position; double fraction = position - index;
                phases[index] += onsets[i] * (1 - fraction);
                phases[(index + 1) % phases.length] += onsets[i] * fraction;
            }
            int radius = Math.max(2, (int)Math.ceil(phases.length / period * 1.5)), peak = 0;
            double sum = 0;
            for (int i = 0; i < phases.length; i++) {
                for (int delta = -radius; delta <= radius; delta++)
                    smooth[i] += phases[Math.floorMod(i + delta, phases.length)] * (radius + 1 - Math.abs(delta));
                sum += smooth[i]; if (smooth[i] > smooth[peak]) peak = i;
            }
            if (smooth[peak] <= sum / phases.length * 1.35) return UNKNOWN;
            double before = smooth[Math.floorMod(peak - 1, phases.length)], after = smooth[(peak + 1) % phases.length];
            double curvature = before - 2 * smooth[peak] + after;
            double shift = curvature == 0 ? 0 : .5 * (before - after) / curvature;
            double phase = (peak + Math.max(-.5, Math.min(.5, shift))) / phases.length;
            if (phase < 0) phase++;
            return new DjBeatGrid(bpm, phase * period * 480);
        }
        private double[] spectrum() throws InterruptedException {
            int n = 16384; while (n < size * 16) n <<= 1;
            double[] real = new double[n], imaginary = new double[n];
            double mean = 0; for (int i = 0; i < size; i++) mean += onsets[i]; mean /= size;
            for (int i = 0; i < size; i++) real[i] = (onsets[i] - mean) * (.5 - .5 * Math.cos(2 * Math.PI * i / (size - 1)));
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1; for (; (j & bit) != 0; bit >>= 1) j ^= bit; j ^= bit;
                if (i < j) { double swap = real[i]; real[i] = real[j]; real[j] = swap; }
            }
            for (int length = 2; length <= n; length <<= 1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                double angle = -2 * Math.PI / length, stepR = Math.cos(angle), stepI = Math.sin(angle);
                for (int start = 0; start < n; start += length) {
                    double wr = 1, wi = 0;
                    for (int j = 0; j < length / 2; j++) {
                        int a = start + j, b = a + length / 2;
                        double re = real[b] * wr - imaginary[b] * wi, im = real[b] * wi + imaginary[b] * wr;
                        real[b] = real[a] - re; imaginary[b] = imaginary[a] - im; real[a] += re; imaginary[a] += im;
                        double next = wr * stepR - wi * stepI; wi = wr * stepI + wi * stepR; wr = next;
                    }
                }
            }
            double[] result = new double[n / 2 + 1];
            for (int i = 0; i < result.length; i++) result[i] = Math.hypot(real[i], imaginary[i]);
            return result;
        }
    }
}
