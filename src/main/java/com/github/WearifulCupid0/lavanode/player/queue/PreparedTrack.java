package com.github.WearifulCupid0.lavanode.player.queue;

import com.github.WearifulCupid0.lavanode.player.frame.gapless.GaplessFrameData;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PreparedTrack {
    private final QueueEntry entry;
    private final AudioPlayer player;
    private final ArrayBlockingQueue<GaplessFrameData> buffer;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile boolean finished;
    private volatile Future<?> future;

    public PreparedTrack(
            QueueEntry entry,
            AudioPlayer player,
            int bufferFrames
    ) {
        this.entry = entry;
        this.player = player;
        this.buffer = new ArrayBlockingQueue<>(bufferFrames);
    }

    public QueueEntry getEntry() {
        return entry;
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    public ArrayBlockingQueue<GaplessFrameData> getBuffer() {
        return buffer;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isFinished() {
        return finished;
    }

    public void markFinished() {
        this.finished = true;
    }

    public void setFuture(Future<?> future) {
        this.future = future;
    }

    public void stopPump() {
        running.set(false);

        Future<?> currentFuture = future;
        if (currentFuture != null) {
            currentFuture.cancel(true);
        }
    }

    public void discard() {
        stopPump();
        player.stopTrack();
        buffer.clear();
    }
}
