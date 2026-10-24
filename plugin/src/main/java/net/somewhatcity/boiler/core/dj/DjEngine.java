package net.somewhatcity.boiler.core.dj;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

/** Commands, file reads, DSP and native disposal are serialized on one owned worker. */
public final class DjEngine implements AutoCloseable {
    public record DeckView(String name, long position, long duration, boolean playing, double tempo,
                           double channel, List<Double> eq, long loopIn, long loopOut, boolean loop, float peak, String error, DjWaveform waveform, double bpm, DjBeatGrid beats, boolean synced) {}
    public record View(List<DeckView> decks, double crossfade, double master, int leader) {}
    private final Deck[] decks = {new Deck(), new Deck()};
    private final DjMixer mixer = new DjMixer();
    private final ArrayBlockingQueue<Runnable> commands = new ArrayBlockingQueue<>(256);
    private volatile View view;
    private volatile boolean closed;
    private double crossfade = .5, master = 1;
    private int leader = -1;

    public DjEngine() { publish(); }
    public View view() { return view; }
    public synchronized boolean command(Runnable command) { return !closed && commands.offer(command); }
    public synchronized boolean install(int index, DjTrack track) {
        if (!command(() -> {
            Deck deck = decks[index]; deck.dispose(); deck.track = track; deck.position = deck.cursor = 0;
            deck.loopIn = deck.loopOut = -1; deck.loop = deck.playing = false; deck.error = "";
            deck.synced = false;
            if (leader == index) decks[1 - index].synced = false;
        })) { dispose(track); return false; }
        return true;
    }
    public void transport(int index, String action) { command(() -> {
        if (action.equals("lead")) {
            leader = leader == index ? -1 : index;
            decks[index].synced = false;
            if (leader < 0) decks[1 - index].synced = false;
            return;
        }
        if (action.equals("sync")) {
            Deck target = decks[index];
            if (target.synced) { target.synced = false; target.error = ""; return; }
            if (leader < 0 || leader == index) { target.error = "SYNC: anderes LEAD"; return; }
            target.synced = sync(index, true);
            return;
        }
        Deck d = decks[index]; if (d.track == null) return;
        switch (action) {
            case "play" -> { if (d.position >= d.track.samples) d.seek(0); d.playing = !d.playing; d.transition(); }
            case "cue" -> { d.playing = false; d.loop = false; d.seek(0); }
            case "in" -> {
                long start = d.track.beats.snap(d.position, 0, d.track.samples - 1);
                if (start < 0) { d.error = "Loop: kein Beat"; break; }
                d.loopIn = start; d.loopOut = -1; d.loop = false; d.seek(d.position);
            }
            case "out" -> {
                long end = d.track.beats.snap(d.position, 0, d.track.samples);
                if (d.loopIn >= 0 && end - d.loopIn >= 4800) {
                    d.loopOut = end; d.loop = true; d.seek(d.loopIn);
                } else d.error = d.track.beats.bpm() > 0 ? "Loop: mindestens 1 Beat" : "Loop: mindestens 100 ms";
            }
            case "loop" -> {
                if (d.loopOut > d.loopIn && d.loopIn >= 0) { d.loop = !d.loop; d.seek(d.loop && d.position >= d.loopOut ? d.loopIn : d.position); }
            }
        }
    }); }
    private boolean sync(int index, boolean force) {
        Deck target = decks[index], reference = decks[leader];
        if (target.track == null || reference.track == null) { target.error = "SYNC: 2 Tracks"; return false; }
        DjBeatGrid own = target.track.beats, other = reference.track.beats;
        if (own.bpm() == 0 || other.bpm() == 0) { target.error = "SYNC: kein Beat"; return false; }
        double rate = other.bpm() * reference.rate / own.bpm();
        if (rate < .84 || rate > 1.16) { target.error = "SYNC: >16%"; return false; }
        double phase = other.phase(reference.position), period = own.period();
        double beat = Math.rint((target.position - own.offset()) / period - phase);
        double position = own.offset() + (beat + phase) * period;
        if (position < 0) position += period;
        if (position >= target.track.samples) position -= period;
        if (position < 0 || position >= target.track.samples) { target.error = "SYNC: kein Beat"; return false; }
        if (Math.abs(target.rate - rate) > 1e-9) {
            target.rate = rate;
            if (target.tempo != null) target.tempo.rate(rate);
        }
        if (target.loop) {
            // Quantized loops span whole beats, so wrapping preserves SYNC phase.
            double length = target.loopOut - target.loopIn;
            position = target.loopIn + ((position - target.loopIn) % length + length) % length;
        }
        // Do not rebuild the native filter every audio block. Correct only an
        // actual phase change (jog, cue, tempo or transport changes).
        if (force || target.playing == reference.playing && Math.abs(position - target.position) > 2)
            target.seek(position);
        return true;
    }
    public void jog(int index, int steps) { command(() -> {
        Deck d = decks[index]; if (d.track == null) return;
        double target = Math.max(0, Math.min(d.track.samples, d.position + steps * 12000L));
        if (d.loop && (target < d.loopIn || target >= d.loopOut)) d.loop = false;
        d.seek(target);
    }); }
    public void adjust(int index, String control, int steps, boolean reset) { command(() -> {
        Deck d = decks[index];
        switch (control) {
            case "tempo" -> {
                d.rate = reset ? 1 : clamp(Math.rint((d.rate - 1) * 200 + steps) / 200 + 1, .84, 1.16);
                if (d.tempo != null) d.tempo.rate(d.rate);
            }
            case "channel" -> d.channel = reset ? 1 : clamp(d.channel + steps * .05, 0, 1);
            case "cross" -> crossfade = reset ? .5 : clamp(crossfade + steps * .05, 0, 1);
            case "master" -> master = reset ? 1 : clamp(master + steps * .05, 0, 1);
            default -> { int band = switch (control) { case "low" -> 0; case "mid" -> 1; case "high" -> 2; default -> -1; };
                if (band >= 0) d.db[band] = reset ? 0 : clamp(d.db[band] + steps, -24, 12); }
        }
    }); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    /** Also used synchronously in DSP tests, without an audio backend or Bukkit. */
    public byte[] render() {
        Runnable command; while ((command = commands.poll()) != null) {
            try { command.run(); }
            catch (Exception error) { for (Deck d : decks) d.error = "Bedienfehler: " + error.getMessage(); }
        }
        if (leader >= 0) {
            int follower = 1 - leader;
            if (decks[follower].synced && !sync(follower, false)) decks[follower].synced = false;
        }
        float[][] block = {new float[960], new float[960]};
        for (int i = 0; i < 2; i++) {
            Deck d = decks[i];
            try { d.render(block[i]); }
            catch (Exception error) { d.playing = false; d.error = "Audiofehler: " + error.getMessage(); d.clearTempo(); }
        }
        byte[] result = mixer.mix(block, new double[]{decks[0].channel, decks[1].channel}, crossfade, master);
        publish(); return result;
    }
    public void run(Consumer<byte[]> output, Runnable changed) {
        long next = System.nanoTime();
        try {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                output.accept(render()); changed.run();
                next += 20_000_000;
                long remaining = next - System.nanoTime();
                if (remaining > 0) LockSupport.parkNanos(remaining);
                else if (remaining < -100_000_000) next = System.nanoTime();
            }
        } finally { dispose(); }
    }
    private void publish() {
        List<DeckView> snapshots = new ArrayList<>();
        for (Deck d : decks) snapshots.add(new DeckView(d.track == null ? "Kein Track" : d.track.name,
                (long)d.position, d.track == null ? 0 : d.track.samples, d.playing, (d.rate - 1) * 100,
                d.channel, List.of(d.db[0], d.db[1], d.db[2]), d.loopIn, d.loopOut, d.loop, d.peak, d.error,
                d.track == null ? DjWaveform.EMPTY : d.track.waveform,
                d.track == null ? 0 : d.track.beats.bpm() * d.rate,
                d.track == null ? DjBeatGrid.UNKNOWN : d.track.beats, d.synced));
        view = new View(List.copyOf(snapshots), crossfade, master, leader);
    }
    @Override public synchronized void close() { closed = true; }
    /** Called by the worker finally, or by tests after they stop rendering. */
    public void dispose() {
        close(); Runnable pending; while ((pending = commands.poll()) != null) { try { pending.run(); } catch (Exception ignored) {} }
        for (Deck d : decks) d.dispose();
    }
    private static void dispose(DjTrack track) { if (track != null) try { track.close(); } catch (Exception ignored) {} }

