package net.somewhatcity.boiler.core.media.playback;

import java.util.ArrayDeque;

/** Timestamped events keep audio and video on the same bounded playback timeline. */
public final class MediaQueue<T> {
    public record Entry<T>(T value, long timestampMicros, boolean discontinuity, long generation) {}
    private final ArrayDeque<Entry<T>> queue = new ArrayDeque<>();
    private final int capacity;
    private final boolean live;
    private final java.util.function.Consumer<T> discard;
    private boolean finished;
    private boolean cancelled;
    private boolean dropped;
    private long generation;

    public MediaQueue(int capacity, boolean live) {
        this(capacity, live, value -> {});
    }

    public MediaQueue(int capacity, boolean live, java.util.function.Consumer<T> discard) {
        if (capacity < 1) throw new IllegalArgumentException("buffer must be positive");
        this.capacity = capacity;
        this.live = live;
        this.discard = discard;
    }

    public synchronized void put(T value, long timestampMicros) throws InterruptedException {
        put(value, timestampMicros, generation);
    }
    public synchronized void put(T value, long timestampMicros, long expectedGeneration) throws InterruptedException {
        while (!finished && expectedGeneration == generation && !live && queue.size() == capacity) wait();
        if (finished || expectedGeneration != generation) { discard.accept(value); return; }
        if (live) {
            while (!queue.isEmpty() && (queue.size() == capacity || timestampMicros - queue.peekFirst().timestampMicros() > 500_000)) {
                discard.accept(queue.removeFirst().value());
                dropped = true;
            }
        }
        queue.addLast(new Entry<>(value, timestampMicros, false, generation));
        notifyAll();
    }

    public synchronized Entry<T> take() throws InterruptedException {
        while (queue.isEmpty() && !finished) wait();
        if (cancelled || queue.isEmpty()) return null;
        Entry<T> entry = queue.removeFirst();
        boolean reset = dropped;
        dropped = false;
        notifyAll();
        return new Entry<>(entry.value(), entry.timestampMicros(), reset, entry.generation());
    }

    public synchronized long generation() { return generation; }
    public synchronized void reset() {
        if (cancelled) return;
        generation++;
        while (!queue.isEmpty()) discard.accept(queue.removeFirst().value());
        finished = false; dropped = true;
        notifyAll();
    }

    public synchronized int size() { return queue.size(); }
    public synchronized void finish() { finished = true; notifyAll(); }
    public synchronized void cancel() {
        cancelled = true;
        finished = true;
        while (!queue.isEmpty()) discard.accept(queue.removeFirst().value());
        notifyAll();
    }
}
