package com.github.WearifulCupid0.lavanode.player.frame;

import com.github.WearifulCupid0.lavanode.player.queue.QueueEntry;
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;

public record PlayerFrameProviderSnapshot(
        QueueEntry currentEntry,
        long position,
        boolean paused,
        int volume,
        PcmFilterFactory filterFactory
) {
}