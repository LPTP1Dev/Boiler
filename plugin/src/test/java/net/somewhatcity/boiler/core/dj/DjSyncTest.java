package net.somewhatcity.boiler.core.dj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DjSyncTest {
    @TempDir Path directory;
    private static short sample(int i, double bpm, double offset) {
        double beat = (i / 48000.0 - offset) * bpm / 60;
        if (beat < 0) return 0;
        double age = (beat - Math.floor(beat)) * 60 / bpm;
        return age < .055 ? (short)(26000 * Math.exp(-age * 85) * Math.sin(2 * Math.PI * 100 * age)) : 0;
    }
    private Path track(String name, double bpm, double offset) throws Exception {
        int samples = 12 * 48000;
        ByteBuffer bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes()).putInt(samples * 2 + 36).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1)
                .putInt(48000).putInt(96000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(samples * 2);
        for (int i = 0; i < samples; i++) bytes.putShort(sample(i, bpm, offset));
        return Files.write(directory.resolve(name), bytes.array());
    }
    @Test void estimatesTempoAndOffsetForRhythmicAudioAndRejectsSilenceAndSteadyTone() throws Exception {
        for (double bpm : new double[]{80,120,128,150,180}) {
            var builder = new DjBeatGrid.Builder();
            for (int i = 0; i < 12 * 48000; i++) builder.add(sample(i,bpm,.13));
            var grid = builder.build(); assertEquals(bpm,grid.bpm(),.4,"BPM " + bpm);
            double phase = grid.phase(.13 * 48000); assertTrue(Math.min(phase,1-phase)<.04,"offset at " + bpm + ": " + grid.offset());
        }
        for (boolean tone : new boolean[]{false,true}) {
            var builder = new DjBeatGrid.Builder();
            for (int i=0;i<6*48000;i++) builder.add(tone ? (short)(10000*Math.sin(2*Math.PI*440*i/48000)) : (short)0);
            assertEquals(0,builder.build().bpm());
        }
    }
    @Test void syncMatchesEffectiveTempoAndPhaseWithoutChangingTheReferenceOrPlaybackState() throws Exception {
        var engine = new DjEngine();
        try {
            var a = DjTrack.prepare(track("A.wav",120,.13), x -> {});
            var b = DjTrack.prepare(track("B.wav",128,.08), x -> {});
            engine.install(0,a); engine.install(1,b);
            engine.jog(0,7); engine.jog(1,9); engine.adjust(1,"tempo",10,false); engine.render();
            long referencePosition = engine.view().decks().get(1).position();
            engine.transport(1,"lead"); engine.transport(0,"sync"); engine.render();
            var target = engine.view().decks().get(0); var reference = engine.view().decks().get(1);
            assertEquals(reference.bpm(),target.bpm(),.001); assertEquals(referencePosition,reference.position());
            assertFalse(target.playing()); assertEquals(5,reference.tempo(),.0001);
            double difference = Math.abs(a.beats.phase(target.position())-b.beats.phase(reference.position()));
            assertTrue(Math.min(difference,1-difference)<.001);
            assertGridLinesAlign(engine.view());
            engine.transport(0,"play"); engine.transport(1,"play");
            for(int i=0;i<20;i++) engine.render();
            target=engine.view().decks().get(0); reference=engine.view().decks().get(1);
            difference=Math.abs(a.beats.phase(target.position())-b.beats.phase(reference.position()));
            assertTrue(Math.min(difference,1-difference)<.001);
            assertGridLinesAlign(engine.view());
        } finally { engine.dispose(); }
    }
    private static void assertGridLinesAlign(DjEngine.View view) {
        var image = new java.awt.image.BufferedImage(256, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { DjView.draw(graphics, 256, 128, view, new String[]{"", ""}); }
        finally { graphics.dispose(); }
        int gridColor = new java.awt.Color(183, 205, 222).getRGB();
        int lines = 0;
        for (int column = 0; column < 250; column++) {
            boolean a = image.getRGB(3 + column, 12) == gridColor;
            boolean b = image.getRGB(3 + column, 25) == gridColor;
            assertEquals(a, b, "Synced grid column " + column);
            if (a) lines++;
        }
        assertTrue(lines >= 4, "Visible beat lines on both waveforms");
    }
    @Test void loopEndpointsSnapToThePublishedGridAndStayInPhaseAfterSyncAndWraps() throws Exception {
        var engine = new DjEngine();
        try {
            engine.install(0, DjTrack.prepare(track("loop.wav",120,.13), x -> {}));
            engine.install(1, DjTrack.prepare(track("reference.wav",128,.08), x -> {}));
            engine.jog(0, 7); engine.transport(0, "in"); engine.render();
            var deck = engine.view().decks().get(0);
            long start = deck.loopIn();
            assertEquals(deck.beats().snap(84000, 0, deck.duration() - 1), start);
            assertNotEquals(84000, start);
            engine.transport(0, "out"); engine.render();
            assertFalse(engine.view().decks().get(0).loop());
            engine.jog(0, 4); engine.transport(0, "out"); engine.render();
            deck = engine.view().decks().get(0);
            assertTrue(deck.loop());
            assertEquals(deck.beats().snap(132000, 0, deck.duration()), deck.loopOut());
            assertEquals(Math.rint((deck.loopOut() - start) / deck.beats().period()),
                    (deck.loopOut() - start) / deck.beats().period(), .0001);
            engine.jog(1, 9); engine.transport(1, "lead"); engine.transport(0, "sync"); engine.render();
            assertTrue(engine.view().decks().get(0).loop());
            assertGridLinesAlign(engine.view());
            engine.transport(0, "play"); engine.transport(1, "play");
            for (int i = 0; i < 180; i++) engine.render();
            deck = engine.view().decks().get(0);
            var reference = engine.view().decks().get(1);
            assertTrue(deck.loop()); assertTrue(deck.position() >= start && deck.position() < deck.loopOut());
            double difference = Math.abs(deck.beats().phase(deck.position()) - reference.beats().phase(reference.position()));
            assertTrue(Math.min(difference, 1 - difference) < .001);
            assertGridLinesAlign(engine.view());
        } finally { engine.dispose(); }
    }
    @Test void snappingKeepsEndpointsOnValidBeatsAtTrackBoundaries() {
        var grid = new DjBeatGrid(120, 6240);
        assertEquals(6240, grid.snap(0, 0, 48000));
        assertEquals(30240, grid.snap(48000, 0, 48000));
        assertEquals(-1, grid.snap(0, 0, 6000));
        assertEquals(12345, DjBeatGrid.UNKNOWN.snap(12345, 0, 48000));
    }
    @Test void syncToggleFollowsLeaderTempoAndJogUntilDisabledAndAllowsRoleChanges() throws Exception {
        var engine = new DjEngine();
        try {
            engine.install(0, DjTrack.prepare(track("follow.wav",120,.13), x -> {}));
            engine.install(1, DjTrack.prepare(track("lead.wav",128,.08), x -> {}));
            engine.jog(0, 8); engine.jog(1, 10);
            engine.transport(1, "lead"); engine.transport(0, "sync");
            engine.transport(0, "play"); engine.transport(1, "play"); engine.render();
            assertEquals(1, engine.view().leader()); assertTrue(engine.view().decks().get(0).synced());
            for (int step = 0; step < 3; step++) {
                engine.adjust(1, "tempo", 2, false); engine.jog(1, 1);
                for (int i = 0; i < 20; i++) engine.render();
                var follower = engine.view().decks().get(0); var leader = engine.view().decks().get(1);
                assertEquals(leader.bpm(), follower.bpm(), .001);
                assertTrue(follower.synced()); assertGridLinesAlign(engine.view());
            }
            engine.transport(0, "sync"); engine.render();
            var free = engine.view().decks().get(0); assertFalse(free.synced());
            engine.adjust(1, "tempo", 2, false); engine.jog(1, 1); engine.render();
            assertEquals(free.tempo(), engine.view().decks().get(0).tempo());
            engine.transport(0, "lead"); engine.transport(1, "sync"); engine.render();
            assertEquals(0, engine.view().leader()); assertTrue(engine.view().decks().get(1).synced());
            assertFalse(engine.view().decks().get(0).synced()); assertGridLinesAlign(engine.view());
            engine.transport(0, "lead"); engine.render();
            assertEquals(-1, engine.view().leader()); assertFalse(engine.view().decks().get(1).synced());
        } finally { engine.dispose(); }
    }
    @Test void syncRequiresAnotherLeaderAndStopsFollowingIfTempoBecomesUnsupported() throws Exception {
        var engine = new DjEngine();
        try {
            engine.install(0, DjTrack.prepare(track("target.wav",120,.13), x -> {}));
            engine.install(1, DjTrack.prepare(track("leader.wav",128,.08), x -> {}));
            engine.transport(0, "sync"); engine.render();
            assertFalse(engine.view().decks().get(0).synced());
            assertTrue(engine.view().decks().get(0).error().contains("LEAD"));
            engine.transport(1, "lead"); engine.transport(0, "sync"); engine.render();
            assertTrue(engine.view().decks().get(0).synced());
            engine.adjust(1, "tempo", 32, false); engine.render();
            assertFalse(engine.view().decks().get(0).synced());
            assertTrue(engine.view().decks().get(0).error().contains("16%"));
        } finally { engine.dispose(); }
    }
    @Test void subdivisionsAndIntroDoNotCancelAValidBeatGrid() throws Exception {
        var builder = new DjBeatGrid.Builder();
        double bpm = 128, period = 60 / bpm;
        for (int i = 0; i < 30 * 48000; i++) {
            double time = i / 48000.0 - 3.13;
            double value = 0;
            if (time >= 0) {
                double kickAge = time % period, hatAge = (time + period / 2) % period;
                value = 16000 * Math.exp(-kickAge * 85) * Math.sin(2 * Math.PI * 100 * kickAge)
                        + 22000 * Math.exp(-hatAge * 100) * Math.sin(2 * Math.PI * 8000 * hatAge);
            }
            builder.add((short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,value)));
        }
        var grid = builder.build(); assertEquals(bpm,grid.bpm(),.2);
        double phase=grid.phase(3.13*48000); assertTrue(Math.min(phase,1-phase)<.06);
    }
    @Test void unsupportedSyncKeepsTempoAndPositionAndReportsTheReason() throws Exception {
        var engine = new DjEngine();
        try {
            engine.transport(0,"sync"); engine.render(); assertFalse(engine.view().decks().get(0).error().isBlank());
            engine.install(0,DjTrack.prepare(track("slow.wav",80,.1),x -> {}));
            engine.install(1,DjTrack.prepare(track("fast.wav",180,.1),x -> {})); engine.render();
            engine.transport(1,"lead"); engine.transport(0,"sync"); engine.render();
            var target = engine.view().decks().get(0);
            assertEquals(0,target.tempo(),.0001); assertEquals(0,target.position()); assertTrue(target.error().contains("16%"));
        } finally { engine.dispose(); }
    }
}
