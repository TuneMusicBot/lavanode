package com.github.WearifulCupid0.lavanode.player.frame.crossfade;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;

final class CrossfadeAudio {
    static final AudioDataFormat PCM_FORMAT = StandardAudioDataFormats.DISCORD_PCM_S16_BE;

    static final int SAMPLE_COUNT = PCM_FORMAT.totalSampleCount();
    static final int FRAME_MS = (int) PCM_FORMAT.frameDuration();
    static final double MIX_HEADROOM = 0.90;

    private CrossfadeAudio() {
    }
}
