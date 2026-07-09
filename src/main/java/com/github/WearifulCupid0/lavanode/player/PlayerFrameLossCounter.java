package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.util.ByteRingBuffer;

import java.util.concurrent.TimeUnit;

public class PlayerFrameLossCounter {
    private static final long ACCEPTABLE_TRACK_SWITCH_TIME = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long ONE_SECOND = TimeUnit.SECONDS.toNanos(1);

    public static final int EXPECTED_PACKET_COUNT_PER_MIN = (60 * 1000) / 20;

    private final ByteRingBuffer loss = new ByteRingBuffer(60);
    private final ByteRingBuffer success = new ByteRingBuffer(60);
    private long playingSince = Long.MAX_VALUE;
    private long trackStart;
    private long lastTrackEnd;
    private long lastUpdate;
    private byte currentLoss;
    private byte currentSuccess;

    public void onSuccess() {
        checkTime();
        currentSuccess++;
    }

    public void onFail() {
        checkTime();
        currentLoss++;
    }

    public ByteRingBuffer lastMinuteLoss() {
        return loss;
    }

    public ByteRingBuffer lastMinuteSuccess() {
        return success;
    }

    public boolean isDataUsable() {
        if(trackStart - lastTrackEnd > ACCEPTABLE_TRACK_SWITCH_TIME && lastTrackEnd != 0) {
            return false;
        }
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - playingSince) >= 60;
    }

    private void checkTime() {
        var now = System.nanoTime();
        if(now - lastUpdate > ONE_SECOND) {
            lastUpdate = now;
            loss.put(currentLoss);
            success.put(currentSuccess);
            currentLoss = 0;
            currentSuccess = 0;
        }
    }

    public void start() {
        trackStart = System.nanoTime();
        if(trackStart - playingSince > ACCEPTABLE_TRACK_SWITCH_TIME || playingSince == Long.MAX_VALUE) {
            playingSince = trackStart;
            loss.clear();
            success.clear();
        }
    }

    public void end() {
        lastTrackEnd = System.nanoTime();
    }
}