    private static final class Deck {
        DjTrack track;
        DjTempo tempo;
        final DjMixer.Eq eq = new DjMixer.Eq();
        final double[] db = new double[3];
        final float[] input = new float[1024];
        float[] pending = new float[0];
        int offset, fade;
        long cursor, loopIn = -1, loopOut = -1;
        double position, rate = 1, channel = 1;
        boolean playing, loop, finished, synced;
        float peak;
        float lastSample, transitionFrom;
        String error = "";

        void clearTempo() { if (tempo != null) tempo.close(); tempo = null; pending = new float[0]; offset = 0; finished = false; eq.reset(); }
        void transition() { fade = 240; transitionFrom = lastSample; }
        void seek(double target) { position = target; cursor = (long)target; clearTempo(); transition(); error = ""; }
        void dispose() { clearTempo(); DjEngine.dispose(track); track = null; }
        void release(float[] block, int start) {
            int count = Math.min(240, block.length - start);
            float from = lastSample;
            for (int i = 0; i < count; i++) block[start + i] = from * (1 - (i + 1) / (float)count);
            lastSample = 0;
        }
        void render(float[] block) throws Exception {
            peak = 0;
            if (!playing || track == null) { release(block, 0); return; }
            if (tempo == null) { tempo = new DjTempo(rate); transition(); }
            for (int at = 0; at < block.length;) {
                if (offset == pending.length) {
                    float[] ready = tempo.pull();
                    while (ready == null && !finished) {
                        long end = loop ? loopOut : track.samples;
                        if (cursor >= end) {
                            if (loop) cursor = loopIn;
                            else { tempo.finish(); finished = true; break; }
                        }
                        int count = track.read(cursor, input, 0, (int)Math.min(input.length, end - cursor));
                        if (count == 0) { tempo.finish(); finished = true; break; }
                        // Fade the two sides of a loop boundary over 2 ms.
                        if (loop) for (int n = 0; n < count; n++) {
                            long p = cursor + n;
                            input[n] *= (float)Math.min(1, Math.min((p - loopIn + 1) / 96.0, (loopOut - p) / 96.0));
                        }
                        cursor += count; tempo.push(input, count); ready = tempo.pull();
                    }
                    if (ready == null && finished) ready = tempo.pull();
                    if (ready == null) { playing = false; position = track.samples; clearTempo(); release(block, at); break; }
                    pending = ready; offset = 0;
                    if (pending.length == 0) continue;
                }
                float sample = eq.process(pending[offset++], db);
                if (fade > 0) {
                    float progress = (240 - fade--) / 240f;
                    sample = transitionFrom * (1 - progress) + sample * progress;
                }
                lastSample = sample;
                block[at++] = sample; peak = Math.max(peak, Math.abs(sample));
                position += rate;
                if (loop && position >= loopOut) position = loopIn + (position - loopIn) % (loopOut - loopIn);
                else position = Math.min(position, track.samples);
            }
        }
    }
}
