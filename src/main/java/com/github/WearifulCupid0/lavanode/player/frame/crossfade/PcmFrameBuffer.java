package com.github.WearifulCupid0.lavanode.player.frame.crossfade;

import java.util.Arrays;

final class PcmFrameBuffer {
    private final short[][] frames;
    private int head;
    private int size;

    PcmFrameBuffer(int limitFrames) {
        int safeLimit = Math.max(1, limitFrames);
        this.frames = new short[safeLimit][CrossfadeAudio.SAMPLE_COUNT];
    }

    void offerCopy(short[] samples) {
        if (isFull()) {
            return;
        }

        int index = physicalIndex(size);
        System.arraycopy(samples, 0, frames[index], 0, Math.min(samples.length, CrossfadeAudio.SAMPLE_COUNT));

        if (samples.length < CrossfadeAudio.SAMPLE_COUNT) {
            Arrays.fill(frames[index], samples.length, CrossfadeAudio.SAMPLE_COUNT, (short) 0);
        }

        size++;
    }

    boolean pollInto(short[] target) {
        if (size == 0) {
            return false;
        }

        short[] frame = frames[head];
        System.arraycopy(frame, 0, target, 0, Math.min(frame.length, target.length));

        if (frame.length < target.length) {
            Arrays.fill(target, frame.length, target.length, (short) 0);
        }

        head = (head + 1) % frames.length;
        size--;

        return true;
    }

    void moveRemainingTo(PcmFrameBuffer target) {
        while (!isEmpty() && !target.isFull()) {
            int index = head;
            target.offerCopy(frames[index]);
            head = (head + 1) % frames.length;
            size--;
        }

        if (isEmpty()) {
            clear();
        }
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    boolean isFull() {
        return size >= frames.length;
    }

    void clear() {
        head = 0;
        size = 0;
    }

    private int physicalIndex(int logicalOffset) {
        return (head + logicalOffset) % frames.length;
    }
}
