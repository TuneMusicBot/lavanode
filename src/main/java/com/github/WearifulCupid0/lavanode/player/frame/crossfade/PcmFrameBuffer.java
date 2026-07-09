package com.github.WearifulCupid0.lavanode.player.frame.crossfade;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

final class PcmFrameBuffer {
    private final Deque<short[]> frames = new ArrayDeque<>();
    private final int limitFrames;

    PcmFrameBuffer(int limitFrames) {
        this.limitFrames = limitFrames;
    }

    boolean offerCopy(short[] samples) {
        if (frames.size() >= limitFrames) {
            return false;
        }

        frames.addLast(Arrays.copyOf(samples, samples.length));
        return true;
    }

    boolean pollInto(short[] target) {
        short[] frame = frames.pollFirst();

        if (frame == null) {
            return false;
        }

        System.arraycopy(frame, 0, target, 0, Math.min(frame.length, target.length));

        if (frame.length < target.length) {
            Arrays.fill(target, frame.length, target.length, (short) 0);
        }

        return true;
    }

    void moveRemainingTo(PcmFrameBuffer target) {
        while (!frames.isEmpty()) {
            target.frames.addLast(frames.pollFirst());
        }
    }

    int size() {
        return frames.size();
    }

    boolean isEmpty() {
        return frames.isEmpty();
    }

    boolean isFull() {
        return frames.size() >= limitFrames;
    }

    void clear() {
        frames.clear();
    }
}
