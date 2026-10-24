package net.somewhatcity.boiler.core.dj;

/** Stateful, smoothed three-band EQ and a stereo-style equal-power crossfade into mono. */
public final class DjMixer {
    public static final class Eq {
        private double low, upper, lo = 1, mid = 1, hi = 1;
        private static final double LOW = 1 - Math.exp(-2 * Math.PI * 250 / 48000);
        private static final double HIGH = 1 - Math.exp(-2 * Math.PI * 4000 / 48000);
        public float process(float sample, double[] db) {
            low += LOW * (sample - low); upper += HIGH * (sample - upper);
            lo += .004 * (Math.pow(10, db[0] / 20) - lo);
            mid += .004 * (Math.pow(10, db[1] / 20) - mid);
            hi += .004 * (Math.pow(10, db[2] / 20) - hi);
            return (float)(low * lo + (upper - low) * mid + (sample - upper) * hi);
        }
        public void reset() { low = upper = 0; }
    }
    private double left = Math.sqrt(.5), right = Math.sqrt(.5), master = 1;
    public byte[] mix(float[][] audio, double[] channels, double crossfade, double volume) {
        byte[] result = new byte[audio[0].length * 2];
        double a = Math.cos(crossfade * Math.PI / 2) * channels[0];
        double b = Math.sin(crossfade * Math.PI / 2) * channels[1];
        for (int i = 0; i < audio[0].length; i++) {
            left += .004 * (a - left); right += .004 * (b - right); master += .004 * (volume - master);
            double value = (audio[0][i] * left + audio[1][i] * right) * master;
            // Saturation protects the PCM conversion; 20 ms smoothing avoids fader clicks.
            int sample = (int)Math.round(Math.max(-1, Math.min(1, value)) * 32767);
            result[i * 2] = (byte)sample; result[i * 2 + 1] = (byte)(sample >> 8);
        }
        return result;
    }
}
