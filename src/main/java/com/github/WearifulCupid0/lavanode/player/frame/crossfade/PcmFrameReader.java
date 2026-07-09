package com.github.WearifulCupid0.lavanode.player.frame.crossfade;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

import java.nio.ByteBuffer;
import java.util.Arrays;

final class PcmFrameReader {
    private final MutableAudioFrame frame = new MutableAudioFrame();
    private final ByteBuffer frameBuffer = ByteBuffer.allocate(CrossfadeAudio.PCM_FORMAT.maximumChunkSize());

    boolean read(AudioPlayer player, short[] samples) {
        frameBuffer.clear();
        frame.setBuffer(frameBuffer);

        boolean provided = player.provide(frame);

        if (!provided || frame.isTerminator()) {
            fillSilence(samples);
            return false;
        }

        int length = frameBuffer.position();

        if (length <= 0) {
            fillSilence(samples);
            return false;
        }

        frameBuffer.flip();
        decodeS16Be(frameBuffer, length, samples);
        return true;
    }

    private static void decodeS16Be(ByteBuffer bytes, int length, short[] samples) {
        int sampleCount = Math.min(samples.length, length / Short.BYTES);

        for (int i = 0; i < sampleCount; i++) {
            int high = bytes.get() & 0xFF;
            int low = bytes.get() & 0xFF;

            samples[i] = (short) ((high << 8) | low);
        }

        if (sampleCount < samples.length) {
            Arrays.fill(samples, sampleCount, samples.length, (short) 0);
        }
    }

    static void fillSilence(short[] samples) {
        Arrays.fill(samples, (short) 0);
    }
}
