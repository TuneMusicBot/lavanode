package com.github.WearifulCupid0.lavanode.player.connections.discord;

import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.connections.OpusFrameSubscription;
import io.netty.buffer.ByteBuf;
import moe.kyokobot.koe.codec.CodecInstance;
import moe.kyokobot.koe.media.AudioFrameProvider;
import org.jetbrains.annotations.NotNull;

/** Koe sender backed by the player's shared PCM -> Lavaplayer Opus branch. */
public final class DiscordFrameDispatcher implements AudioFrameProvider {
    private static final int DISCORD_BUFFER_FRAMES = 10;

    private final OpusFrameSubscription subscription;
    private byte[] pendingFrame;

    public DiscordFrameDispatcher(PlayerSession playerSession) {
        this.subscription = playerSession.openOpusFrameSubscription(DISCORD_BUFFER_FRAMES);
    }

    @Override
    public void onCodecChanged(@NotNull CodecInstance codec) {
    }

    @Override
    public void dispose() {
        subscription.close();
        pendingFrame = null;
    }

    @Override
    public boolean canProvide() {
        if (pendingFrame != null) {
            return true;
        }

        pendingFrame = subscription.poll();
        return pendingFrame != null;
    }

    @Override
    public boolean provideFrame(ByteBuf buf) {
        if (pendingFrame == null && !canProvide()) {
            return false;
        }

        buf.writeBytes(pendingFrame);
        pendingFrame = null;
        return true;
    }
}
