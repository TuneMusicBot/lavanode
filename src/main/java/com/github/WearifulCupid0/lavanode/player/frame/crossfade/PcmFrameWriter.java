package com.github.WearifulCupid0.lavanode.player.frame.crossfade;

import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

/** Writes signed 16-bit PCM samples using the canonical big-endian format. */
final class PcmFrameWriter {
    private final byte[] bytes = new byte[CrossfadeAudio.PCM_FORMAT.maximumChunkSize()];

    boolean write(short[] samples, MutableAudioFrame targetFrame) {
        int sampleCount = Math.min(samples.length, bytes.length / Short.BYTES);

        for (int i = 0; i < sampleCount; i++) {
            short sample = samples[i];
            bytes[i * 2] = (byte) ((sample >>> 8) & 0xFF);
            bytes[i * 2 + 1] = (byte) (sample & 0xFF);
        }

        int length = sampleCount * Short.BYTES;
        if (length <= 0) {
            return false;
        }

        targetFrame.store(bytes, 0, length);
        return true;
    }
}
