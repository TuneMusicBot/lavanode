package com.github.WearifulCupid0.lavanode.server.websocket;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import com.github.WearifulCupid0.lavanode.player.PlayerManager;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlayerUpdateBroadcaster {
    private final static Logger log = LoggerFactory.getLogger(PlayerUpdateBroadcaster.class);

    private final Vertx vertx;
    private final PlayerManager playerManager;
    private final AudioPlayerManager audioPlayerManager;
    private final long intervalMs;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private long timerId = -1;

    public PlayerUpdateBroadcaster(
            Vertx vertx,
            PlayerManager playerManager,
            AudioPlayerManager audioPlayerManager,
            long intervalMs
    ) {
        this.vertx = vertx;
        this.playerManager = playerManager;
        this.audioPlayerManager = audioPlayerManager;
        this.intervalMs = intervalMs;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        this.timerId = vertx.setPeriodic(intervalMs, ignored -> broadcastPlayerUpdates());
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }

        if (timerId != -1) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    private void broadcastPlayerUpdates() {
        List<PlayerSession> players = playerManager.getPlayersSnapshot();

        for (PlayerSession player : players) {
            try {
                if (!shouldSendUpdate(player)) {
                    continue;
                }

                JsonObject payload = player.toJson(audioPlayerManager);

                payload.put("guildId", player.getId());
                payload.put("userId", player.getUserId());

                playerManager.dispatchEvent(
                        WebsocketOpCodes.playerUpdate,
                        payload,
                        player.getUserId()
                );
            } catch (Exception exception) {
                log.warn(
                        "Failed to send playerUpdate for player {}",
                        player.getId(),
                        exception
                );
            }
        }
    }

    private boolean shouldSendUpdate(PlayerSession player) {
        return player != null;
    }
}
