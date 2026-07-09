package com.github.WearifulCupid0.lavanode.player.frame.crossfade;

import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkEncoder;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

final class PcmToOpusEncoder implements AutoCloseable {
    private final AudioChunkEncoder encoder;

    private final ByteBuffer encoderInputBytes = ByteBuffer
            .allocateDirect(CrossfadeAudio.SAMPLE_COUNT * Short.BYTES)
            .order(ByteOrder.nativeOrder());

    private final ShortBuffer encoderInput = encoderInputBytes.asShortBuffer();

    private final ByteBuffer opusOutput = ByteBuffer.allocateDirect(
            CrossfadeAudio.OPUS_FORMAT.maximumChunkSize()
    );

    private final byte[] opusBytes = new byte[CrossfadeAudio.OPUS_FORMAT.maximumChunkSize()];

    PcmToOpusEncoder(AudioConfiguration baseConfiguration) {
        AudioConfiguration opusConfiguration = baseConfiguration.copy();
        opusConfiguration.setOutputFormat(CrossfadeAudio.OPUS_FORMAT);

        this.encoder = CrossfadeAudio.OPUS_FORMAT.createEncoder(opusConfiguration);
    }

    boolean encode(short[] samples, MutableAudioFrame targetFrame) {
        encoderInput.clear();
        encoderInput.put(samples, 0, CrossfadeAudio.SAMPLE_COUNT);
        encoderInput.flip();

        opusOutput.clear();

        /*
         * Importante: encode(input, output) não retorna os bytes. Ele grava dentro
         * do output. Algumas versões já deixam o ByteBuffer em modo leitura; outras
         * deixam position = tamanho escrito. Este ajuste aceita os dois comportamentos.
         */
        encoder.encode(encoderInput, opusOutput);

        if (opusOutput.position() > 0) {
            opusOutput.flip();
        }

        int length = opusOutput.remaining();

        if (length <= 0) {
            return false;
        }

        opusOutput.get(opusBytes, 0, length);
        targetFrame.store(opusBytes, 0, length);

        return true;
    }

    @Override
    public void close() {
        encoder.close();
    }
}
