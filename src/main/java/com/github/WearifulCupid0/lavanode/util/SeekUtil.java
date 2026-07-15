package com.github.WearifulCupid0.lavanode.util;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

public final class SeekUtil {
    public static long clampPosition(AudioTrack track, long positionMs) {
        long position = Math.max(0L, positionMs);

        if (track == null) {
            return position;
        }

        long duration = track.getDuration();

        if (!track.getInfo().isStream && duration > 0L) {
            position = Math.min(position, duration);
        }

        return position;
    }

    public static boolean canSeek(AudioTrack track) {
        return track != null && track.isSeekable();
    }
}
