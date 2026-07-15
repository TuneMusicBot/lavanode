package com.github.WearifulCupid0.lavanode.player.frame;

import com.github.WearifulCupid0.lavanode.player.queue.QueueEntry;
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

import java.util.List;

public interface PlayerFrameProvider {
    void setFilterFactory(PcmFilterFactory factory);

    void enqueueMany(List<QueueEntry> entries);

    void enqueue(QueueEntry entry);

    /**
     * Immediately replaces the current playing track with the provided entry.
     *
     * The currently audible entry, when present, must be moved to history and
     * ended with STOPPED. The forced entry then becomes the current track and
     * will be moved to history normally when it finishes.
     */
    void play(QueueEntry entry);

    void skip();

    void previous();

    boolean seek(long positionMs);

    default void onLoopOptionsUpdated() {
    }

    QueueEntry removeQueuedEntry(String entryId);

    boolean clearQueuedEntries();

    boolean clearQueueHistory();

    boolean clearQueuedEntriesAndHistory();

    void stop();

    void pause();

    void resume();

    void setVolume(int volume);

    AudioTrack getPlayingTrack();

    QueueEntry getCurrentEntry();

    AudioPlayer getAudioPlayer();

    void destroy();

    boolean provide(MutableAudioFrame audioFrame);

    default boolean isTransitioning() {
        return false;
    }

    void restore(PlayerFrameProviderSnapshot snapshot);
}
