package net.somewhatcity.boiler.core.dj;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DjWaveformTest {
    @Test void colorsDistinguishBassMidsAndTrebleFromRealFrequencyContent() {
        for (int band = 0; band < 3; band++) {
            double hz = new double[]{80, 1000, 12000}[band]; var builder = new DjWaveform.Builder();
            for (int i = 0; i < 48000; i++) builder.add((short)(16000 * Math.sin(2 * Math.PI * hz * i / 48000)));
            int color = builder.build().color(24000,48000);
            int[] rgb = {color >> 16 & 255, color >> 8 & 255, color & 255};
            assertTrue(rgb[band] > rgb[(band + 1) % 3] && rgb[band] > rgb[(band + 2) % 3], "dominant color for " + hz + " Hz");
        }
    }
    @Test void extremaComeFromAudioAndIncludeTheFinalPartialBucket() {
        var builder = new DjWaveform.Builder();
        builder.add((short)-12000);
        for (int i = 1; i < 480; i++) builder.add((short)0);
        builder.add((short)30000);
        var waveform = builder.build();
        assertEquals(481, waveform.samples()); assertEquals(2, waveform.size());
        assertEquals(-12000, (short)waveform.envelope(0, 480));
        assertEquals(0, (short)(waveform.envelope(0, 480) >>> 16));
        assertEquals(30000, (short)waveform.envelope(480, 481));
        assertEquals(30000, (short)(waveform.envelope(480, 481) >>> 16));
    }
    @Test void silenceEmptyDataAndRegionsOutsideTheTrackStayFlat() {
        assertEquals(0, DjWaveform.EMPTY.envelope(0, 1000));
        var builder = new DjWaveform.Builder(); for (int i = 0; i < 960; i++) builder.add((short)0);
        var waveform = builder.build();
        assertEquals(0, waveform.envelope(-200, 0)); assertEquals(0, waveform.envelope(960, 2000));
        assertEquals(0, waveform.envelope(-1000, 2000)); assertEquals(0, waveform.envelope(400, 400));
    }
    @Test void longTracksKeepBoundedStorageWithoutLosingPeaks() {
        var builder = new DjWaveform.Builder(); int count = 32768 * 480 + 1;
        for (int i = 0; i < count; i++) builder.add(i == 0 ? Short.MIN_VALUE : i == count - 1 ? Short.MAX_VALUE : (short)0);
        var waveform = builder.build();
        assertTrue(waveform.size() <= 32768); assertEquals(count, waveform.samples());
        int envelope = waveform.envelope(0, count);
        assertEquals(Short.MIN_VALUE, (short)envelope); assertEquals(Short.MAX_VALUE, (short)(envelope >>> 16));
        assertEquals(Short.MAX_VALUE, (short)(waveform.envelope(count - 1, count) >>> 16));
    }
}
