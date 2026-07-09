package com.github.WearifulCupid0.lavanode.player.voice;

import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import io.netty.buffer.ByteBuf;
import moe.kyokobot.koe.codec.CodecInstance;
import moe.kyokobot.koe.media.AudioFrameProvider;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

public class PlayerProvider implements AudioFrameProvider {
    private final ByteBuffer buffer = ByteBuffer.allocate(
            StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    );

    private final MutableAudioFrame audioFrame = new MutableAudioFrame();

    private final PlayerSession playerSession;

    private boolean frameAvailable;

    public PlayerProvider(PlayerSession playerSession) {
        this.playerSession = playerSession;
        this.audioFrame.setBuffer(buffer);
    }

    @Override
    public void onCodecChanged(@NotNull CodecInstance codec) {
    }

    @Override
    public void dispose() {
    }

    @Override
    public boolean canProvide() {
        if (frameAvailable) {
            return true;
        }

        buffer.clear();

        frameAvailable = playerSession.provide(audioFrame);

        if (frameAvailable) {
            buffer.flip();
        }

        return frameAvailable;
    }

    @Override
    public boolean provideFrame(ByteBuf buf) {
        if (!frameAvailable && !canProvide()) {
            return false;
        }

        buf.writeBytes(buffer);

        frameAvailable = false;

        return true;
    }
}