package com.github.WearifulCupid0.lavanode.player.connections;

import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.connections.audio.LavaplayerOpusEncoder;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shared encoded branch. PCM remains the source of truth; Discord and HTTP
 * subscribe here so one Lavaplayer Opus encode is reused by every Opus output.
 */
public final class OpusFrameDispatcher {
    static final long DEMAND_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);

    private static final Logger log = LoggerFactory.getLogger(OpusFrameDispatcher.class);

    private final Object lifecycleLock = new Object();
    private final PlayerSession session;
    private final ScheduledExecutorService executor;
    private final Set<OpusFrameSubscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final PcmFrameSubscription pcmSubscription;
    private final LavaplayerOpusEncoder encoder;

    private ScheduledFuture<?> dispatchTask;
    private volatile boolean destroyed;

    public OpusFrameDispatcher(
            PlayerSession session,
            PcmFrameDispatcher pcmDispatcher,
            ScheduledExecutorService executor,
            AudioConfiguration audioConfiguration
    ) {
        this.session = session;
        this.executor = executor;
        this.pcmSubscription = pcmDispatcher.subscribe(4);
        this.encoder = new LavaplayerOpusEncoder(audioConfiguration);
    }

    public OpusFrameSubscription subscribe(int capacity) {
        synchronized (lifecycleLock) {
            if (destroyed) {
                throw new IllegalStateException("Opus frame dispatcher has been destroyed");
            }

            OpusFrameSubscription subscription = new OpusFrameSubscription(this, capacity);
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

    void unsubscribe(OpusFrameSubscription subscription) {
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

            for (OpusFrameSubscription subscription : subscriptions.toArray(OpusFrameSubscription[]::new)) {
                subscription.close();
            }

            subscriptions.clear();
            pcmSubscription.close();
            encoder.close();
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
                    PcmFrameDispatcher.FRAME_DURATION_MS,
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException exception) {
            subscriptions.clear();
            throw new IllegalStateException("Opus frame executor is not available", exception);
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

            pcmSubscription.keepAlive();
            byte[] pcm = pcmSubscription.poll();
            if (pcm == null) {
                return;
            }

            byte[] opus = encoder.encode(pcm);
            if (opus == null || opus.length == 0) {
                return;
            }

            nowNanos = System.nanoTime();
            for (OpusFrameSubscription subscription : subscriptions) {
                if (subscription.hasRecentDemand(nowNanos)) {
                    subscription.offer(opus);
                }
            }
        } catch (Throwable error) {
            log.error("Failed to encode/dispatch Opus frame for player {}", session.getId(), error);
        }
    }

    private boolean hasDemandingSubscriber(long nowNanos) {
        for (OpusFrameSubscription subscription : subscriptions) {
            if (subscription.hasRecentDemand(nowNanos)) {
                return true;
            }
        }

        return false;
    }
}
