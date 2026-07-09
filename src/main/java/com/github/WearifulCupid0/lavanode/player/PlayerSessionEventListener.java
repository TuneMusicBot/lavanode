package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.player.queue.QueueEntry;
import com.github.WearifulCupid0.lavanode.server.websocket.WebsocketOpCodes;
import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class PlayerSessionEventListener extends AudioEventAdapter implements PlayerEventListener {
    private static final Logger log = LoggerFactory.getLogger(PlayerSessionEventListener.class);

    private final PlayerManager playerManager;

    public PlayerSessionEventListener(PlayerManager playerManager) {
        this.playerManager = playerManager;
    }

    @Override
    public void onQueueUpdate(PlayerSession player) {
        log.debug("Queue updated! Player ID {}", player.getId());
        playerManager.dispatchEvent(WebsocketOpCodes.queueUpdate, withPlayer(player, player.getQueue().sizeToJson()), player.getUserId());
    }

    @Override
    public void onTrackStart(PlayerSession player, QueueEntry entry) {
        log.debug("Track {} started! Player ID {}", entry.getTrack().getIdentifier(), player.getId());

        player.setPosition(entry.getTrack().getPosition());
        player.getFrameLossCounter().start();

        playerManager.dispatchEvent(
                WebsocketOpCodes.trackStart,
                withPlayer(player, entry.toJson(playerManager.getAudioPlayerManager())),
                player.getUserId()
        );
    }

    @Override
    public void onTrackEnd(PlayerSession player, AudioTrack track, AudioTrackEndReason reason) {
        log.debug("Track {} ended! Player ID {}", track.getIdentifier(), player.getId());

        player.resetPosition();
        player.getFrameLossCounter().end();

        JsonObject json = new JsonObject()
                .put("track", RequestUtil.trackToJson(playerManager.getAudioPlayerManager(), track))
                .put("endReason", reason.toString());

        playerManager.dispatchEvent(WebsocketOpCodes.trackEnd, withPlayer(player, json), player.getUserId());
    }

    @Override
    public void onTrackException(PlayerSession player, AudioTrack track, FriendlyException exception) {
        log.error("Track {} error! Player ID {}, error: ", track.getIdentifier(), player.getId(), exception);

        JsonObject json = new JsonObject()
                .put("track", RequestUtil.trackToJson(playerManager.getAudioPlayerManager(), track))
                .put("severity", exception.severity.toString())
                .put("exception", RequestUtil.encodeThrowable(exception));

        playerManager.dispatchEvent(WebsocketOpCodes.trackException, withPlayer(player, json), player.getUserId());
    }

    @Override
    public void onTrackStuck(PlayerSession player, AudioTrack track, long thresholdMs) {
        log.debug("Track {} stucked! Player ID {}", track.getIdentifier(), player.getId());
        JsonObject json = new JsonObject()
                .put("track", RequestUtil.trackToJson(playerManager.getAudioPlayerManager(), track))
                .put("thresholdMs", thresholdMs);

        playerManager.dispatchEvent(WebsocketOpCodes.trackStuck, withPlayer(player, json), player.getUserId());
    }

    @Override
    public void onQueueEnd(PlayerSession player) {
        log.debug("Queue ended! Player ID {}", player.getId());
        playerManager.dispatchEvent(WebsocketOpCodes.queueEnd, withPlayer(player, player.getQueue().sizeToJson()), player.getUserId());
    }

    @Override
    public void onGatewayError(PlayerSession player, Throwable error) {
        playerManager.dispatchEvent(
                WebsocketOpCodes.gatewayError,
                withPlayer(player, new JsonObject()
                        .put("error", RequestUtil.encodeThrowable(error))
                ),
                player.getUserId()
        );
    }

    @Override
    public void onGatewayClosed(PlayerSession player, int code, String reason, boolean byRemote) {
        playerManager.dispatchEvent(
                WebsocketOpCodes.gatewayDisconnect,
                withPlayer(player, new JsonObject()
                        .put("code", code)
                        .put("reason", reason)
                        .put("byRemote", byRemote)
                ),
                player.getUserId()
        );
    }

    @Override
    public void onGatewayReady(PlayerSession player, InetSocketAddress address, int ssrc) {
        playerManager.dispatchEvent(
                WebsocketOpCodes.gatewayReady,
                withPlayer(player, new JsonObject()
                        .put("address", address.toString())
                        .put("ssrc", ssrc)
                ),
                player.getUserId()
        );
    }

    private JsonObject withPlayer(PlayerSession player, JsonObject data) {
        JsonObject json = data == null ? new JsonObject() : data.copy();

        return json
                .put("guildId", player.getId())
                .put("userId", player.getUserId());
    }
}
