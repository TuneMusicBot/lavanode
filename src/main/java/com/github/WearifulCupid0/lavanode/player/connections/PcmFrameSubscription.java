package com.github.WearifulCupid0.lavanode.player.connections;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PcmFrameSubscription implements AutoCloseable {
    private final PcmFrameDispatcher dispatcher;
    private final int capacity;
    private final Deque<byte[]> frames;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long lastDemandNanos;

    PcmFrameSubscription(PcmFrameDispatcher dispatcher, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Frame subscription capacity must be positive");
        }

        this.dispatcher = dispatcher;
        this.capacity = capacity;
        this.frames = new ArrayDeque<>(capacity);
    }

    void offer(byte[] frame) {
        synchronized (frames) {
            if (closed.get()) {
                return;
            }

            while (frames.size() >= capacity) {
                frames.removeFirst();
            }

            frames.addLast(frame);
        }
    }

    public byte[] poll() {
        signalDemand();

        synchronized (frames) {
            return frames.pollFirst();
        }
    }

    public void keepAlive() {
        signalDemand();
    }

    public boolean isClosed() {
        return closed.get();
    }

    boolean hasRecentDemand(long nowNanos) {
        long demand = lastDemandNanos;
        return demand != 0L && nowNanos - demand <= PcmFrameDispatcher.DEMAND_TIMEOUT_NANOS;
    }

    private void signalDemand() {
        if (closed.get()) {
            return;
        }

        long now = System.nanoTime();

        synchronized (frames) {
            if (lastDemandNanos == 0L || now - lastDemandNanos > PcmFrameDispatcher.DEMAND_TIMEOUT_NANOS) {
                frames.clear();
            }

            lastDemandNanos = now;
        }

        dispatcher.onDemand();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        synchronized (frames) {
            frames.clear();
        }

        dispatcher.unsubscribe(this);
    }
}
