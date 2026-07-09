package com.github.WearifulCupid0.lavanode.player.frame.crossfade;

import com.github.WearifulCupid0.lavanode.player.PlayerEventListener;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProvider;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProviderSnapshot;
import com.github.WearifulCupid0.lavanode.player.frame.TrackEventGuard;
import com.github.WearifulCupid0.lavanode.player.queue.PlayerQueue;
import com.github.WearifulCupid0.lavanode.player.queue.QueueEntry;
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class CrossfadeFrameProvider extends AudioEventAdapter implements PlayerFrameProvider {
    private static final Logger log = LoggerFactory.getLogger(CrossfadeFrameProvider.class);

    private final Object lock = new Object();

    private final PlayerSession session;
    private final PlayerQueue queue;
    private final PlayerEventListener listener;
    private final CrossfadeConfig config;

    private AudioPlayer activePlayer;
    private AudioPlayer incomingPlayer;

    private final PcmFrameReader activeReader = new PcmFrameReader();
    private final PcmFrameReader incomingReader = new PcmFrameReader();
    private final PcmToOpusEncoder opusEncoder;
    private final PcmCrossfadeMixer mixer = new PcmCrossfadeMixer();

    private final PcmFrameBuffer incomingBuffer;
    private final PcmFrameBuffer activeBufferedFrames = new PcmFrameBuffer(Integer.MAX_VALUE);

    private final short[] activeSamples = new short[CrossfadeAudio.SAMPLE_COUNT];
    private final short[] incomingSamples = new short[CrossfadeAudio.SAMPLE_COUNT];
    private final short[] mixedSamples = new short[CrossfadeAudio.SAMPLE_COUNT];

    private final TrackEventGuard trackEventGuard = new TrackEventGuard();

    private QueueEntry currentEntry;
    private AudioTrack activeTrack;
    private long activeTrackGeneration;
    private AudioTrack incomingTrack;
    private long incomingTrackGeneration;
    private QueueEntry incomingEntry;
    private QueueEntry outgoingEntry;

    private AudioTrack outgoingTrack;
    private AudioTrack incomingFinishedTrack;
    private AudioTrackEndReason incomingFinishedReason;
    private AudioTrack promotedFinishedTrack;
    private AudioTrackEndReason promotedFinishedReason;

    private PcmFilterFactory filterFactory;
    private int volume = 100;
    private boolean paused;

    private boolean closed;
    private boolean suppressEvents;
    private boolean queueEndEmitted = true;

    private boolean incomingStarted;
    private boolean incomingFinished;

    private boolean crossfading;
    private int crossfadeFrameIndex;

    /*
     * Posição audível da música que está entrando.
     * Não use incomingPlayer.getPlayingTrack().getPosition() para isso, pois o
     * incomingPlayer pode estar adiantado por causa do preload.
     */
    private double incomingAudiblePosition;

    public CrossfadeFrameProvider(
            PlayerSession session,
            PlayerEventListener listener,
            AudioPlayerManager pcmPlayerManager,
            long crossfadeMs,
            long preloadLeadMs
    ) {
        this.session = session;
        this.queue = session.getQueue();
        this.listener = listener;
        this.config = new CrossfadeConfig(crossfadeMs, preloadLeadMs);
        this.incomingBuffer = new PcmFrameBuffer(config.incomingBufferLimitFrames());

        this.activePlayer = pcmPlayerManager.createPlayer();
        this.incomingPlayer = pcmPlayerManager.createPlayer();

        this.activePlayer.addListener(this);
        this.incomingPlayer.addListener(this);

        this.opusEncoder = new PcmToOpusEncoder(pcmPlayerManager.getConfiguration());
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

            if (currentEntry == null && activePlayer.getPlayingTrack() == null) {
                startNextLocked(false);
            }

            listener.onQueueUpdate(session);
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

            if (currentEntry == null && activePlayer.getPlayingTrack() == null) {
                startNextLocked(false);
            }

            listener.onQueueUpdate(session);
        }
    }

    public void tick() {
        synchronized (lock) {
            if (closed || paused) {
                return;
            }

            tickLocked();
        }
    }

    private void tickLocked() {
        maybeStartIncomingPreloadLocked();

        if (!crossfading) {
            pumpIncomingBufferLocked(config.pumpFramesPerTick());
        }

        maybeStartCrossfadeLocked();
    }

    @Override
    public boolean provide(MutableAudioFrame targetFrame) {
        synchronized (lock) {
            if (closed || paused) {
                return false;
            }

            tickLocked();

            if (crossfading) {
                return provideCrossfadeLocked(targetFrame);
            }

            return provideActiveAsOpusLocked(targetFrame);
        }
    }

    private boolean provideActiveAsOpusLocked(MutableAudioFrame targetFrame) {
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean activeProvided = takeActivePcmFrameLocked(activeSamples);

            if (activeProvided) {
                return opusEncoder.encode(activeSamples, targetFrame);
            }

            if (promotedFinishedReason != null && currentEntry != null) {
                emitPromotedFinishedLocked();
                continue;
            }

            return false;
        }

        return false;
    }

    private boolean provideCrossfadeLocked(MutableAudioFrame targetFrame) {
        boolean activeProvided = takeActivePcmFrameLocked(activeSamples);
        boolean incomingProvided = takeIncomingPcmFrameLocked(incomingSamples);

        if (!activeProvided && !incomingProvided) {
            finishCrossfadeLocked(AudioTrackEndReason.FINISHED);
            return false;
        }

        mixer.mix(
                activeSamples,
                activeProvided,
                incomingSamples,
                incomingProvided,
                mixedSamples,
                activeProvided ? crossfadeFrameIndex : config.crossfadeFrameCount(),
                config.crossfadeFrameCount()
        );

        boolean encoded = opusEncoder.encode(mixedSamples, targetFrame);

        if (!encoded) {
            return false;
        }

        if (incomingProvided) {
            incomingAudiblePosition += getPositionIncrementMs();
        }

        crossfadeFrameIndex++;

        if (!activeProvided || crossfadeFrameIndex >= config.crossfadeFrameCount()) {
            finishCrossfadeLocked(AudioTrackEndReason.FINISHED);
        }

        return true;
    }

    private boolean takeActivePcmFrameLocked(short[] target) {
        if (activeBufferedFrames.pollInto(target)) {
            return true;
        }

        return activeReader.read(activePlayer, target);
    }

    private boolean takeIncomingPcmFrameLocked(short[] target) {
        if (incomingBuffer.pollInto(target)) {
            return true;
        }

        if (!incomingStarted || incomingFinished) {
            PcmFrameReader.fillSilence(target);
            return false;
        }

        return incomingReader.read(incomingPlayer, target);
    }

    private void maybeStartIncomingPreloadLocked() {
        if (incomingStarted || crossfading || currentEntry == null || queue.peek() == null) {
            return;
        }

        AudioTrack playingTrack = activeTrack != null ? activeTrack : activePlayer.getPlayingTrack();

        if (playingTrack == null || playingTrack.getInfo().isStream) {
            return;
        }

        long duration = playingTrack.getDuration();

        if (duration <= 0) {
            return;
        }

        long position = Math.max(0L, Math.round(session.getPosition()));
        long remaining = duration - position;

        if (remaining > config.preloadStartRemainingMs()) {
            return;
        }

        startIncomingPreloadLocked();
    }

    private boolean startIncomingPreloadLocked() {
        if (incomingStarted) {
            return true;
        }

        QueueEntry next = queue.poll();

        if (next == null) {
            return false;
        }

        incomingEntry = next;
        incomingStarted = true;
        incomingFinished = false;
        incomingFinishedTrack = null;
        incomingFinishedReason = null;
        incomingAudiblePosition = 0.0;
        incomingBuffer.clear();

        AudioTrack nextTrack = next.getTrack().makeClone();
        nextTrack.setPosition(next.getTrack().getPosition());
        bindIncomingTrackLocked(nextTrack);
        incomingAudiblePosition = nextTrack.getPosition();

        incomingPlayer.setFilterFactory(filterFactory);
        incomingPlayer.setVolume(volume);
        incomingPlayer.setPaused(false);

        boolean started = incomingPlayer.startTrack(nextTrack, false);

        if (!started) {
            if (incomingTrack == nextTrack) {
                clearIncomingTrackLocked();
            }
            clearIncomingStateLocked(false);
            queue.addFirst(next);
            return false;
        }

        listener.onQueueUpdate(session);
        return true;
    }

    private void pumpIncomingBufferLocked(int maxFrames) {
        if (!incomingStarted || incomingFinished || incomingBuffer.isFull()) {
            return;
        }

        int frames = 0;

        while (frames < maxFrames && !incomingBuffer.isFull()) {
            boolean provided = incomingReader.read(incomingPlayer, incomingSamples);

            if (!provided) {
                return;
            }

            incomingBuffer.offerCopy(incomingSamples);
            frames++;
        }
    }

    private void maybeStartCrossfadeLocked() {
        if (crossfading || !incomingStarted || incomingEntry == null) {
            return;
        }

        AudioTrack playingTrack = activeTrack != null ? activeTrack : activePlayer.getPlayingTrack();

        if (playingTrack == null || playingTrack.getInfo().isStream) {
            return;
        }

        long duration = playingTrack.getDuration();

        if (duration <= 0) {
            return;
        }

        long position = Math.max(0L, Math.round(session.getPosition()));
        long remaining = duration - position;

        if (remaining > config.crossfadeMs()) {
            return;
        }

        if (incomingBuffer.isEmpty() && !incomingFinished) {
            return;
        }

        if (incomingBuffer.size() < config.minStartBufferFrames()
                && !incomingFinished
                && remaining > CrossfadeAudio.FRAME_MS * 3L) {
            return;
        }

        outgoingEntry = currentEntry;
        outgoingTrack = playingTrack;

        crossfading = true;
        crossfadeFrameIndex = 0;

        listener.onQueueUpdate(session);
    }

    private void finishCrossfadeLocked(AudioTrackEndReason oldTrackEndReason) {
        if (!incomingStarted || incomingEntry == null) {
            crossfading = false;
            crossfadeFrameIndex = 0;
            return;
        }

        QueueEntry oldEntry = outgoingEntry != null ? outgoingEntry : currentEntry;

        if (oldEntry != null && oldTrackEndReason == AudioTrackEndReason.FINISHED) {
            queue.addToHistory(oldEntry.copyWithPosition(0));
        }

        AudioTrack oldTrack = outgoingTrack != null ? outgoingTrack : activeTrack;

        suppressEvents = true;
        activePlayer.stopTrack();
        suppressEvents = false;

        AudioPlayer oldActive = activePlayer;
        activePlayer = incomingPlayer;
        incomingPlayer = oldActive;

        promoteIncomingTrackToActiveLocked();

        currentEntry = incomingEntry;

        activeBufferedFrames.clear();
        incomingBuffer.moveRemainingTo(activeBufferedFrames);

        promotedFinishedTrack = incomingFinishedTrack;
        promotedFinishedReason = incomingFinished
                ? (incomingFinishedReason != null ? incomingFinishedReason : AudioTrackEndReason.FINISHED)
                : null;

        activePlayer.setPaused(paused);
        activePlayer.setVolume(volume);
        activePlayer.setFilterFactory(filterFactory);

        QueueEntry startedEntry = currentEntry;
        double audiblePosition = incomingAudiblePosition;

        clearIncomingStateLocked(false);

        crossfading = false;
        crossfadeFrameIndex = 0;
        outgoingEntry = null;
        outgoingTrack = null;

        if (oldEntry != null && oldTrack != null)
            listener.onTrackEnd(session, oldTrack, oldTrackEndReason);

        if (startedEntry != null)
            listener.onTrackStart(session, startedEntry);

        /*
         * O listener padrão reseta a posição em onTrackEnd/onTrackStart.
         * Por isso a posição audível da música promovida precisa ser escrita depois
         * dos eventos; caso contrário ela volta para 0 após a transição.
         */
        session.setPosition(audiblePosition);

        listener.onQueueUpdate(session);
    }

    private boolean startNextLocked(boolean stopIfEmpty) {
        cancelIncomingPreloadLocked(true);
        promotedFinishedTrack = null;
        promotedFinishedReason = null;
        activeBufferedFrames.clear();

        QueueEntry next = queue.poll();

        if (next == null) {
            currentEntry = null;
            clearActiveTrackLocked();

            if (stopIfEmpty) {
                suppressEvents = true;
                activePlayer.stopTrack();
                suppressEvents = false;

                session.resetPosition();
            }

            emitQueueEndLocked();
            listener.onQueueUpdate(session);
            return false;
        }

        currentEntry = next;
        queueEndEmitted = false;

        AudioTrack track = next.getTrack().makeClone();
        track.setPosition(next.getTrack().getPosition());

        activePlayer.setFilterFactory(filterFactory);
        activePlayer.setVolume(volume);
        activePlayer.setPaused(paused);

        suppressEvents = true;
        activePlayer.stopTrack();
        suppressEvents = false;

        bindActiveTrackLocked(track);

        boolean started = activePlayer.startTrack(track, false);

        if (!started) {
            if (activeTrack == track) {
                clearActiveTrackLocked();
            }
            currentEntry = null;
            return startNextLocked(stopIfEmpty);
        }

        session.setPosition(track.getPosition());

        listener.onTrackStart(session, currentEntry);
        listener.onQueueUpdate(session);

        return true;
    }

    private void cancelIncomingPreloadLocked(boolean requeue) {
        if (requeue && incomingEntry != null) {
            queue.addFirst(incomingEntry);
        }

        if (incomingStarted || incomingPlayer.getPlayingTrack() != null) {
            suppressEvents = true;
            incomingPlayer.stopTrack();
            suppressEvents = false;
        }

        clearIncomingStateLocked(true);
    }

    private void clearIncomingStateLocked(boolean clearBuffer) {
        incomingStarted = false;
        incomingFinished = false;
        clearIncomingTrackLocked();
        incomingEntry = null;
        incomingFinishedTrack = null;
        incomingFinishedReason = null;
        incomingAudiblePosition = 0.0;

        if (clearBuffer) {
            incomingBuffer.clear();
        }
    }

    private void startSpecificEntryLocked(QueueEntry entry) {
        currentEntry = entry;
        queueEndEmitted = false;

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
            return;
        }

        session.setPosition(track.getPosition());

        listener.onTrackStart(session, currentEntry);
        listener.onQueueUpdate(session);
    }

    @Override
    public void skip() {
        synchronized (lock) {
            if (closed) {
                return;
            }

            if (crossfading) {
                skipDuringCrossfadeLocked();
                return;
            }

            if (incomingStarted && incomingEntry != null) {
                skipToPreloadedIncomingLocked();
                return;
            }

            QueueEntry skippedEntry = currentEntry;
            AudioTrack skippedTrack = activeTrack != null ? activeTrack : activePlayer.getPlayingTrack();

            if (skippedEntry != null) {
                queue.addToHistory(skippedEntry.copyWithPosition(0));
                listener.onTrackEnd(
                        session,
                        skippedTrack != null ? skippedTrack : skippedEntry.getTrack(),
                        AudioTrackEndReason.STOPPED
                );
            }

            currentEntry = null;
            startNextLocked(true);
        }
    }

    @Override
    public void previous() {
        synchronized (lock) {
            if (closed) {
                return;
            }

            if (crossfading && currentEntry != null) {
                restartCurrentDuringCrossfadeLocked();
                return;
            }

            QueueEntry previous = queue.pollPrevious();

            if (previous == null) {
                return;
            }

            cancelIncomingPreloadLocked(true);

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

            resetCrossfadeStateLocked();

            activeBufferedFrames.clear();
            promotedFinishedTrack = null;
            promotedFinishedReason = null;

            clearActiveTrackLocked();
            clearIncomingTrackLocked();

            suppressEvents = true;
            activePlayer.stopTrack();
            incomingPlayer.stopTrack();
            suppressEvents = false;

            currentEntry = null;

            startSpecificEntryLocked(previous.copyWithPosition(0));
        }
    }

    private void skipDuringCrossfadeLocked() {
        QueueEntry skippedEntry = currentEntry;
        AudioTrack skippedTrack = outgoingTrack != null
                ? outgoingTrack
                : (activeTrack != null ? activeTrack : activePlayer.getPlayingTrack());

        if (skippedEntry != null) {
            queue.addToHistory(skippedEntry.copyWithPosition(0));
            listener.onTrackEnd(
                    session,
                    skippedTrack != null ? skippedTrack : skippedEntry.getTrack(),
                    AudioTrackEndReason.STOPPED
            );
        }

        cancelIncomingPreloadLocked(false);
        resetCrossfadeStateLocked();
        activeBufferedFrames.clear();
        promotedFinishedTrack = null;
        promotedFinishedReason = null;

        clearActiveTrackLocked();
        suppressEvents = true;
        try {
            activePlayer.stopTrack();
        } finally {
            suppressEvents = false;
        }

        currentEntry = null;
        startNextLocked(true);
    }

    private void skipToPreloadedIncomingLocked() {
        QueueEntry nextEntry = incomingEntry.copyWithPosition(0);
        QueueEntry skippedEntry = currentEntry;
        AudioTrack skippedTrack = activeTrack != null ? activeTrack : activePlayer.getPlayingTrack();

        if (skippedEntry != null) {
            queue.addToHistory(skippedEntry.copyWithPosition(0));
            listener.onTrackEnd(
                    session,
                    skippedTrack != null ? skippedTrack : skippedEntry.getTrack(),
                    AudioTrackEndReason.STOPPED
            );
        }

        cancelIncomingPreloadLocked(false);
        resetCrossfadeStateLocked();
        activeBufferedFrames.clear();
        promotedFinishedTrack = null;
        promotedFinishedReason = null;

        clearActiveTrackLocked();
        suppressEvents = true;
        try {
            activePlayer.stopTrack();
        } finally {
            suppressEvents = false;
        }

        currentEntry = null;
        startSpecificEntryLocked(nextEntry);
    }

    private void restartCurrentDuringCrossfadeLocked() {
        QueueEntry restartEntry = currentEntry.copyWithPosition(0);

        cancelIncomingPreloadLocked(true);
        resetCrossfadeStateLocked();
        activeBufferedFrames.clear();
        promotedFinishedTrack = null;
        promotedFinishedReason = null;

        clearActiveTrackLocked();
        suppressEvents = true;
        try {
            activePlayer.stopTrack();
        } finally {
            suppressEvents = false;
        }

        currentEntry = null;
        startSpecificEntryLocked(restartEntry);
    }

    private void resetCrossfadeStateLocked() {
        crossfading = false;
        crossfadeFrameIndex = 0;
        outgoingEntry = null;
        outgoingTrack = null;
    }


    @Override
    public QueueEntry removeQueuedEntry(String entryId) {
        synchronized (lock) {
            if (closed || entryId == null || entryId.isBlank()) {
                return null;
            }

            if (incomingEntry != null && entryId.equals(incomingEntry.getId())) {
                QueueEntry removed = incomingEntry;

                cancelIncomingPreloadLocked(false);
                resetCrossfadeStateLocked();
                activeBufferedFrames.clear();

                return removed;
            }

            return queue.removeById(entryId);
        }
    }

    @Override
    public boolean clearQueuedEntries() {
        synchronized (lock) {
            if (closed) {
                return false;
            }

            boolean changed = queue.clearQueueOnly();

            if (incomingEntry != null) {
                cancelIncomingPreloadLocked(false);
                resetCrossfadeStateLocked();
                activeBufferedFrames.clear();
                changed = true;
            }

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

            if (incomingEntry != null) {
                cancelIncomingPreloadLocked(false);
                resetCrossfadeStateLocked();
                activeBufferedFrames.clear();
                changed = true;
            }

            return changed;
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (closed) {
                return;
            }

            queue.clear();
            currentEntry = null;
            queueEndEmitted = true;

            activeBufferedFrames.clear();
            clearIncomingStateLocked(true);
            promotedFinishedTrack = null;
            promotedFinishedReason = null;
            clearActiveTrackLocked();
            clearIncomingTrackLocked();

            suppressEvents = true;
            activePlayer.stopTrack();
            incomingPlayer.stopTrack();
            suppressEvents = false;

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
            incomingPlayer.setPaused(true);
        }
    }

    @Override
    public void resume() {
        synchronized (lock) {
            paused = false;
            activePlayer.setPaused(false);
            incomingPlayer.setPaused(false);
        }
    }

    @Override
    public void setVolume(int volume) {
        synchronized (lock) {
            this.volume = Math.max(0, Math.min(1000, volume));
            activePlayer.setVolume(this.volume);
            incomingPlayer.setVolume(this.volume);
        }
    }

    @Override
    public void setFilterFactory(PcmFilterFactory factory) {
        synchronized (lock) {
            this.filterFactory = factory;
            activePlayer.setFilterFactory(factory);
            incomingPlayer.setFilterFactory(factory);

            if (!crossfading && incomingStarted) {
                cancelIncomingPreloadLocked(true);
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
    public boolean isTransitioning() {
        synchronized (lock) {
            return crossfading || incomingStarted || !activeBufferedFrames.isEmpty();
        }
    }

    @Override
    public void restore(PlayerFrameProviderSnapshot snapshot) {
        synchronized (lock) {
            if (closed) {
                return;
            }

            cancelIncomingPreloadLocked(false);
            activeBufferedFrames.clear();
            promotedFinishedTrack = null;
            promotedFinishedReason = null;

            this.currentEntry = snapshot.currentEntry();
            this.volume = Math.max(0, Math.min(1000, snapshot.volume()));
            this.paused = snapshot.paused();
            this.filterFactory = snapshot.filterFactory();

            activePlayer.setFilterFactory(filterFactory);
            activePlayer.setVolume(volume);
            activePlayer.setPaused(paused);

            clearActiveTrackLocked();
            clearIncomingTrackLocked();

            suppressEvents = true;
            activePlayer.stopTrack();
            incomingPlayer.stopTrack();
            suppressEvents = false;

            if (currentEntry == null) {
                session.resetPosition();
                return;
            }

            AudioTrack track = currentEntry.getTrack().makeClone();
            track.setPosition(snapshot.position());

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

            session.setPosition(track.getPosition());
        }
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            closed = true;

            activeBufferedFrames.clear();
            clearIncomingStateLocked(true);
            promotedFinishedTrack = null;
            promotedFinishedReason = null;
            clearActiveTrackLocked();
            clearIncomingTrackLocked();

            suppressEvents = true;
            activePlayer.stopTrack();
            incomingPlayer.stopTrack();
            suppressEvents = false;

            activePlayer.destroy();
            incomingPlayer.destroy();
            opusEncoder.close();

            currentEntry = null;
        }
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        synchronized (lock) {
            if (closed || suppressEvents) {
                return;
            }

            if (player == incomingPlayer) {
                if (!isIncomingEventLocked(player, track)) {
                    return;
                }

                incomingFinished = true;
                incomingFinishedTrack = track;
                incomingFinishedReason = endReason;
                return;
            }

            if (!isActiveEventLocked(player, track)) {
                return;
            }

            if (crossfading) {
                return;
            }

            if (incomingStarted && incomingEntry != null) {
                finishCrossfadeLocked(endReason);
                return;
            }

            QueueEntry endedEntry = currentEntry;

            if (endedEntry != null) {
                listener.onTrackEnd(session, track, endReason);

                if (endReason == AudioTrackEndReason.FINISHED) {
                    queue.addToHistory(endedEntry.copyWithPosition(0));
                }
            }

            currentEntry = null;
            clearActiveTrackLocked();
            promotedFinishedTrack = null;
            promotedFinishedReason = null;
            activeBufferedFrames.clear();
            session.resetPosition();

            if (endReason.mayStartNext) {
                if (!startNextLocked(false)) {
                    emitQueueEndLocked();
                }

                return;
            }

            if (queue.isEmpty()) {
                emitQueueEndLocked();
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

                if (incomingStarted && incomingEntry != null) {
                    finishCrossfadeLocked(AudioTrackEndReason.LOAD_FAILED);
                    return;
                }

                currentEntry = null;
                clearActiveTrackLocked();
                session.resetPosition();
                startNextLocked(false);
                return;
            }

            if (player == incomingPlayer) {
                if (!isIncomingEventLocked(player, track)) {
                    return;
                }

                listener.onTrackException(session, track, exception);
                cancelIncomingPreloadLocked(false);
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

                if (incomingStarted && incomingEntry != null) {
                    finishCrossfadeLocked(AudioTrackEndReason.LOAD_FAILED);
                    return;
                }

                currentEntry = null;
                clearActiveTrackLocked();
                session.resetPosition();
                startNextLocked(false);
                return;
            }

            if (player == incomingPlayer) {
                if (!isIncomingEventLocked(player, track)) {
                    return;
                }

                log.debug("Incoming track stuck after {}ms: {}", thresholdMs, track.getInfo().uri);
                listener.onTrackStuck(session, track, thresholdMs);
                cancelIncomingPreloadLocked(false);
            }
        }
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

    private void bindIncomingTrackLocked(AudioTrack track) {
        trackEventGuard.unbind(incomingTrack);
        incomingTrack = track;
        incomingTrackGeneration = trackEventGuard.bind(track);
    }

    private void clearIncomingTrackLocked() {
        incomingTrackGeneration = trackEventGuard.invalidate(incomingTrack);
        incomingTrack = null;
    }

    private void promoteIncomingTrackToActiveLocked() {
        if (incomingTrack == null) {
            clearActiveTrackLocked();
            return;
        }

        trackEventGuard.unbind(activeTrack);
        activeTrack = incomingTrack;
        activeTrackGeneration = incomingTrackGeneration;
        incomingTrack = null;
        incomingTrackGeneration = trackEventGuard.invalidate(null);
    }

    private boolean isActiveEventLocked(AudioPlayer player, AudioTrack track) {
        return player == activePlayer
                && trackEventGuard.accepts(activeTrack, activeTrackGeneration, track);
    }

    private boolean isIncomingEventLocked(AudioPlayer player, AudioTrack track) {
        return player == incomingPlayer
                && trackEventGuard.accepts(incomingTrack, incomingTrackGeneration, track);
    }

    private void emitPromotedFinishedLocked() {
        AudioTrack track = promotedFinishedTrack != null
                ? promotedFinishedTrack
                : activePlayer.getPlayingTrack();

        AudioTrackEndReason reason = promotedFinishedReason != null
                ? promotedFinishedReason
                : AudioTrackEndReason.FINISHED;

        if (currentEntry != null) {
            listener.onTrackEnd(session, track != null ? track : currentEntry.getTrack(), reason);
        }

        currentEntry = null;
        clearActiveTrackLocked();
        promotedFinishedTrack = null;
        promotedFinishedReason = null;
        session.resetPosition();

        if (reason.mayStartNext) {
            startNextLocked(false);
        } else if (queue.isEmpty()) {
            emitQueueEndLocked();
        }
    }

    private void emitQueueEndLocked() {
        if (queueEndEmitted) {
            return;
        }

        queueEndEmitted = true;
        listener.onQueueUpdate(session);
        listener.onQueueEnd(session);
    }

    private double getPositionIncrementMs() {
        return CrossfadeAudio.FRAME_MS
                * session.getPlayerFilters().timescale().speed()
                * session.getPlayerFilters().timescale().rate();
    }
}
