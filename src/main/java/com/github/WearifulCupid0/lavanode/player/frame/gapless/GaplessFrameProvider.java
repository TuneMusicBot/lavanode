package com.github.WearifulCupid0.lavanode.player.frame.gapless;

import com.github.WearifulCupid0.lavanode.player.PlayerEventListener;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProvider;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProviderSnapshot;
import com.github.WearifulCupid0.lavanode.player.frame.TrackEventGuard;
import com.github.WearifulCupid0.lavanode.player.queue.PlayerQueue;
import com.github.WearifulCupid0.lavanode.player.queue.PreparedTrack;
import com.github.WearifulCupid0.lavanode.player.queue.QueueEntry;
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class GaplessFrameProvider extends AudioEventAdapter implements PlayerFrameProvider {
    private static final Logger log = LoggerFactory.getLogger(GaplessFrameProvider.class);

    private final Object lock = new Object();

    private final PlayerSession session;
    private final PlayerQueue queue;
    private final PlayerEventListener listener;
    private final ExecutorService preloadExecutor;

    private final long preloadBeforeMs;
    private final long preloadLoadTimeoutMs;
    private final int prebufferFrames;

    private AudioPlayer activePlayer;
    private AudioPlayer standbyPlayer;

    private final TrackEventGuard trackEventGuard = new TrackEventGuard();

    private QueueEntry currentEntry;
    private AudioTrack activeTrack;
    private long activeTrackGeneration;
    private AudioTrack preparedAudioTrack;
    private long preparedTrackGeneration;
    private PreparedTrack preparedTrack;
    private ArrayBlockingQueue<GaplessFrameData> currentPreloadedBuffer;

    private String failedPreloadEntryId;
    private long nextPreloadRetryAt;

    private PcmFilterFactory filterFactory;
    private int volume = 100;
    private boolean paused;

    private boolean queueEndEmitted = true;
    private boolean promotingPreparedTrack;
    private boolean suppressEvents;
    private boolean closed;

    private boolean activeEnded;
    private AudioTrack activeEndedTrack;
    private AudioTrackEndReason activeEndedReason;

    public GaplessFrameProvider(
            PlayerSession session,
            AudioPlayerManager playerManager,
            PlayerEventListener listener,
            ExecutorService preloadExecutor,
            long preloadBeforeMs,
            int prebufferFrames,
            long preloadLoadTimeoutMs
    ) {
        this.session = session;
        this.queue = session.getQueue();
        this.listener = listener;
        this.preloadExecutor = preloadExecutor;
        this.preloadBeforeMs = preloadBeforeMs;
        this.prebufferFrames = prebufferFrames;
        this.preloadLoadTimeoutMs = preloadLoadTimeoutMs;

        this.activePlayer = playerManager.createPlayer();
        this.standbyPlayer = playerManager.createPlayer();

        this.activePlayer.addListener(this);
        this.standbyPlayer.addListener(this);
    }

    @Override
    public void setFilterFactory(PcmFilterFactory factory) {
        synchronized (lock) {
            this.filterFactory = factory;
            this.activePlayer.setFilterFactory(factory);
            this.standbyPlayer.setFilterFactory(factory);

            if (preparedTrack != null) {
                preparedTrack.getPlayer().setFilterFactory(factory);
            }

            discardPreparedLocked();

            if (currentPreloadedBuffer != null) {
                currentPreloadedBuffer.clear();
                currentPreloadedBuffer = null;
            }

            prepareNextIfNeededLocked();
        }
    }

    @Override
    public void enqueueMany(List<QueueEntry> list) {
        if (list == null || list.isEmpty()) {
            return;
        }

        synchronized (lock) {
            if (closed) {
                return;
            }

            queue.addAll(list);

            queueEndEmitted = false;

            listener.onQueueUpdate(session);

            if (currentEntry == null && activePlayer.getPlayingTrack() == null && !activeEnded) {
                startNextLocked();
                return;
            }

            prepareNextIfNeededLocked();
        }
    }

    @Override
    public void enqueue(QueueEntry entry) {
        synchronized (lock) {
            if (closed) {
                return;
            }

            queue.add(entry);
            queueEndEmitted = false;

            listener.onQueueUpdate(session);

            if (currentEntry == null && activePlayer.getPlayingTrack() == null && !activeEnded) {
                startNextLocked();
                return;
            }

            prepareNextIfNeededLocked();
        }
    }

    @Override
    public boolean provide(MutableAudioFrame targetFrame) {
        synchronized (lock) {
            if (closed) {
                return false;
            }

            GaplessFrameData preloadedFrame = pollPreloadedFrameLocked();

            if (preloadedFrame != null) {
                targetFrame.store(preloadedFrame.data(), 0, preloadedFrame.length());
                return true;
            }

            boolean provided = activePlayer.provide(targetFrame);

            if (provided) {
                return true;
            }

            if (activePlayer.getPlayingTrack() != null && !activeEnded) {
                return false;
            }

            if (currentEntry == null && !activeEnded) {
                return false;
            }

            AudioTrackEndReason endReason = activeEndedReason;

            if (currentEntry != null) {
                emitActiveEndLocked();
            }

            if (endReason != null && !endReason.mayStartNext) {
                enterIdleLocked(true);
                return false;
            }

            advanceLocked();

            preloadedFrame = pollPreloadedFrameLocked();

            if (preloadedFrame != null) {
                targetFrame.store(preloadedFrame.data(), 0, preloadedFrame.length());
                return true;
            }

            return activePlayer.provide(targetFrame);
        }
    }

    private void enterIdleLocked(boolean emitQueueEnd) {
        currentEntry = null;
        clearActiveTrackLocked();
        currentPreloadedBuffer = null;

        discardPreparedLocked();
        clearActiveEndLocked();
        session.resetPosition();

        if (emitQueueEnd && !queueEndEmitted) {
            queueEndEmitted = true;

            listener.onQueueUpdate(session);
            listener.onQueueEnd(session);
        }
    }

    public void tick() {
        synchronized (lock) {
            if (closed) {
                return;
            }

            prepareNextIfNeededLocked();
        }
    }

    @Override
    public void skip() {
        synchronized (lock) {
            if (closed) {
                return;
            }

            if (currentEntry == null && activePlayer.getPlayingTrack() == null && !activeEnded) {
                return;
            }

            QueueEntry skippedEntry = currentEntry;
            AudioTrack skippedTrack = activeTrack != null ? activeTrack : activePlayer.getPlayingTrack();

            if (skippedEntry != null) {
                queue.addToHistory(skippedEntry.copyWithPosition(0));
            }

            stopActiveSilentlyLocked();

            if (skippedEntry != null) {
                listener.onTrackEnd(
                        session,
                        skippedTrack != null ? skippedTrack : skippedEntry.getTrack(),
                        AudioTrackEndReason.STOPPED
                );
            }

            currentEntry = null;
            currentPreloadedBuffer = null;
            clearActiveEndLocked();

            advanceLocked();
        }
    }

    @Override
    public void previous() {
        synchronized (lock) {
            if (closed) {
                return;
            }

            QueueEntry previous = queue.pollPrevious();

            if (previous == null) {
                return;
            }

            discardPreparedLocked();

            QueueEntry replacedEntry = currentEntry;
            AudioTrack replacedTrack = activeTrack != null ? activeTrack : activePlayer.getPlayingTrack();

            if (replacedEntry != null) {
                queue.addFirst(replacedEntry.copyWithPosition(0));
                listener.onTrackEnd(
                        session,
                        replacedTrack != null ? replacedTrack : replacedEntry.getTrack(),
                        AudioTrackEndReason.STOPPED
                );
            }

            currentEntry = null;
            currentPreloadedBuffer = null;
            clearActiveEndLocked();
            stopActiveSilentlyLocked();

            startSpecificEntryLocked(previous.copyWithPosition(0));
        }
    }


    @Override
    public QueueEntry removeQueuedEntry(String entryId) {
        synchronized (lock) {
            if (closed) {
                return null;
            }

            QueueEntry removed = queue.removeById(entryId);

            if (removed != null && preparedTrack != null && removed.getId().equals(preparedTrack.getEntry().getId())) {
                discardPreparedLocked();
            }

            return removed;
        }
    }

    @Override
    public boolean clearQueuedEntries() {
        synchronized (lock) {
            if (closed) {
                return false;
            }

            boolean changed = queue.clearQueueOnly();

            if (preparedTrack != null) {
                discardPreparedLocked();
                changed = true;
            }

            failedPreloadEntryId = null;
            nextPreloadRetryAt = 0L;

            return changed;
        }
    }

    @Override
    public boolean clearQueueHistory() {
        synchronized (lock) {
            return queue.clearHistoryOnly();
        }
    }

    @Override
    public boolean clearQueuedEntriesAndHistory() {
        synchronized (lock) {
            if (closed) {
                return false;
            }

            boolean changed = queue.clearQueueAndHistory();

            if (preparedTrack != null) {
                discardPreparedLocked();
                changed = true;
            }

            failedPreloadEntryId = null;
            nextPreloadRetryAt = 0L;

            return changed;
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            queue.clear();

            stopActiveSilentlyLocked();
            stopStandbySilentlyLocked();

            currentEntry = null;
            currentPreloadedBuffer = null;
            queueEndEmitted = true;

            discardPreparedLocked();
            clearActiveEndLocked();
            session.resetPosition();

            listener.onQueueUpdate(session);
            listener.onQueueEnd(session);
        }
    }

    @Override
    public void pause() {
        synchronized (lock) {
            paused = true;
            activePlayer.setPaused(true);
            standbyPlayer.setPaused(true);

            if (preparedTrack != null) {
                preparedTrack.getPlayer().setPaused(true);
            }
        }
    }

    @Override
    public void resume() {
        synchronized (lock) {
            paused = false;
            activePlayer.setPaused(false);
            standbyPlayer.setPaused(false);

            if (preparedTrack != null) {
                preparedTrack.getPlayer().setPaused(false);
            }
        }
    }

    @Override
    public void setVolume(int volume) {
        synchronized (lock) {
            this.volume = Math.max(0, Math.min(1000, volume));
            activePlayer.setVolume(this.volume);
            standbyPlayer.setVolume(this.volume);

            if (preparedTrack != null) {
                preparedTrack.getPlayer().setVolume(this.volume);
            }
        }
    }

    @Override
    public AudioTrack getPlayingTrack() {
        synchronized (lock) {
            return activePlayer.getPlayingTrack();
        }
    }

    @Override
    public QueueEntry getCurrentEntry() {
        synchronized (lock) {
            return currentEntry;
        }
    }

    @Override
    public AudioPlayer getAudioPlayer() {
        synchronized (lock) {
            return activePlayer;
        }
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            closed = true;

            discardPreparedLocked();

            clearActiveTrackLocked();
            clearPreparedAudioTrackLocked();

            activePlayer.destroy();
            standbyPlayer.destroy();

            currentEntry = null;
            currentPreloadedBuffer = null;
        }
    }

    private void startNextLocked() {
        QueueEntry next = queue.poll();

        if (next == null) {
            enterIdleLocked(true);
            return;
        }

        if (!startSpecificEntryLocked(next)) {
            startNextLocked();
        }
    }

    private boolean startSpecificEntryLocked(QueueEntry entry) {
        if (entry == null) {
            return false;
        }

        stopActiveSilentlyLocked();

        currentEntry = entry;
        currentPreloadedBuffer = null;
        queueEndEmitted = false;

        clearActiveEndLocked();

        AudioTrack track = entry.getTrack().makeClone();
        track.setPosition(entry.getTrack().getPosition());

        activePlayer.setFilterFactory(filterFactory);
        activePlayer.setVolume(volume);
        activePlayer.setPaused(paused);

        bindActiveTrackLocked(track);

        boolean started = activePlayer.startTrack(track, false);

        if (!started) {
            if (activeTrack == track) {
                clearActiveTrackLocked();
            }
            currentEntry = null;
            session.resetPosition();
            return false;
        }

        activePlayer.setPaused(paused);
        session.setPosition(track.getPosition());

        listener.onTrackStart(session, currentEntry);
        listener.onQueueUpdate(session);

        prepareNextIfNeededLocked();
        return true;
    }

    @Override
    public boolean isTransitioning() {
        synchronized (lock) {
            return activeEnded || currentPreloadedBuffer != null || promotingPreparedTrack;
        }
    }

    @Override
    public void restore(PlayerFrameProviderSnapshot snapshot) {
        synchronized (lock) {
            discardPreparedLocked();

            this.currentEntry = snapshot.currentEntry();
            this.currentPreloadedBuffer = null;
            this.filterFactory = snapshot.filterFactory();
            this.volume = Math.max(0, Math.min(1000, snapshot.volume()));
            this.paused = snapshot.paused();

            stopActiveSilentlyLocked();
            stopStandbySilentlyLocked();

            activePlayer.setFilterFactory(filterFactory);
            standbyPlayer.setFilterFactory(filterFactory);

            activePlayer.setVolume(volume);
            standbyPlayer.setVolume(volume);

            activePlayer.setPaused(paused);
            standbyPlayer.setPaused(paused);

            if (currentEntry == null) {
                session.resetPosition();
                return;
            }

            AudioTrack track = currentEntry.getTrack().makeClone();
            track.setPosition(snapshot.position());

            clearActiveEndLocked();

            bindActiveTrackLocked(track);

            boolean started = activePlayer.startTrack(track, false);

            if (!started) {
                if (activeTrack == track) {
                    clearActiveTrackLocked();
                }
                currentEntry = null;
                session.resetPosition();
                return;
            }

            activePlayer.setPaused(paused);
            session.setPosition(track.getPosition());

            prepareNextIfNeededLocked();
        }
    }

    private void advanceLocked() {
        promotingPreparedTrack = true;

        try {
            if (promotePreparedLocked()) {
                return;
            }

            startNextLocked();
        } finally {
            promotingPreparedTrack = false;
        }
    }

    private boolean promotePreparedLocked() {
        if (preparedTrack == null) {
            return false;
        }

        QueueEntry nextInQueue = queue.peek();

        if (nextInQueue == null || !nextInQueue.getId().equals(preparedTrack.getEntry().getId())) {
            discardPreparedLocked();
            return false;
        }

        QueueEntry next = queue.poll();

        PreparedTrack prepared = preparedTrack;
        preparedTrack = null;

        prepared.stopPump();

        AudioPlayer oldActive = activePlayer;

        activePlayer = prepared.getPlayer();
        standbyPlayer = oldActive;
        promotePreparedAudioTrackToActiveLocked(activePlayer.getPlayingTrack());

        stopStandbySilentlyLocked();

        activePlayer.setFilterFactory(filterFactory);
        activePlayer.setVolume(volume);
        activePlayer.setPaused(paused);

        currentEntry = next;
        currentPreloadedBuffer = prepared.getBuffer();
        queueEndEmitted = false;

        clearActiveEndLocked();

        if (prepared.isFinished()) {
            activeEnded = true;
            activeEndedTrack = next.getTrack();
            activeEndedReason = AudioTrackEndReason.FINISHED;
        }

        listener.onTrackStart(session, next);
        session.setPosition(next.getTrack().getPosition());
        listener.onQueueUpdate(session);

        prepareNextIfNeededLocked();

        return true;
    }

    private void prepareNextIfNeededLocked() {
        if (preparedTrack != null || paused) {
            return;
        }

        QueueEntry next = queue.peek();

        if (next == null) {
            return;
        }

        long now = System.currentTimeMillis();

        if (next.getId().equals(failedPreloadEntryId) && now < nextPreloadRetryAt) {
            return;
        }

        AudioTrack currentTrack = activePlayer.getPlayingTrack();

        if (currentTrack == null) {
            return;
        }

        long duration = currentTrack.getDuration();

        if (duration <= 0 || currentTrack.getInfo().isStream) {
            return;
        }

        long remaining = duration - Math.max(0L, Math.round(session.getPosition()));

        if (remaining > preloadBeforeMs) {
            return;
        }

        startPreloadLocked(next);
    }

    private void startPreloadLocked(QueueEntry next) {
        stopStandbySilentlyLocked();

        failedPreloadEntryId = null;
        nextPreloadRetryAt = 0L;

        PreparedTrack prepared = new PreparedTrack(
                next,
                standbyPlayer,
                prebufferFrames
        );

        standbyPlayer.setFilterFactory(filterFactory);
        standbyPlayer.setVolume(volume);
        standbyPlayer.setPaused(false);

        AudioTrack nextTrack = next.getTrack().makeClone();
        nextTrack.setPosition(next.getTrack().getPosition());
        bindPreparedAudioTrackLocked(nextTrack);

        boolean started = standbyPlayer.startTrack(nextTrack, false);

        if (!started) {
            if (preparedAudioTrack == nextTrack) {
                clearPreparedAudioTrackLocked();
            }
            failedPreloadEntryId = next.getId();
            nextPreloadRetryAt = System.currentTimeMillis() + 5_000;
            return;
        }

        preparedTrack = prepared;

        try {
            prepared.setFuture(preloadExecutor.submit(() -> pumpPreparedTrack(prepared)));
        } catch (RejectedExecutionException exception) {
            failedPreloadEntryId = next.getId();
            nextPreloadRetryAt = System.currentTimeMillis() + 2_000;
            discardPreparedLocked();

            log.debug(
                    "Preload rejected for track {}. Falling back to normal playback.",
                    next.getTrack().getInfo().uri,
                    exception
            );
        }
    }

    private void pumpPreparedTrack(PreparedTrack prepared) {
        ByteBuffer preloadBuffer = ByteBuffer.allocate(
                StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
        );

        MutableAudioFrame preloadFrame = new MutableAudioFrame();
        preloadFrame.setBuffer(preloadBuffer);

        long startedAt = System.currentTimeMillis();

        long effectiveTimeoutMs = Math.min(
                preloadLoadTimeoutMs,
                Math.max(1_000, preloadBeforeMs - 500)
        );

        try {
            while (prepared.isRunning()) {
                long elapsed = System.currentTimeMillis() - startedAt;

                if (elapsed >= effectiveTimeoutMs && prepared.getBuffer().isEmpty()) {
                    synchronized (lock) {
                        if (preparedTrack == prepared) {
                            failedPreloadEntryId = prepared.getEntry().getId();
                            nextPreloadRetryAt = System.currentTimeMillis() + 2_000;
                            discardPreparedLocked();
                        }
                    }

                    log.debug(
                            "Preload timed out after {}ms for track {}. Falling back to normal playback.",
                            elapsed,
                            prepared.getEntry().getTrack().getInfo().uri
                    );

                    return;
                }

                preloadBuffer.clear();

                boolean provided;

                try {
                    provided = prepared.getPlayer().provide(
                            preloadFrame,
                            500,
                            TimeUnit.MILLISECONDS
                    );
                } catch (TimeoutException timeout) {
                    if (prepared.getPlayer().getPlayingTrack() == null) {
                        prepared.markFinished();
                        return;
                    }

                    continue;
                }

                if (!provided) {
                    if (prepared.getPlayer().getPlayingTrack() == null) {
                        prepared.markFinished();
                        return;
                    }

                    continue;
                }

                if (preloadFrame.isTerminator()) {
                    prepared.markFinished();
                    return;
                }

                byte[] data = preloadFrame.getData();

                if (data == null || data.length == 0) {
                    continue;
                }

                GaplessFrameData frameData = new GaplessFrameData(Arrays.copyOf(data, data.length));

                while (prepared.isRunning()) {
                    if (prepared.getBuffer().offer(frameData, 100, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Throwable throwable) {
            synchronized (lock) {
                if (preparedTrack == prepared) {
                    failedPreloadEntryId = prepared.getEntry().getId();
                    nextPreloadRetryAt = System.currentTimeMillis() + 2_000;
                    discardPreparedLocked();
                }
            }

            log.debug(
                    "Failed to preload next track {}. Falling back to normal playback.",
                    prepared.getEntry().getTrack().getInfo().uri,
                    throwable
            );
        }
    }

    private GaplessFrameData pollPreloadedFrameLocked() {
        if (currentPreloadedBuffer == null) {
            return null;
        }

        GaplessFrameData frame = currentPreloadedBuffer.poll();

        if (frame != null) {
            return frame;
        }

        if (currentPreloadedBuffer.isEmpty()) {
            currentPreloadedBuffer = null;
        }

        return null;
    }

    private void discardPreparedLocked() {
        PreparedTrack prepared = preparedTrack;
        preparedTrack = null;
        clearPreparedAudioTrackLocked();

        if (prepared == null) {
            return;
        }

        suppressEvents = true;
        try {
            prepared.discard();
        } finally {
            suppressEvents = false;
        }
    }

    private void stopActiveSilentlyLocked() {
        clearActiveTrackLocked();
        suppressEvents = true;
        try {
            activePlayer.stopTrack();
        } finally {
            suppressEvents = false;
        }
    }

    private void stopStandbySilentlyLocked() {
        suppressEvents = true;
        try {
            standbyPlayer.stopTrack();
        } finally {
            suppressEvents = false;
        }
    }

    private void emitActiveEndLocked() {
        AudioTrackEndReason reason = activeEndedReason != null
                ? activeEndedReason
                : AudioTrackEndReason.FINISHED;

        AudioTrack track = activeEndedTrack;

        if (currentEntry != null) {
            listener.onTrackEnd(session, track != null ? track : currentEntry.getTrack(), reason);

            if (reason == AudioTrackEndReason.FINISHED) {
                queue.addToHistory(currentEntry.copyWithPosition(0));
            }
        }

        currentEntry = null;
        clearActiveTrackLocked();
        session.resetPosition();
        clearActiveEndLocked();
    }

    private void bindActiveTrackLocked(AudioTrack track) {
        trackEventGuard.unbind(activeTrack);
        activeTrack = track;
        activeTrackGeneration = trackEventGuard.bind(track);
    }

    private void clearActiveTrackLocked() {
        activeTrackGeneration = trackEventGuard.invalidate(activeTrack);
        activeTrack = null;
    }

    private void bindPreparedAudioTrackLocked(AudioTrack track) {
        trackEventGuard.unbind(preparedAudioTrack);
        preparedAudioTrack = track;
        preparedTrackGeneration = trackEventGuard.bind(track);
    }

    private void clearPreparedAudioTrackLocked() {
        preparedTrackGeneration = trackEventGuard.invalidate(preparedAudioTrack);
        preparedAudioTrack = null;
    }

    private void promotePreparedAudioTrackToActiveLocked(AudioTrack fallbackTrack) {
        if (preparedAudioTrack != null) {
            activeTrack = preparedAudioTrack;
            activeTrackGeneration = preparedTrackGeneration;
            preparedAudioTrack = null;
            preparedTrackGeneration = trackEventGuard.invalidate(null);
            return;
        }

        if (fallbackTrack != null) {
            bindActiveTrackLocked(fallbackTrack);
        } else {
            clearActiveTrackLocked();
        }
    }

    private boolean isActiveEventLocked(AudioPlayer player, AudioTrack track) {
        return player == activePlayer
                && trackEventGuard.accepts(activeTrack, activeTrackGeneration, track);
    }

    private boolean isPreparedEventLocked(AudioPlayer player, AudioTrack track) {
        return preparedTrack != null
                && player == preparedTrack.getPlayer()
                && trackEventGuard.accepts(preparedAudioTrack, preparedTrackGeneration, track);
    }

    private void clearActiveEndLocked() {
        activeEnded = false;
        activeEndedTrack = null;
        activeEndedReason = null;
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        synchronized (lock) {
            if (closed || suppressEvents) {
                return;
            }

            if (player == activePlayer) {
                if (!isActiveEventLocked(player, track)) {
                    return;
                }

                activeEnded = true;
                activeEndedTrack = track;
                activeEndedReason = endReason;
                return;
            }

            if (preparedTrack != null && player == preparedTrack.getPlayer()) {
                if (!isPreparedEventLocked(player, track)) {
                    return;
                }

                preparedTrack.markFinished();
            }
        }
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        synchronized (lock) {
            if (closed || suppressEvents) {
                return;
            }

            if (player == activePlayer) {
                if (!isActiveEventLocked(player, track)) {
                    return;
                }

                listener.onTrackException(session, track, exception);

                activeEnded = true;
                activeEndedTrack = track;
                activeEndedReason = AudioTrackEndReason.LOAD_FAILED;
                return;
            }

            if (preparedTrack != null && player == preparedTrack.getPlayer()) {
                if (!isPreparedEventLocked(player, track)) {
                    return;
                }

                failedPreloadEntryId = preparedTrack.getEntry().getId();
                nextPreloadRetryAt = System.currentTimeMillis() + 2_000;
                discardPreparedLocked();
            }
        }
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        synchronized (lock) {
            if (closed || suppressEvents) {
                return;
            }

            if (player == activePlayer) {
                if (!isActiveEventLocked(player, track)) {
                    return;
                }

                listener.onTrackStuck(session, track, thresholdMs);

                activeEnded = true;
                activeEndedTrack = track;
                activeEndedReason = AudioTrackEndReason.LOAD_FAILED;
                return;
            }

            if (preparedTrack != null && player == preparedTrack.getPlayer()) {
                if (!isPreparedEventLocked(player, track)) {
                    return;
                }

                log.debug(
                        "Preloaded track stuck after {}ms: {}",
                        thresholdMs,
                        track.getInfo().uri
                );

                failedPreloadEntryId = preparedTrack.getEntry().getId();
                nextPreloadRetryAt = System.currentTimeMillis() + 2_000;

                discardPreparedLocked();
            }
        }
    }
}
