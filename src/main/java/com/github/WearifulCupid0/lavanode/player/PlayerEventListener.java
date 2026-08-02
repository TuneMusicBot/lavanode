package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.player.queue.QueueEntry;
import com.github.WearifulCupid0.lavanode.player.connections.PlayerConnection;
import com.github.WearifulCupid0.lavanode.server.websocket.WebsocketOpCodes;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

import java.net.InetSocketAddress;

public interface PlayerEventListener {
    void onPlayerUpdate(PlayerSession player, WebsocketOpCodes code);

    void onPlayerDestroy(PlayerSession player);

    void onQueueUpdate(PlayerSession player);

    void onQueueClear(PlayerSession player);

    void onQueueShuffle(PlayerSession player);

    void onQueueEntryRemoved(PlayerSession player, QueueEntry entry);

    void onQueueEnd(PlayerSession player);

    void onTrackStart(PlayerSession player, QueueEntry entry);

    void onTrackEnd(PlayerSession player, AudioTrack track, AudioTrackEndReason reason);

    void onTrackException(PlayerSession player, AudioTrack track, FriendlyException exception);

    void onTrackStuck(PlayerSession player, AudioTrack track, long thresholdMs);


    void onConnectionCreate(PlayerSession player, PlayerConnection connection);

    void onConnectionDelete(PlayerSession player, PlayerConnection connection, String reason);

    void onConnectionDisconnect(PlayerSession player, PlayerConnection connection, String reason);

    void onConnectionError(PlayerSession player, PlayerConnection connection, Throwable error);

    void onGatewayError(PlayerSession player, Throwable error);

    void onGatewayClosed(PlayerSession player, int code, String reason, boolean byRemote);

    void onGatewayReady(PlayerSession player, InetSocketAddress address, int ssrc);
}
