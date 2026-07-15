package com.github.WearifulCupid0.lavanode.player.frame.gapless;

import com.github.WearifulCupid0.lavanode.player.queue.PreparedTrack;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class GaplessPreloadPump {
    private static final Logger log = LoggerFactory.getLogger(GaplessPreloadPump.class);

    @FunctionalInterface
    interface FailureHandler {
        void onFailure(PreparedTrack prepared, long retryDelayMs);
    }

    private final GaplessPreloadConfig config;
    private final FailureHandler failureHandler;

    GaplessPreloadPump(GaplessPreloadConfig config, FailureHandler failureHandler) {
        this.config = config;
        this.failureHandler = failureHandler;
    }

    void pump(PreparedTrack prepared) {
        ByteBuffer preloadBuffer = ByteBuffer.allocate(
                StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
        );

        MutableAudioFrame preloadFrame = new MutableAudioFrame();
        preloadFrame.setBuffer(preloadBuffer);

        long startedAt = System.currentTimeMillis();
        long effectiveTimeoutMs = config.effectiveTimeoutMs();

        try {
            while (prepared.isRunning()) {
                long elapsed = System.currentTimeMillis() - startedAt;

                if (elapsed >= effectiveTimeoutMs && prepared.getBuffer().isEmpty()) {
                    failureHandler.onFailure(prepared, 2_000L);

                    log.debug(
                            "Preload timed out after {}ms for track {}. Falling back to normal playback.",
                            elapsed,
                            prepared.getEntry().getTrack().getInfo().uri
                    );

                    return;
                }

                preloadBuffer.clear();

                boolean provided;

                try {
                    provided = prepared.getPlayer().provide(
                            preloadFrame,
                            500,
                            TimeUnit.MILLISECONDS
                    );
                } catch (TimeoutException timeout) {
                    if (prepared.getPlayer().getPlayingTrack() == null) {
                        prepared.markFinished();
                        return;
                    }

                    continue;
                }

                if (!provided) {
                    if (prepared.getPlayer().getPlayingTrack() == null) {
                        prepared.markFinished();
                        return;
                    }

                    continue;
                }

                if (preloadFrame.isTerminator()) {
                    prepared.markFinished();
                    return;
                }

                byte[] data = preloadFrame.getData();

                if (data == null || data.length == 0) {
                    continue;
                }

                GaplessFrameData frameData = new GaplessFrameData(Arrays.copyOf(data, data.length));

                while (prepared.isRunning()) {
                    if (prepared.getBuffer().offer(frameData, 100, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Throwable throwable) {
            failureHandler.onFailure(prepared, 2_000L);

            log.debug(
                    "Failed to preload next track {}. Falling back to normal playback.",
                    prepared.getEntry().getTrack().getInfo().uri,
                    throwable
            );
        }
    }
}
