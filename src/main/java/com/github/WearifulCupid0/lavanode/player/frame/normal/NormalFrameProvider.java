package com.github.WearifulCupid0.lavanode.player.frame.normal;

import com.github.WearifulCupid0.lavanode.player.PlayerEventListener;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProvider;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProviderSnapshot;
import com.github.WearifulCupid0.lavanode.player.frame.TrackEventGuard;
import com.github.WearifulCupid0.lavanode.player.queue.PlayerQueue;
import com.github.WearifulCupid0.lavanode.player.queue.QueueEntry;
import com.github.WearifulCupid0.lavanode.util.SeekUtil;
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

import java.util.List;

public class NormalFrameProvider extends AudioEventAdapter implements PlayerFrameProvider {
    private final Object lock = new Object();

    private final PlayerSession session;
    private final PlayerQueue queue;
    private final AudioPlayer audioPlayer;
    private final PlayerEventListener listener;

    private final TrackEventGuard trackEventGuard = new TrackEventGuard();

    private QueueEntry currentEntry;
    private AudioTrack activeTrack;
    private long activeTrackGeneration;
    private PcmFilterFactory filterFactory;
    private int volume = 100;
    private boolean paused;
    private boolean suppressEvents;
    private boolean queueEndEmitted = true;

    public NormalFrameProvider(
            PlayerSession session,
            PlayerEventListener listener,
            AudioPlayerManager playerManager
    ) {
        this.session = session;
        this.queue = session.getQueue();
        this.audioPlayer = playerManager.createPlayer();
        this.listener = listener;

        this.audioPlayer.addListener(this);
    }

    @Override
    public void setFilterFactory(PcmFilterFactory factory) {
        synchronized (lock) {
            this.filterFactory = factory;
            this.audioPlayer.setFilterFactory(factory);
        }
    }

    @Override
    public void enqueueMany(List<QueueEntry> list) {
        if (list == null || list.isEmpty()) {
            return;
        }

        synchronized (lock) {
            this.queue.addAll(list);
            queueEndEmitted = false;
            listener.onQueueUpdate(session);

            if (currentEntry == null && audioPlayer.getPlayingTrack() == null) {
                startNextLocked(false);
            }
        }
    }

    @Override
    public void enqueue(QueueEntry entry) {
        synchronized (lock) {
            this.queue.add(entry);
            queueEndEmitted = false;
            listener.onQueueUpdate(session);

            if (currentEntry == null && audioPlayer.getPlayingTrack() == null) {
                startNextLocked(false);
            }
        }
    }

    private boolean startNextLocked(boolean stopIfEmpty) {
        QueueEntry next = queue.poll();

        if (next == null) {
            currentEntry = null;

            if (stopIfEmpty) {
                stopActiveSilentlyLocked();
                session.resetPosition();
            }

            return emitQueueEndLocked();
        }

        return startEntryLocked(next);
    }

    private boolean startEntryLocked(QueueEntry entry) {
        if (entry == null) {
            return false;
        }

        AudioTrack track = entry.getTrack().makeClone();
        track.setPosition(entry.getTrack().getPosition());

        audioPlayer.setFilterFactory(filterFactory);
        audioPlayer.setVolume(volume);
        audioPlayer.setPaused(paused);

        stopActiveSilentlyLocked();

        currentEntry = entry;
        bindActiveTrackLocked(track);
        queueEndEmitted = false;

        boolean started = audioPlayer.startTrack(track, false);

        if (!started) {
            if (activeTrack == track) {
                clearActiveTrackLocked();
            }
            currentEntry = null;
            session.resetPosition();
            return false;
        }

        audioPlayer.setPaused(paused);
        session.setPosition(track.getPosition());

        listener.onTrackStart(session, currentEntry);
        listener.onQueueUpdate(session);

        return true;
    }

    private void stopActiveSilentlyLocked() {
        clearActiveTrackLocked();
        suppressEvents = true;
        try {
            audioPlayer.stopTrack();
        } finally {
            suppressEvents = false;
        }
    }

    @Override
    public void play(QueueEntry entry) {
        if (entry == null) {
            return;
        }

        synchronized (lock) {
            QueueEntry replacedEntry = currentEntry;
            AudioTrack replacedTrack = activeTrack != null ? activeTrack : audioPlayer.getPlayingTrack();

            if (replacedEntry != null) {
                queue.addToHistory(replacedEntry.copyWithPosition(0));
                listener.onTrackEnd(
                        session,
                        replacedTrack != null ? replacedTrack : replacedEntry.getTrack(),
                        AudioTrackEndReason.STOPPED
                );
            }

            currentEntry = null;
            startEntryLocked(entry.copyWithPosition(0));
        }
    }

    @Override
    public void skip() {
        synchronized (lock) {
            QueueEntry skippedEntry = currentEntry;
            AudioTrack skippedTrack = activeTrack != null ? activeTrack : audioPlayer.getPlayingTrack();

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
            QueueEntry previousEntry = queue.pollPrevious();

            if (previousEntry == null) {
                return;
            }

            QueueEntry replacedEntry = currentEntry;
            AudioTrack replacedTrack = activeTrack != null ? activeTrack : audioPlayer.getPlayingTrack();

            if (replacedEntry != null) {
                queue.addFirst(replacedEntry.copyWithPosition(0));
                listener.onTrackEnd(
                        session,
                        replacedTrack != null ? replacedTrack : replacedEntry.getTrack(),
                        AudioTrackEndReason.STOPPED
                );
            }

            currentEntry = null;
            startEntryLocked(previousEntry.copyWithPosition(0));
        }
    }

