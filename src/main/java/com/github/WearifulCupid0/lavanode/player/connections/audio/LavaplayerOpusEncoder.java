package com.github.WearifulCupid0.lavanode.player.connections.audio;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkEncoder;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Encodes the canonical big-endian PCM frame with Lavaplayer's Opus encoder. */
public final class LavaplayerOpusEncoder implements AutoCloseable {
    private static final AudioDataFormat PCM_FORMAT = StandardAudioDataFormats.DISCORD_PCM_S16_BE;
    private static final AudioDataFormat OPUS_FORMAT = StandardAudioDataFormats.DISCORD_OPUS;

    private final AudioChunkEncoder encoder;
    private final ByteBuffer nativeInputBytes = ByteBuffer
            .allocateDirect(PCM_FORMAT.totalSampleCount() * Short.BYTES)
            .order(ByteOrder.nativeOrder());
    private final ShortBuffer nativeInput = nativeInputBytes.asShortBuffer();
    private final ByteBuffer opusOutput = ByteBuffer.allocateDirect(OPUS_FORMAT.maximumChunkSize());

    public LavaplayerOpusEncoder(AudioConfiguration baseConfiguration) {
        AudioConfiguration configuration = baseConfiguration.copy();
        configuration.setOutputFormat(OPUS_FORMAT);
        this.encoder = OPUS_FORMAT.createEncoder(configuration);
    }

    public byte[] encode(byte[] pcmS16Be) {
        if (pcmS16Be == null || pcmS16Be.length == 0) {
            return null;
        }

        ShortBuffer source = ByteBuffer
                .wrap(pcmS16Be)
                .order(ByteOrder.BIG_ENDIAN)
                .asShortBuffer();

        nativeInput.clear();

        int samples = Math.min(source.remaining(), nativeInput.remaining());
        for (int i = 0; i < samples; i++) {
            nativeInput.put(source.get());
        }

        while (nativeInput.hasRemaining()) {
            nativeInput.put((short) 0);
        }

        nativeInput.flip();
        opusOutput.clear();
        encoder.encode(nativeInput, opusOutput);

        if (!opusOutput.hasRemaining()) {
            return null;
        }

        byte[] opus = new byte[opusOutput.remaining()];
        opusOutput.get(opus);
        return opus;
    }

    @Override
    public void close() {
        encoder.close();
    }
}
