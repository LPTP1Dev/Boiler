package net.somewhatcity.boiler.core.dj;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import java.nio.ShortBuffer;
import java.nio.file.*;
import java.util.ArrayList;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in local music regression; never includes or downloads audio. */
class DjMusicAnalysisTest {
    @Test void detectsBeatsInLocalMusicLibrary() throws Exception {
        String media = System.getenv("BOILER_DJ_TEST_MEDIA");
        assumeTrue(media != null && !media.isBlank(), "Set BOILER_DJ_TEST_MEDIA to a local music directory");
        Path report = Path.of("build/reports/dj-music"); Files.createDirectories(report);
        var results = new ArrayList<String>(); var failed = new ArrayList<String>();
        try (var files = Files.list(Path.of(media))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".mp3")).sorted().toList()) {
                var builder = new DjBeatGrid.Builder(); long samples = 0;
                try (var decoder = new FFmpegFrameGrabber(path.toString())) {
                    decoder.setAudioChannels(1); decoder.setSampleRate(48000); decoder.setSampleFormat(AV_SAMPLE_FMT_S16); decoder.start();
                    Frame frame;
                    while (samples < 120 * 48000 && (frame = decoder.grabSamples()) != null) {
                        if (frame.samples == null || !(frame.samples[0] instanceof ShortBuffer buffer)) continue;
                        var input = buffer.duplicate(); while (input.hasRemaining() && samples < 120 * 48000) { builder.add(input.get()); samples++; }
                    }
                }
                var grid = builder.build();
                String result = path.getFileName()+": BPM="+grid.bpm()+", offset="+grid.offset()/48000;
                results.add(result); System.out.println(result);
                if (grid.bpm()==0) failed.add(path.getFileName().toString());
            }
        }
        Files.write(report.resolve("results.txt"), results);
        assertFalse(results.isEmpty(),"No MP3 files found"); assertTrue(failed.isEmpty(),"No beat detected: "+failed);
    }
    @Test void syncsTheProvidedLocalMusicPair() throws Exception {
        String media = System.getenv("BOILER_DJ_TEST_MEDIA");
        assumeTrue(media != null && !media.isBlank(), "Set BOILER_DJ_TEST_MEDIA to the local music directory");
        Path aFile = Path.of(media).resolve("Klangkuenstler - Die Welt Brennt.mp3");
        Path bFile = Path.of(media).resolve("Klangkuenstler - Untergang.mp3");
        assumeTrue(Files.isRegularFile(aFile) && Files.isRegularFile(bFile), "Local regression pair unavailable");
        var engine = new DjEngine();
        try {
            var a = DjTrack.prepare(aFile, progress -> {});
            engine.install(0,a); engine.render();
            var b = DjTrack.prepare(bFile, progress -> {});
            engine.install(1,b); engine.jog(0,100); engine.jog(1,120); engine.render();
            assertEquals(150,a.beats.bpm(),.3); assertEquals(142,b.beats.bpm(),.3);
            engine.transport(0,"lead"); engine.transport(1,"sync"); engine.render();
            var target = engine.view().decks().get(1); var reference = engine.view().decks().get(0);
            assertEquals(reference.bpm(),target.bpm(),.001); assertTrue(target.error().isBlank());
            double difference = Math.abs(b.beats.phase(target.position())-a.beats.phase(reference.position()));
            assertTrue(Math.min(difference,1-difference)<.001);
        } finally { engine.dispose(); }
    }
}