    @Override
    public boolean seek(long positionMs) {
        synchronized (lock) {
            AudioTrack track = activeTrack != null ? activeTrack : audioPlayer.getPlayingTrack();

            if (!SeekUtil.canSeek(track)) {
                return false;
            }

            long position = SeekUtil.clampPosition(track, positionMs);

            track.setPosition(position);
            session.setPosition(position);

            return true;
        }
    }

    @Override
    public QueueEntry removeQueuedEntry(String entryId) {
        synchronized (lock) {
            return queue.removeById(entryId);
        }
    }

    @Override
    public boolean clearQueuedEntries() {
        synchronized (lock) {
            return queue.clearQueueOnly();
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
            return queue.clearQueueAndHistory();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            currentEntry = null;
            queue.clear();
            queueEndEmitted = true;
            stopActiveSilentlyLocked();
            session.resetPosition();
            listener.onQueueUpdate(session);
            listener.onQueueEnd(session);
        }
    }

    @Override
    public void pause() {
        synchronized (lock) {
            paused = true;
            audioPlayer.setPaused(true);
        }
    }

    @Override
    public void resume() {
        synchronized (lock) {
            paused = false;
            audioPlayer.setPaused(false);
        }
    }

    @Override
    public void setVolume(int volume) {
        synchronized (lock) {
            this.volume = Math.max(0, Math.min(1000, volume));
            audioPlayer.setVolume(this.volume);
        }
    }

    @Override
    public AudioTrack getPlayingTrack() {
        synchronized (lock) {
            return audioPlayer.getPlayingTrack();
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
            return audioPlayer;
        }
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            currentEntry = null;
            stopActiveSilentlyLocked();
            audioPlayer.destroy();
        }
    }

    @Override
    public boolean provide(MutableAudioFrame audioFrame) {
        synchronized (lock) {
            return audioPlayer.provide(audioFrame);
        }
    }

    @Override
    public void restore(PlayerFrameProviderSnapshot snapshot) {
        synchronized (lock) {
            this.currentEntry = snapshot.currentEntry();
            this.filterFactory = snapshot.filterFactory();
            this.volume = Math.max(0, Math.min(1000, snapshot.volume()));
            this.paused = snapshot.paused();

            stopActiveSilentlyLocked();

            if (this.currentEntry == null) {
                session.resetPosition();
                return;
            }

            AudioTrack track = this.currentEntry.getTrack().makeClone();
            track.setPosition(snapshot.position());

            this.audioPlayer.setFilterFactory(filterFactory);
            this.audioPlayer.setVolume(volume);
            this.audioPlayer.setPaused(paused);

            bindActiveTrackLocked(track);

            boolean started = this.audioPlayer.startTrack(track, false);

            if (!started) {
                if (activeTrack == track) {
                    clearActiveTrackLocked();
                }
                this.currentEntry = null;
                session.resetPosition();
                return;
            }

            this.audioPlayer.setPaused(paused);
            session.setPosition(track.getPosition());
        }
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        synchronized (lock) {
            if (suppressEvents || !isActiveEventLocked(player, track)) {
                return;
            }

            QueueEntry endedEntry = currentEntry;

            if (endedEntry != null) {
                listener.onTrackEnd(session, track, endReason);

                if (endReason == AudioTrackEndReason.FINISHED && session.isTrackLoop()) {
                    currentEntry = null;
                    clearActiveTrackLocked();
                    session.resetPosition();
                    startEntryLocked(endedEntry.copyWithPosition(0));
                    return;
                }

                handleFinishedEntryLoopLocked(endedEntry, endReason);
            }

            currentEntry = null;
            clearActiveTrackLocked();
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
            if (suppressEvents || !isActiveEventLocked(player, track)) {
                return;
            }

            listener.onTrackException(session, track, exception);
        }
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        synchronized (lock) {
            if (suppressEvents || !isActiveEventLocked(player, track)) {
                return;
            }

            listener.onTrackStuck(session, track, thresholdMs);
        }
    }

    private void handleFinishedEntryLoopLocked(QueueEntry entry, AudioTrackEndReason endReason) {
        if (entry == null || endReason != AudioTrackEndReason.FINISHED) {
            return;
        }

        queue.addToHistory(entry.copyWithPosition(0));
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

    private boolean isActiveEventLocked(AudioPlayer player, AudioTrack track) {
        return player == audioPlayer
                && trackEventGuard.accepts(activeTrack, activeTrackGeneration, track);
    }

    private boolean emitQueueEndLocked() {
        if (queueEndEmitted) {
            return false;
        }

        queueEndEmitted = true;
        listener.onQueueUpdate(session);
        listener.onQueueEnd(session);

        if (session.isQueueLoop() && queue.moveHistoryToQueueFromStart()) {
            queueEndEmitted = false;
            listener.onQueueUpdate(session);
            return startNextLocked(false);
        }

        return false;
    }
}
