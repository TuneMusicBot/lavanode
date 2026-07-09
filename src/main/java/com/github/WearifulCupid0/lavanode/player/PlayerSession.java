package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.player.filters.PlayerFilters;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProvider;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProviderMode;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProviderSnapshot;
import com.github.WearifulCupid0.lavanode.player.frame.crossfade.CrossfadeFrameProvider;
import com.github.WearifulCupid0.lavanode.player.frame.gapless.GaplessFrameProvider;
import com.github.WearifulCupid0.lavanode.player.frame.normal.NormalFrameProvider;
import com.github.WearifulCupid0.lavanode.player.queue.PlayerQueue;
import com.github.WearifulCupid0.lavanode.player.queue.QueueEntry;
import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public final class PlayerSession {
    private static final long GAPLESS_PRELOAD_BEFORE_MS = Main.TRACK_STUCK_THRESHOLD;
    private static final int GAPLESS_PREBUFFER_FRAMES = 100;
    private static final long GAPLESS_PRELOAD_TIMEOUT_MS = 15_000;

    private static final long CROSSFADE_DURATION_MS = 15_000;
    private static final long CROSSFADE_PRELOAD_LEAD_MS = Main.TRACK_STUCK_THRESHOLD;

    private final Object lock = new Object();

    private final String id;
    private final String userId;

    private final PlayerQueue queue;
    private final PlayerEventListener listener;
    private final PlayerSessionManager sessionManager;

    private final PlayerFilters playerFilters = new PlayerFilters();
    private final PlayerFrameLossCounter playerFrameLossCounter = new PlayerFrameLossCounter();

    //For Gapless and Crossfade frame providers
    private final ExecutorService preloadExecutor;

    private PlayerFrameProvider frameProvider;
    private PlayerFrameProviderMode frameProviderMode = PlayerFrameProviderMode.NORMAL;
    private PlayerFrameProviderMode pendingFrameProviderMode;

    private volatile double realPosition = 0.0;

    public PlayerSession(
            String id,
            String userId,
            PlayerSessionManager sessionManager,
            PlayerEventListener listener,
            ExecutorService preloadExecutor
    ) {
        this.id = id;
        this.userId = userId;
        this.queue = new PlayerQueue();
        this.sessionManager = sessionManager;
        this.listener = listener;
        this.preloadExecutor = preloadExecutor;

        this.frameProvider = createFrameProvider(PlayerFrameProviderMode.NORMAL);
    }

    public void setFrameProviderMode(PlayerFrameProviderMode mode) {
        synchronized (lock) {
            if (this.frameProviderMode == mode) {
                this.pendingFrameProviderMode = null;
                return;
            }

            if (this.frameProvider.isTransitioning()) {
                this.pendingFrameProviderMode = mode;
                return;
            }

            switchFrameProviderLocked(mode);
        }
    }

    public PlayerFrameProviderMode getFrameProviderMode() {
        synchronized (lock) {
            return frameProviderMode;
        }
    }

    private void applyPendingFrameProviderModeIfPossibleLocked() {
        if (pendingFrameProviderMode == null) {
            return;
        }

        if (frameProvider.isTransitioning()) {
            return;
        }

        PlayerFrameProviderMode mode = pendingFrameProviderMode;
        pendingFrameProviderMode = null;

        switchFrameProviderLocked(mode);
    }

    private void switchFrameProviderLocked(PlayerFrameProviderMode mode) {
        PlayerFrameProviderSnapshot snapshot = createSnapshotLocked();

        PlayerFrameProvider oldProvider = this.frameProvider;
        PlayerFrameProvider newProvider = createFrameProvider(mode);

        applyStateToProvider(newProvider);
        newProvider.restore(snapshot);

        this.frameProvider = newProvider;
        this.frameProviderMode = mode;

        oldProvider.destroy();
    }

    private PlayerFrameProvider createFrameProvider(PlayerFrameProviderMode mode) {
        return switch (mode) {
            case NORMAL -> new NormalFrameProvider(
                    this,
                    listener,
                    sessionManager.getAudioPlayerManager()
            );

            case GAPLESS -> new GaplessFrameProvider(
                    this,
                    sessionManager.getAudioPlayerManager(),
                    listener,
                    preloadExecutor,
                    GAPLESS_PRELOAD_BEFORE_MS,
                    GAPLESS_PREBUFFER_FRAMES,
                    GAPLESS_PRELOAD_TIMEOUT_MS
            );

            case CROSSFADE -> new CrossfadeFrameProvider(
                    this,
                    listener,
                    sessionManager.getPcmAudioPlayerManager(),
                    CROSSFADE_DURATION_MS,
                    CROSSFADE_PRELOAD_LEAD_MS
            );
        };
    }

    private PlayerFrameProviderSnapshot createSnapshotLocked() {
        QueueEntry currentEntry = frameProvider.getCurrentEntry();

        long position = currentEntry != null
                ? Math.max(0L, Math.round(realPosition))
                : 0L;

        return new PlayerFrameProviderSnapshot(
                currentEntry,
                position,
                frameProvider.getAudioPlayer().isPaused(),
                frameProvider.getAudioPlayer().getVolume(),
                playerFilters.factory()
        );
    }

    private void applyStateToProvider(PlayerFrameProvider provider) {
        provider.setVolume(frameProvider.getAudioPlayer().getVolume());
        provider.setFilterFactory(playerFilters.factory());

        if (frameProvider.getAudioPlayer().isPaused()) {
            provider.pause();
        } else {
            provider.resume();
        }
    }

    public void setPosition(double position) {
        if (!Double.isFinite(position)) {
            realPosition = 0.0;
            return;
        }

        realPosition = Math.max(0.0, position);
    }

    public void resetPosition() {
        realPosition = 0.0;
    }

    public double getPosition() {
        return realPosition;
    }

    public PlayerFilters getPlayerFilters() {
        return playerFilters;
    }

    public PlayerFrameLossCounter getFrameLossCounter() { return playerFrameLossCounter; }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public PlayerQueue getQueue() {
        return queue;
    }

    public PlayerEventListener getListener() { return listener; }

    public boolean provide(MutableAudioFrame audioFrame) {
        synchronized (lock) {
            boolean provided = this.frameProvider.provide(audioFrame);

            if (provided) {
                this.playerFrameLossCounter.onSuccess();
                realPosition += 20
                        * playerFilters.timescale().speed()
                        * playerFilters.timescale().rate();
            } else {
                this.playerFrameLossCounter.onFail();
            }

            applyPendingFrameProviderModeIfPossibleLocked();

            return provided;
        }
    }

    public PlayerFrameProvider getFrameProvider() {
        return this.frameProvider;
    }

    public void skip() {
        synchronized (lock) {
            this.frameProvider.skip();
            applyPendingFrameProviderModeIfPossibleLocked();
        }
    }

    public void previous() {
        synchronized (lock) {
            this.frameProvider.previous();
            applyPendingFrameProviderModeIfPossibleLocked();
        }
    }

    public boolean shuffleQueue() {
        synchronized (lock) {
            boolean shuffled = this.queue.shuffle();

            if (shuffled) {
                this.listener.onQueueUpdate(this);
            }

            return shuffled;
        }
    }

    public QueueEntry removeFromQueue(String entryId) {
        synchronized (lock) {
            QueueEntry removed = this.frameProvider.removeQueuedEntry(entryId);

            if (removed != null) {
                this.listener.onQueueUpdate(this);
            }

            return removed;
        }
    }

    public boolean clearQueuedEntries() {
        synchronized (lock) {
            boolean changed = this.frameProvider.clearQueuedEntries();

            if (changed) {
                this.listener.onQueueUpdate(this);
            }

            return changed;
        }
    }

    public boolean clearQueueHistory() {
        synchronized (lock) {
            boolean changed = this.frameProvider.clearQueueHistory();

            if (changed) {
                this.listener.onQueueUpdate(this);
            }

            return changed;
        }
    }

    public boolean clearQueuedEntriesAndHistory() {
        synchronized (lock) {
            boolean changed = this.frameProvider.clearQueuedEntriesAndHistory();

            if (changed) {
                this.listener.onQueueUpdate(this);
            }

            return changed;
        }
    }

    public void enqueueMany(List<AudioTrack> list, String requesterId, JsonObject extraData) {
        if (list == null || list.isEmpty()) {
            return;
        }

        synchronized (lock) {
            List<QueueEntry> entries = new ArrayList<>(list.size());

            for (AudioTrack track : list) {
                if (track == null) {
                    continue;
                }

                entries.add(new QueueEntry(
                        UUID.randomUUID().toString(),
                        track,
                        requesterId,
                        System.currentTimeMillis(),
                        extraData
                ));
            }

            if (entries.isEmpty()) {
                return;
            }

            this.frameProvider.enqueueMany(entries);
        }
    }

    public void enqueue(AudioTrack track, String requesterId, JsonObject extraData) {
        synchronized (lock) {
            QueueEntry entry = new QueueEntry(
                    UUID.randomUUID().toString(),
                    track,
                    requesterId,
                    System.currentTimeMillis(),
                    extraData
            );

            this.frameProvider.enqueue(entry);
        }
    }

    public void setFilterFactory(PcmFilterFactory factory) {
        synchronized (lock) {
            this.frameProvider.setFilterFactory(factory);
        }
    }

    public void pause() {
        synchronized (lock) {
            this.frameProvider.pause();
            this.playerFrameLossCounter.end();
        }
    }

    public void resume() {
        synchronized (lock) {
            this.frameProvider.resume();
            this.playerFrameLossCounter.start();
        }
    }

    public void setVolume(int volume) {
        synchronized (lock) {
            this.frameProvider.setVolume(Math.max(0, Math.min(1000, volume)));
        }
    }

    public AudioTrack getPlayingTrack() {
        return this.frameProvider.getPlayingTrack();
    }

    public boolean isPlaying() {
        return getPlayingTrack() != null;
    }

    public void destroy() {
        synchronized (lock) {
            this.frameProvider.stop();
            this.frameProvider.destroy();
        }
    }

    public JsonObject toJson(AudioPlayerManager audioPlayerManager) {
        return toJson(audioPlayerManager, false);
    }

    public JsonObject toJson(AudioPlayerManager audioPlayerManager, boolean withQueue) {
        synchronized (lock) {
            JsonObject json = new JsonObject();
            json
                    .put("guildId", id)
                    .put("userId", userId)
                    .put("filters", playerFilters.toJson())
                    .put("providerMode", frameProviderMode.toString())
                    .put("volume", frameProvider.getAudioPlayer().getVolume())
                    .put("paused", frameProvider.getAudioPlayer().isPaused())
                    .put("transitioning", frameProvider.isTransitioning())
                    .put("position", realPosition);

            if (withQueue)
                json.put("queue", queue.toJson(audioPlayerManager));

            AudioTrack track = frameProvider.getPlayingTrack();
            if (track != null) {
                json.put("track", RequestUtil.trackToJson(audioPlayerManager, track));
            }

            return json;
        }
    }
}