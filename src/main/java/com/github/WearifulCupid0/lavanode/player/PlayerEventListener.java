package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.player.queue.QueueEntry;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

import java.net.InetSocketAddress;

public interface PlayerEventListener {
    void onQueueUpdate(PlayerSession player);

    void onTrackStart(PlayerSession player, QueueEntry entry);

    void onTrackEnd(PlayerSession player, AudioTrack track, AudioTrackEndReason reason);

    void onTrackException(PlayerSession player, AudioTrack track, FriendlyException exception);

    void onTrackStuck(PlayerSession player, AudioTrack track, long thresholdMs);

    void onQueueEnd(PlayerSession player);

    void onGatewayError(PlayerSession player, Throwable error);

    void onGatewayClosed(PlayerSession player, int code, String reason, boolean byRemote);

    void onGatewayReady(PlayerSession player, InetSocketAddress address, int ssrc);
}
