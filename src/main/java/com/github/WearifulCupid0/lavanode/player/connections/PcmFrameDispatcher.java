package com.github.WearifulCupid0.lavanode.player.connections;

import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The single PCM clock for a player. Every frame provider now outputs
 * DISCORD_PCM_S16_BE and this dispatcher fans the same PCM frame out to
 * platform-specific encoders/consumers.
 */
public final class PcmFrameDispatcher {
    public static final long FRAME_DURATION_MS = 20L;
    static final long DEMAND_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);

    private static final Logger log = LoggerFactory.getLogger(PcmFrameDispatcher.class);

    private final Object lifecycleLock = new Object();
    private final PlayerSession session;
    private final ScheduledExecutorService executor;
    private final Set<PcmFrameSubscription> subscriptions = ConcurrentHashMap.newKeySet();

    private final ByteBuffer buffer = ByteBuffer.allocate(
            StandardAudioDataFormats.DISCORD_PCM_S16_BE.maximumChunkSize()
    );
    private final MutableAudioFrame audioFrame = new MutableAudioFrame();

    private ScheduledFuture<?> dispatchTask;
    private volatile boolean destroyed;

    public PcmFrameDispatcher(PlayerSession session, ScheduledExecutorService executor) {
        this.session = session;
        this.executor = executor;
        this.audioFrame.setBuffer(buffer);
    }

    public PcmFrameSubscription subscribe(int capacity) {
        synchronized (lifecycleLock) {
            if (destroyed) {
                throw new IllegalStateException("PCM frame dispatcher has been destroyed");
            }

            PcmFrameSubscription subscription = new PcmFrameSubscription(this, capacity);
            subscriptions.add(subscription);
            return subscription;
        }
    }

    void onDemand() {
        synchronized (lifecycleLock) {
            if (!destroyed) {
                startLocked();
            }
        }
    }

    void unsubscribe(PcmFrameSubscription subscription) {
        synchronized (lifecycleLock) {
            subscriptions.remove(subscription);

            if (!hasDemandingSubscriber(System.nanoTime())) {
                stopLocked();
            }
        }
    }

    public boolean hasDemandingSubscribers() {
        return hasDemandingSubscriber(System.nanoTime());
    }

    public void destroy() {
        synchronized (lifecycleLock) {
            if (destroyed) {
                return;
            }

            destroyed = true;
            stopLocked();

            for (PcmFrameSubscription subscription : subscriptions.toArray(PcmFrameSubscription[]::new)) {
                subscription.close();
            }

            subscriptions.clear();
        }
    }

    private void startLocked() {
        if (dispatchTask != null && !dispatchTask.isCancelled() && !dispatchTask.isDone()) {
            return;
        }

        try {
            dispatchTask = executor.scheduleAtFixedRate(
                    this::dispatchFrameSafely,
                    0L,
                    FRAME_DURATION_MS,
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException exception) {
            subscriptions.clear();
            throw new IllegalStateException("PCM frame executor is not available", exception);
        }
    }

    private void stopLocked() {
        ScheduledFuture<?> task = dispatchTask;
        dispatchTask = null;

        if (task != null) {
            task.cancel(false);
        }
    }

    private void dispatchFrameSafely() {
        if (destroyed || subscriptions.isEmpty()) {
            return;
        }

        try {
            long nowNanos = System.nanoTime();

            if (!hasDemandingSubscriber(nowNanos)) {
                synchronized (lifecycleLock) {
                    if (!hasDemandingSubscriber(System.nanoTime())) {
                        stopLocked();
                    }
                }
                return;
            }

            dispatchFrame();
        } catch (Throwable error) {
            log.error("Failed to dispatch PCM frame for player {}", session.getId(), error);
        }
    }

    private void dispatchFrame() {
        buffer.clear();

        if (!session.providePcm(audioFrame)) {
            return;
        }

        buffer.flip();

        if (!buffer.hasRemaining()) {
            return;
        }

        byte[] packet = new byte[buffer.remaining()];
        buffer.get(packet);

        long nowNanos = System.nanoTime();

        for (PcmFrameSubscription subscription : subscriptions) {
            if (subscription.hasRecentDemand(nowNanos)) {
                subscription.offer(packet);
            }
        }
    }

    private boolean hasDemandingSubscriber(long nowNanos) {
        for (PcmFrameSubscription subscription : subscriptions) {
            if (subscription.hasRecentDemand(nowNanos)) {
                return true;
            }
        }

        return false;
    }
}
