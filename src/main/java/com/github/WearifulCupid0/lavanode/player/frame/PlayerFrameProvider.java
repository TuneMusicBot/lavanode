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

    void skip();

    void previous();

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
