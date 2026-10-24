package net.somewhatcity.boiler.core.dj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class DjEngineTest {
    @TempDir Path directory;
    public static Path wave(Path path, double hz, int seconds) throws Exception {
        int size = seconds * 48000 * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(size + 36).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1)
                .putInt(48000).putInt(96000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(size);
        for (int i = 0; i < size / 2; i++) b.putShort((short)(10000 * Math.sin(2 * Math.PI * hz * i / 48000)));
        return Files.write(path, b.array());
    }
    @Test void tempoPreservesPitchChangesDurationAndAcceptsRuntimeCommands() throws Exception {
        for (double rate : new double[]{.84, 1, 1.16}) {
            DjTempo tempo = new DjTempo(rate);
            List<Float> result = new ArrayList<>();
            try {
                for (int at = 0; at < 144000; at += 960) {
                    float[] input = new float[960];
                    for (int i = 0; i < input.length; i++) input[i] = (float)(.3 * Math.sin(2 * Math.PI * 440 * (at + i) / 48000));
                    tempo.push(input, input.length); collect(tempo, result);
                }
                tempo.finish(); collect(tempo, result);
                assertEquals(144000 / rate, result.size(), 2200, "duration at " + rate);
                int crossings = 0; int start = 4800, end = result.size() - 4800;
                for (int i = start + 1; i < end; i++) if (result.get(i - 1) <= 0 && result.get(i) > 0) crossings++;
                assertEquals(440, crossings * 48000.0 / (end - start), 3, "pitch at " + rate);
            } finally { tempo.close(); }
        }
        try (DjTempo tempo = new DjTempo(1)) { tempo.rate(.84); tempo.rate(1.16); }
    }
    private static void collect(DjTempo tempo, List<Float> target) { float[] out; while ((out = tempo.pull()) != null) for (float x : out) target.add(x); }

    @Test void independentDecksCueLoopsAndSeekUseCurrentAudio() throws Exception {
        DjEngine engine = new DjEngine();
        try {
            engine.install(0, DjTrack.prepare(wave(directory.resolve("A.wav"), 440, 3), x -> {}));
            engine.install(1, DjTrack.prepare(wave(directory.resolve("B.wav"), 880, 3), x -> {}));
            engine.render(); assertFalse(engine.view().decks().get(0).playing());
            engine.transport(0, "play"); engine.transport(1, "play");
            for (int i = 0; i < 20; i++) assertTrue(energy(engine.render()) > .01);
            assertEquals(19200, engine.view().decks().get(0).position());
            engine.transport(0, "cue"); engine.render();
            assertEquals(0, engine.view().decks().get(0).position()); assertFalse(engine.view().decks().get(0).playing());
            assertTrue(engine.view().decks().get(1).playing());
            engine.transport(0, "in"); engine.jog(0, 2); engine.transport(0, "out"); engine.transport(0, "play");
            for (int i = 0; i < 100; i++) engine.render();
            var deck = engine.view().decks().get(0); assertTrue(deck.loop()); assertTrue(deck.position() < 24000);
            engine.jog(0, 10); engine.render(); assertFalse(engine.view().decks().get(0).loop());
            engine.adjust(1, "tempo", 100, false); engine.render(); assertEquals(16, engine.view().decks().get(1).tempo(), .001);
            engine.adjust(1, "tempo", 0, true); engine.render(); assertEquals(0, engine.view().decks().get(1).tempo(), .001);
        } finally { engine.dispose(); }
    }
    @Test void mixerCrossfadeEndpointsMiddleSmoothingAndClipping() {
        float[][] input = {new float[960], new float[960]}; Arrays.fill(input[0], .2f); Arrays.fill(input[1], .4f);
        DjMixer mixer = new DjMixer();
        byte[] out = null;
        for (int i = 0; i < 5; i++) out = mixer.mix(input, new double[]{1, 1}, 0, 1);
        assertEquals(.2, last(out), .001);
        for (int i = 0; i < 5; i++) out = mixer.mix(input, new double[]{1, 1}, 1, 1);
        assertEquals(.4, last(out), .001);
        for (int i = 0; i < 5; i++) out = mixer.mix(input, new double[]{1, 1}, .5, 1);
        assertEquals(.6 / Math.sqrt(2), last(out), .001);
        Arrays.fill(input[0], 10); Arrays.fill(input[1], 10);
        out = mixer.mix(input, new double[]{1, 1}, .5, 1); assertEquals(32767, (short)((out[0]&255) | out[1]<<8));
    }
    @Test void eqIsNeutralAtZeroAndChangesRelevantBands() {
        double neutral = eqEnergy(80, new double[]{0,0,0});
        assertTrue(eqEnergy(80, new double[]{-24,0,0}) < neutral * .4);
        assertTrue(eqEnergy(12000, new double[]{0,0,-24}) < eqEnergy(12000, new double[]{0,0,0}) * .4);
        assertTrue(eqEnergy(1000, new double[]{0,12,0}) > eqEnergy(1000, new double[]{0,0,0}) * 2);
        assertEquals(.3 / Math.sqrt(2), Math.sqrt(neutral), .001);
    }
    @Test void cachesAreDeletedOnReplacementShutdownAndRejectedInstall() throws Exception {
        Path file = wave(directory.resolve("cache.wav"), 440, 1);
        DjTrack first = DjTrack.prepare(file, x -> {}), second = DjTrack.prepare(file, x -> {});
        var cacheField = DjTrack.class.getDeclaredField("cache"); cacheField.setAccessible(true);
        Path a = (Path)cacheField.get(first), b = (Path)cacheField.get(second);
        DjEngine engine = new DjEngine();
        try {
            engine.install(0, first); engine.render();
            engine.install(0, second); engine.render(); assertFalse(Files.exists(a)); assertTrue(Files.exists(b));
        } finally { engine.dispose(); }
        assertFalse(Files.exists(b));
        DjTrack rejected = DjTrack.prepare(file, x -> {}); Path c = (Path)cacheField.get(rejected);
        assertFalse(engine.install(0, rejected)); assertFalse(Files.exists(c));
    }
    @Test void cueFlushesTempoSamplesAndLoopMinimumIsEnforced() throws Exception {
        DjEngine engine = new DjEngine();
        try {
            engine.install(0, DjTrack.prepare(wave(directory.resolve("cue.wav"), 440, 1), x -> {}));
            engine.transport(0,"play"); engine.render();
            engine.transport(0,"in"); engine.transport(0,"out"); engine.render();
            assertFalse(engine.view().decks().get(0).loop());
            engine.transport(0,"cue"); byte[] silence = engine.render();
            assertEquals(0,last(silence)); assertEquals(0,energy(engine.render())); assertEquals(0,engine.view().decks().get(0).position());
            engine.transport(0,"play"); engine.render(); assertEquals(960,engine.view().decks().get(0).position());
        } finally { engine.dispose(); }
    }
    @Test void waveformUsesThePreparedTrackAndFollowsPauseJogCueAndReplacement() throws Exception {
        var engine = new DjEngine();
        try {
            var track = DjTrack.prepare(wave(directory.resolve("waveform.wav"), 440, 3), x -> {});
            engine.install(0, track); engine.transport(0, "play"); engine.render();
            var first = engine.view().decks().get(0);
            assertSame(track.waveform, first.waveform()); assertEquals(first.duration(), first.waveform().samples());
            int envelope = first.waveform().envelope(0, 48000);
            assertEquals(-10000, (short)envelope, 2); assertEquals(10000, (short)(envelope >>> 16), 2);
            engine.transport(0, "play"); engine.render(); long paused = engine.view().decks().get(0).position();
            engine.render(); assertEquals(paused, engine.view().decks().get(0).position());
            engine.jog(0, 1); engine.render(); assertEquals(paused + 12000, engine.view().decks().get(0).position());
            engine.transport(0, "cue"); engine.render(); assertEquals(0, engine.view().decks().get(0).position());
            engine.install(0, DjTrack.prepare(wave(directory.resolve("replacement.wav"), 880, 1), x -> {})); engine.render();
            var replacement = engine.view().decks().get(0);
            assertNotSame(first.waveform(), replacement.waveform()); assertEquals(48000, replacement.waveform().samples());
        } finally { engine.dispose(); }
    }
    private static double eqEnergy(double hz, double[] db) {
        DjMixer.Eq eq = new DjMixer.Eq(); double energy = 0;
        for (int i = 0; i < 48000; i++) { float x = eq.process((float)(.3*Math.sin(2*Math.PI*hz*i/48000)), db); if (i >= 24000) energy += x*x; }
        return energy / 24000;
    }
    private static double last(byte[] pcm) { int i = pcm.length - 2; return (short)((pcm[i]&255) | pcm[i+1]<<8) / 32768.0; }
    private static double energy(byte[] pcm) { double result=0; for(int i=0;i<pcm.length;i+=2) { double x=(short)((pcm[i]&255)|pcm[i+1]<<8)/32768.0; result+=x*x; } return Math.sqrt(result/(pcm.length/2)); }

    @Test void layoutHasNoOverlapsScalesHitAreasAndRendersPreview() throws Exception {
        for (var a : DjLayout.CONTROLS) for (var b : DjLayout.CONTROLS) if (a != b) assertFalse(a.bounds().intersects(b.bounds()), a.id()+" overlaps "+b.id());
        for (int[] size : new int[][]{{256,128},{512,256},{640,360}}) {
            DjLayout layout = new DjLayout(size[0], size[1]);
            for (var c : DjLayout.CONTROLS) assertEquals(c, layout.hit((int)(layout.left+(c.bounds().getCenterX())*layout.scale), (int)(layout.top+c.bounds().getCenterY()*layout.scale)));
            BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics(); try { DjView.draw(g, size[0], size[1], waveformPreview(), new String[]{"", ""}); } finally { g.dispose(); }
            Path report = Path.of("build/reports/djcontroller-preview"); Files.createDirectories(report);
            ImageIO.write(image, "png", report.resolve(size[0]+"x"+size[1]+".png").toFile());
            g = image.createGraphics();
            try {
                DjBrowser.draw(g, size[0], size[1], 1, new net.somewhatcity.boiler.core.media.MediaBrowser.View("Musik", List.of(
                        new net.somewhatcity.boiler.core.media.MediaLibrary.Entry("Musik/Album", "Album", net.somewhatcity.boiler.core.media.MediaLibrary.Kind.DIRECTORY, 0),
                        new net.somewhatcity.boiler.core.media.MediaLibrary.Entry("Musik/Track.wav", "Track mit Leerzeichen.wav", net.somewhatcity.boiler.core.media.MediaLibrary.Kind.AUDIO, 96000)), 1, "", false));
            } finally { g.dispose(); }
            ImageIO.write(image, "png", report.resolve(size[0]+"x"+size[1]+"-browser.png").toFile());
        }
    }
    private static DjEngine.View waveformPreview() {
        var builder = new DjWaveform.Builder();
        for (int i = 0; i < 480000; i++) {
            double amplitude = .08 + .7 * Math.pow((1 + Math.cos(2 * Math.PI * i / 24000)) / 2, 4);
            double hz = new double[]{80, 1000, 12000}[(i / 12000) % 3];
            builder.add((short)(32767 * amplitude * Math.sin(2 * Math.PI * hz * i / 48000)));
        }
        var waveform = builder.build();
        return new DjEngine.View(List.of(
                new DjEngine.DeckView("Night Drive.wav", 96000, 480000, true, 0, 1, List.of(0.,0.,0.), 67500,112500,true,.6f,"",waveform,128,new DjBeatGrid(128,0),false),
                new DjEngine.DeckView("Sunset Mix.mp3", 156000, 480000, true, 2.5, 1, List.of(0.,0.,0.), 141375,187500,false,.4f,"",waveform,128,new DjBeatGrid(128/1.025,3000),true)), .5, 1, 0);
    }
}
